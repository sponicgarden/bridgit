package net.finleg.bridget.capability

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.ktor.server.routing.Route
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.finleg.bridget.BridgeApp
import net.finleg.bridget.R
import net.finleg.bridget.api.ApiServer
import net.finleg.bridget.api.Endpoints
import net.finleg.bridget.api.ErrorCatalog
import net.finleg.bridget.api.RateLimiter
import net.finleg.bridget.api.Reply
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/** Pure rules for speaker requests (SPK-01, SPK-02), unit-tested. */
object SpeakerRules {
    const val MAX_QUEUE_PER_AGENT = 5
    const val PLAYS_PER_HOUR = 60
    const val MAX_BYTES = 25L * 1024 * 1024
    const val MAX_CLIP_MS = 10 * 60_000L
    const val MAX_SAY_CHARS = 4000
    const val DEFAULT_CEILING = 70

    /** "after_current" (default): replace what's queued for this agent/tour, let the current one finish. "now": stop it too. "queue": add. */
    val REPLACE = setOf("after_current", "now", "queue")

    /** SPK-02: public HTTPS only; no credentials, no odd ports. Address checks happen per connection. */
    fun urlAllowed(url: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        return u.scheme.equals("https", true) && !u.host.isNullOrEmpty() && u.userInfo == null && (u.port == -1 || u.port == 443)
    }

    /** A resolved address that is public (not loopback, private, link-local, CGNAT, ULA, multicast). */
    fun publicAddress(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isAnyLocalAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
        val b = a.address
        return when (a) {
            is Inet4Address -> !((b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64) && (b[0].toInt() and 0xFF) != 0
            is Inet6Address -> (b[0].toInt() and 0xFE) != 0xFC // fc00::/7
            else -> false
        }
    }
}

/**
 * The phone's speaker (CAP-51, CAP-53, SPK-01..03): one player, a queue with the City-tour
 * replace rule, a volume ceiling, audio focus, Stop that clears everything. Text is spoken by
 * synthesising it to a file with the phone's TextToSpeech and playing that, so speech and audio
 * share one queue and one set of rules.
 */
class SpeakerQueue(private val app: BridgeApp) {
    data class Item(val id: String, val agentId: String, val agent: String, val file: File, val caption: String?,
                    val tourId: String?, val urgent: Boolean, val queuedAt: Long = System.currentTimeMillis())

    private val queue = ArrayDeque<Item>()
    private var player: MediaPlayer? = null
    @Volatile var current: Item? = null; private set
    private val am = app.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var restoreVolume: Int? = null
    private var setVolume: Int? = null
    private val dir = File(app.cacheDir, "speaker").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun changed() = synchronized(listeners) { listeners.toList() }.forEach { runCatching { it() } }

    fun newFile(ext: String) = File(dir, "${UUID.randomUUID()}.$ext")

    @Synchronized fun queued(): List<Item> = queue.toList()

    /** Adds [item]. [replace] is SpeakerRules.REPLACE. Returns the item's position (0 = playing now). */
    @Synchronized
    fun enqueue(item: Item, replace: String): Int {
        val mine = queue.count { it.agentId == item.agentId }
        if (replace == "queue" && mine >= SpeakerRules.MAX_QUEUE_PER_AGENT) {
            item.file.delete(); throw ErrorCatalog.error("rate_limited", "At most ${SpeakerRules.MAX_QUEUE_PER_AGENT} sounds waiting per agent.")
        }
        if (replace != "queue") {
            // City tour (D30): a newer narration replaces anything still waiting from the same agent / tour.
            val drop = queue.filter { it.agentId == item.agentId && (item.tourId == null || it.tourId == item.tourId) }
            drop.forEach { it.file.delete() }; queue.removeAll(drop.toSet())
            if (replace == "now" && current?.agentId == item.agentId) stopCurrent()
        }
        if (item.urgent) queue.addFirst(item) else queue.addLast(item)
        if (current == null) playNext()
        changed()
        return if (current === item) 0 else queue.indexOf(item) + 1
    }

    /** Stop (SPK-01): stops playback and clears every queue, or only [agentId]'s. */
    @Synchronized
    fun stop(agentId: String? = null) {
        val drop = queue.filter { agentId == null || it.agentId == agentId }
        drop.forEach { it.file.delete() }; queue.removeAll(drop.toSet())
        if (agentId == null || current?.agentId == agentId) { stopCurrent(); playNext() }
        changed()
    }

    @Synchronized
    fun skip() { stopCurrent(); playNext(); changed() }

    private fun stopCurrent() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        current?.file?.delete()
        current = null
    }

    private fun playNext() {
        val next = queue.removeFirstOrNull()
        if (next == null) { release(); return }
        if (quiet()) { next.file.delete(); playNext(); return }
        if (!requestFocus()) { next.file.delete(); release(); return }
        applyCeiling()
        current = next
        app.log.system("audit", "interaction", "${next.agent} played audio" + (next.tourId?.let { " (tour)" } ?: ""))
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(next.file.absolutePath)
            mp.setOnCompletionListener { synchronized(this) { if (player === mp) { stopCurrent(); playNext(); changed() } } }
            mp.setOnErrorListener { _, _, _ -> synchronized(this) { if (player === mp) { stopCurrent(); playNext(); changed() } }; true }
            mp.prepare()
            if (mp.duration > SpeakerRules.MAX_CLIP_MS) throw IllegalStateException("longer than 10 minutes")
            mp.start()
        } catch (e: Exception) {
            app.log.system("diagnostic", "service", "Couldn't play ${next.agent}'s audio: ${e.javaClass.simpleName}", level = "warn")
            stopCurrent(); playNext()
        }
    }

    /** Silent / vibrate / DND / a call: don't play (SPK-01, SPK-03). */
    fun quiet(): Boolean {
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return true
        if (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION) return true
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        return nm.currentInterruptionFilter > android.app.NotificationManager.INTERRUPTION_FILTER_ALL
    }

    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    private fun requestFocus(): Boolean {
        if (focus != null) return true
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS) stop() }.build()
        return if (am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { focus = req; true } else false
    }

    /** SPK-01: never louder than Rahul's ceiling; put back afterwards unless he changed it meanwhile. */
    private fun applyCeiling() {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val ceiling = max * app.settings.speakerCeiling / 100
        val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (now > ceiling) {
            if (restoreVolume == null) restoreVolume = now
            am.setStreamVolume(AudioManager.STREAM_MUSIC, ceiling, 0); setVolume = ceiling
        }
    }

    private fun release() {
        focus?.let { am.abandonAudioFocusRequest(it) }; focus = null
        val r = restoreVolume
        if (r != null && am.getStreamVolume(AudioManager.STREAM_MUSIC) == setVolume) am.setStreamVolume(AudioManager.STREAM_MUSIC, r, 0)
        restoreVolume = null; setVolume = null
    }
}

/** Text to an audio file with the phone's TTS (CAP-53). Offline voices only where the engine says so. */
class TtsFiles(private val ctx: Context) {
    private var tts: TextToSpeech? = null
    private val ready = CompletableDeferred<Boolean>()

    private fun ensure() {
        if (tts == null) tts = TextToSpeech(ctx) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
    }

    suspend fun synth(text: String, out: File, language: String?, rate: Float, pitch: Float): Boolean = withContext(Dispatchers.Main) {
        ensure()
        if (withTimeoutOrNull(5000) { ready.await() } != true) return@withContext false
        val t = tts!!
        language?.let { t.language = java.util.Locale.forLanguageTag(it) }
        t.setSpeechRate(rate); t.setPitch(pitch)
        // Prefer a voice that doesn't need the network (Codex review: TTS may use network voices).
        t.voices?.filter { !it.isNetworkConnectionRequired && it.locale == t.voice?.locale }?.minByOrNull { it.latency }?.let { t.voice = it }
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(u: String?) {}
            override fun onDone(u: String?) { if (u == id) done.complete(true) }
            @Deprecated("old API") override fun onError(u: String?) { if (u == id) done.complete(false) }
            override fun onError(u: String?, code: Int) { if (u == id) done.complete(false) }
        })
        if (t.synthesizeToFile(text, null, out, id) != TextToSpeech.SUCCESS) return@withContext false
        withTimeoutOrNull(30_000) { done.await() } == true && out.length() > 0
    }
}

/** CAP-51 / CAP-53 (minimal, for City tour): play audio and speak text. Switch "Speaker". */
class SpeakerModule(app: BridgeApp) : Capability(app) {
    private val limiter = RateLimiter(windowMs = 3_600_000L)
    private val tts by lazy { TtsFiles(app) }
    override val id = "speaker"
    override val titleRes = R.string.cap_speaker
    override val descriptionRes = R.string.cap_speaker_desc
    override val group = "talk"
    override val switchKey = "speaker"
    override val endpoints = listOf(Endpoints.SPEAKER_PLAY, Endpoints.SPEAKER_SAY, Endpoints.SPEAKER_STATUS, Endpoints.SPEAKER_STOP)
    override fun blockedReason(): String? = if (!app.captureNotificationsOn()) "notifications_off" else null
    override fun details() = JSONObject().put("volume_ceiling_percent", app.settings.speakerCeiling)
        .put("queue_per_agent", SpeakerRules.MAX_QUEUE_PER_AGENT).put("plays_per_hour", SpeakerRules.PLAYS_PER_HOUR)

    private fun net.finleg.bridget.api.Req.common(): Triple<String, String?, Boolean> {
        val replace = (arg("replace") as? String) ?: "after_current"
        if (replace !in SpeakerRules.REPLACE) throw ErrorCatalog.error("invalid_parameter", "replace must be after_current, now or queue.")
        val tourId = (arg("tour_id") as? String)?.takeIf { it.isNotBlank() }
        if (tourId != null && app.tours.current()?.let { it.id == tourId && it.agentId == agent!!.id } != true)
            throw ErrorCatalog.error("not_found", "No running tour $tourId of yours.")
        val urgent = (arg("urgent") as? Boolean) ?: (arg("urgent") == "true")
        if (!limiter.tryAcquire(agent!!.id, SpeakerRules.PLAYS_PER_HOUR))
            throw ErrorCatalog.error("rate_limited", "At most ${SpeakerRules.PLAYS_PER_HOUR} plays an hour.", mapOf("retry_after_s" to limiter.retryAfterS(agent!!.id)))
        return Triple(replace, tourId, urgent)
    }

    private fun reply(item: SpeakerQueue.Item, pos: Int) = Reply.Json(JSONObject().put("play_id", item.id)
        .put("position", pos).put("status", if (pos == 0) "playing" else "queued"))

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(Endpoints.SPEAKER_PLAY) {
            requireUsable()
            val (replace, tourId, urgent) = common()
            val a = agent!!
            val ctype = call.request.headers["Content-Type"] ?: ""
            val file: java.io.File
            if (ctype.startsWith("audio/")) {
                val ext = ctype.substringAfter('/').substringBefore(';').take(8).ifEmpty { "bin" }
                file = app.speaker.newFile(ext)
                val bytes = rawBody ?: throw ErrorCatalog.error("invalid_parameter", "Send the audio as the body.")
                file.writeBytes(bytes)
            } else {
                val url = body.optString("url", "")
                if (!SpeakerRules.urlAllowed(url)) throw ErrorCatalog.error("invalid_parameter", "url must be a public https:// address (or send audio/* bytes as the body).")
                file = app.speaker.newFile("audio")
                fetchPublic(url, file)
                params.put("url", URI(url).host)
            }
            val item = SpeakerQueue.Item(UUID.randomUUID().toString().take(12), a.id, a.name, file,
                (arg("text") as? String)?.take(500), tourId, urgent)
            val pos = app.speaker.enqueue(item, replace)
            tourId?.let { app.tours.narrated(it, item.caption) }
            message = "${a.name} sent audio to play"
            reply(item, pos)
        }
        endpoint(Endpoints.SPEAKER_SAY) {
            requireUsable()
            val (replace, tourId, urgent) = common()
            val a = agent!!
            val text = body.optString("text", "").trim()
            if (text.isEmpty() || text.length > SpeakerRules.MAX_SAY_CHARS) throw ErrorCatalog.error("invalid_parameter", "text must be 1 to ${SpeakerRules.MAX_SAY_CHARS} characters.")
            val rate = body.optDouble("rate", 1.0).toFloat().takeIf { it in 0.5f..2f } ?: throw ErrorCatalog.error("invalid_parameter", "rate must be 0.5 to 2.")
            val pitch = body.optDouble("pitch", 1.0).toFloat().takeIf { it in 0.5f..2f } ?: throw ErrorCatalog.error("invalid_parameter", "pitch must be 0.5 to 2.")
            val file = app.speaker.newFile("wav")
            if (!tts.synth(text, file, body.optString("language", "").ifEmpty { null }, rate, pitch))
                throw ErrorCatalog.error("internal_error", "The phone's text-to-speech didn't produce audio.")
            val item = SpeakerQueue.Item(UUID.randomUUID().toString().take(12), a.id, a.name, file, text.take(500), tourId, urgent)
            val pos = app.speaker.enqueue(item, replace)
            tourId?.let { app.tours.narrated(it, text) }
            message = "${a.name} sent speech to read aloud"
            reply(item, pos)
        }
        endpoint(Endpoints.SPEAKER_STATUS) {
            val cur = app.speaker.current
            Reply.Json(JSONObject().put("playing", cur?.let { JSONObject().put("play_id", it.id).put("agent", it.agent).put("text", it.caption ?: JSONObject.NULL) } ?: JSONObject.NULL)
                .put("queued", JSONArray(app.speaker.queued().filter { it.agentId == agent!!.id }.map { JSONObject().put("play_id", it.id) }))
                .put("quiet", app.speaker.quiet()))
        }
        endpoint(Endpoints.SPEAKER_STOP) {
            app.speaker.stop(agent!!.id)
            Reply.Json(JSONObject().put("stopped", true))
        }
    }

    /** SPK-02: HTTPS to a public address only, every redirect re-checked (max 3), 25 MB, timeouts. */
    private suspend fun fetchPublic(start: String, out: File) = withContext(Dispatchers.IO) {
        var url = start
        repeat(4) { hop ->
            val u = URI(url)
            if (!SpeakerRules.urlAllowed(url)) throw ErrorCatalog.error("invalid_parameter", "Redirected to a non-https address.")
            val addrs = runCatching { InetAddress.getAllByName(u.host).toList() }.getOrElse { throw ErrorCatalog.error("invalid_parameter", "Can't resolve ${u.host}.") }
            if (addrs.isEmpty() || addrs.any { !SpeakerRules.publicAddress(it) }) throw ErrorCatalog.error("invalid_parameter", "${u.host} isn't a public address.")
            val c = (u.toURL().openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 15_000; readTimeout = 60_000
                setRequestProperty("User-Agent", "Bridget/${app.appVersion()} (Android)")
            }
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    if (hop == 3) throw ErrorCatalog.error("invalid_parameter", "Too many redirects.")
                    url = u.resolve(c.getHeaderField("Location") ?: throw ErrorCatalog.error("invalid_parameter", "Redirect without a Location.")).toString()
                    return@repeat
                }
                if (code != HttpURLConnection.HTTP_OK) throw ErrorCatalog.error("invalid_parameter", "The audio URL answered HTTP $code.")
                if (c.contentLengthLong > SpeakerRules.MAX_BYTES) throw ErrorCatalog.error("payload_too_large", "Audio is limited to 25 MB.")
                c.inputStream.use { inp -> out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024); var total = 0L
                    while (true) { val n = inp.read(buf); if (n < 0) break; total += n
                        if (total > SpeakerRules.MAX_BYTES) { out.delete(); throw ErrorCatalog.error("payload_too_large", "Audio is limited to 25 MB.") }
                        o.write(buf, 0, n) }
                } }
                return@withContext
            } finally { c.disconnect() }
        }
    }
}
