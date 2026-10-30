package net.finleg.bridget.listen

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.VibrationEffect
import android.os.VibratorManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.finleg.bridget.BridgeApp
import net.finleg.bridget.BridgeService
import net.finleg.bridget.capability.granted
import net.finleg.bridget.capability.isoAt
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom

/**
 * Always-on wake words (8.23, CAP-46/47/48). While the switch is on and the listener has the microphone,
 * a grammar-limited Vosk recogniser spots the owner's wake words in RAM. After a hit, what follows is
 * recorded until a pause (max 60 s), transcribed on the phone, and sent to the mapped agent as `listen.heard`.
 * Audio from before the wake word is never kept: the spotter's 2 s buffer is overwritten as it goes.
 */
class WakeListener(private val app: BridgeApp) {
    sealed class State {
        /** Not listening; [reason] is the first thing missing (switch, model, words, microphone). */
        data class Off(val reason: String) : State()
        object Listening : State()
        data class Hearing(val phrase: String) : State()
        /** Microphone released for a while: "muted", "call" or "mic_in_use". */
        data class Paused(val reason: String) : State()
    }

    data class Clip(val agentId: String, val wav: ByteArray, val at: Long, val seconds: Double)
    data class LastHit(val phrase: String, val agentName: String, val transcript: String, val at: Long)

    val model = WakeModel(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<State>(State.Off("switch_off"))
    val state: StateFlow<State> get() = _state
    private var job: Job? = null
    @Volatile var mutedUntil = 0L; private set
    @Volatile var lastHit: LastHit? = null; private set
    private val clips = LinkedHashMap<String, Clip>()

    fun words(): List<WakeWord> = WakeRules.fromJson(app.settings.wakeWords)
    /** Wake words whose agent still exists. */
    fun activeWords(): List<WakeWord> = words().filter { app.agents.get(it.agentId) != null }
    fun saveWords(words: List<WakeWord>) { app.settings.wakeWords = WakeRules.toJson(words) }

    fun start() {
        app.settings.addListener { sync() }
        scope.launch { app.listener.collect { sync() } }
        scope.launch { model.state.collect { sync() } }
    }

    /** Null when the listener can run; otherwise why not (also the module's blocked reason, minus the switch). */
    fun missing(): String? = when {
        !app.granted(Manifest.permission.RECORD_AUDIO) -> "permission:RECORD_AUDIO"
        !app.captureNotificationsOn() -> "notifications_off"
        !app.listener.value.running -> "listener_off"
        !app.listener.value.micReady -> "open_app_once"
        !model.ready -> "model_missing"
        activeWords().isEmpty() -> "no_wake_words"
        else -> null
    }

    @Synchronized fun sync() {
        val reason = if (!app.settings.isOn(SWITCH)) "switch_off" else missing()
        val words = activeWords()
        if (reason != null) {
            job?.cancel(); job = null
            if (_state.value != State.Off(reason)) setState(State.Off(reason))
            return
        }
        if (job?.isActive == true && running == words) return
        job?.cancel()
        running = words
        job = scope.launch { runCatching { loop(words) }.onFailure { e ->
            if (e is CancellationException) throw e
            app.log.system("diagnostic", "wakewords", "Wake words stopped: ${e.javaClass.simpleName}: ${e.message}", level = "error")
            setState(State.Off("error"))
        } }
    }
    private var running: List<WakeWord>? = null

    fun mute(ms: Long = WakeRules.MUTE_MS) {
        mutedUntil = System.currentTimeMillis() + ms
        app.log.system("audit", "wakewords", "You muted wake words for ${ms / 60_000} minutes")
    }
    fun unmute() { mutedUntil = 0 }

    /**
     * Called by Bridgit's own recorders after they take the mic lock: the listener lets go of the
     * microphone within a frame (or after the follow-up it's hearing is cut short).
     */
    suspend fun awaitMicReleased(timeoutMs: Long = 3_000) {
        withTimeoutOrNull(timeoutMs) {
            while (_state.value is State.Listening || _state.value is State.Hearing) delay(50)
        }
    }

    private fun pausedReason(): String? = when {
        System.currentTimeMillis() < mutedUntil -> "muted"
        app.speakerReady() && app.speaker.inCall() -> "call"
        app.registry.micLock.isLocked -> "mic_in_use"
        else -> null
    }

    private fun setState(s: State) {
        val old = _state.value
        _state.value = s
        if (old::class != s::class || s is State.Hearing) {
            if (s is State.Listening && old !is State.Hearing && old !is State.Paused) app.log.system("audit", "wakewords", "Listening for wake words started")
            if (s is State.Off && (old is State.Listening || old is State.Paused)) app.log.system("audit", "wakewords", "Listening for wake words stopped (${s.reason})")
        }
        BridgeService.running?.refreshNotification()
    }

    private suspend fun loop(words: List<WakeWord>) {
        val m = withContext(Dispatchers.IO) { model.load() }
        while (currentCoroutineContext().isActive) {
            val why = pausedReason()
            if (why != null) { if (_state.value != State.Paused(why)) setState(State.Paused(why)); delay(1_000); continue }
            if (!listenUntilPaused(m, words)) delay(5_000)
        }
    }

    /** Spots wake words until something wants the microphone back. False when the microphone couldn't be opened. */
    @SuppressLint("MissingPermission")
    private suspend fun listenUntilPaused(m: Model, words: List<WakeWord>): Boolean {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 4)) }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            app.log.system("diagnostic", "wakewords", "Couldn't open the microphone for wake words", level = "warn")
            return false
        }
        val spotter = Recognizer(m, RATE.toFloat(), WakeRules.grammar(words)).apply { setWords(true); setPartialWords(true) }
        val ring = Ring(RATE * 2 * 2)
        var fed = 0L
        val gate = WakeRules.SoundGate()
        var quiet = 0
        try {
            rec.startRecording()
            setState(State.Listening)
            val buf = ByteArray(FRAME)
            var frames = 0
            while (currentCoroutineContext().isActive) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) return false
                if (n == 0) continue
                ring.write(buf, n)
                // Silence never reaches Vosk; when sound starts, it gets up to 300 ms of what came just before.
                if (!gate.feed(WakeRules.rms(buf, n))) {
                    quiet++
                    if (app.registry.micLock.isLocked || (++frames % 10 == 0 && pausedReason() != null)) return true
                    continue
                }
                val chunk = if (quiet > 0) ring.last(minOf(quiet, PREROLL_FRAMES) * FRAME + n) else buf.copyOf(n)
                quiet = 0
                val full = spotter.acceptWaveForm(chunk, chunk.size)
                fed += chunk.size
                val hit = WakeRules.match(if (full) spotter.result else spotter.partialResult, words)
                if (hit != null) {
                    // Keep only what came after the wake word ended.
                    val endByte = hit.endS?.let { (it * RATE * 2).toLong() and 1L.inv() } ?: fed
                    val tail = ring.last((fed - endByte).coerceIn(0, ring.size.toLong()).toInt())
                    ring.clear(); spotter.reset(); fed = 0
                    follow(rec, m, hit.wake, tail)
                    setState(State.Listening)
                }
                // The mic lock is checked every frame so a recording agent isn't kept waiting; the rest once a second.
                if (app.registry.micLock.isLocked || (++frames % 10 == 0 && pausedReason() != null)) return true
            }
            return true
        } finally {
            runCatching { rec.stop() }; rec.release(); spotter.close(); ring.clear()
        }
    }

    /** Records what follows the wake word until a pause, then transcribes and delivers it off this thread. */
    private suspend fun follow(rec: AudioRecord, m: Model, wake: WakeWord, tail: ByteArray) {
        val heardAt = System.currentTimeMillis()
        setState(State.Hearing(WakeRules.normalize(wake.phrase)))
        tick()
        val pcm = ByteArrayOutputStream()
        val det = WakeRules.PauseDetector(FRAME_MS)
        val free = Recognizer(m, RATE.toFloat())
        var end: WakeRules.PauseDetector.End? = null
        try {
            var i = 0
            while (i < tail.size && end == null) {
                val n = minOf(FRAME, tail.size - i)
                val chunk = tail.copyOfRange(i, i + n)
                pcm.write(chunk); free.acceptWaveForm(chunk, n); end = det.feed(WakeRules.rms(chunk, n)); i += n
            }
            val buf = ByteArray(FRAME)
            while (end == null && currentCoroutineContext().isActive) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                pcm.write(buf, 0, n); free.acceptWaveForm(buf, n)
                end = det.feed(WakeRules.rms(buf, n))
                if (app.registry.micLock.isLocked || pausedReason() == "call") break
            }
            val voskText = runCatching { JSONObject(free.finalResult).optString("text") }.getOrDefault("")
            val bytes = pcm.toByteArray()
            val endedBy = end?.let { WakeRules.endedBy(it) } ?: "interrupted"
            scope.launch { deliver(wake, bytes, voskText, det.heardSpeech, endedBy, heardAt) }
        } finally { free.close() }
    }

    private suspend fun deliver(wake: WakeWord, pcm: ByteArray, voskText: String, spoke: Boolean, endedBy: String, heardAt: Long) {
        val agent = app.agents.get(wake.agentId) ?: return
        var recogniser = "vosk"
        var transcript = voskText
        if (spoke && net.finleg.bridget.planned.Transcriber.available(app)) {
            val f = File(app.cacheDir, "wake-${System.nanoTime()}.pcm")
            try {
                withContext(Dispatchers.IO) { f.writeBytes(pcm) }
                runCatching { net.finleg.bridget.planned.Transcriber.recognizePcm(app, f, RATE, 1, null) }
                    .onFailure { app.log.system("diagnostic", "wakewords", "On-device recogniser gave nothing, using Vosk's text: ${it.message}", level = "warn") }
                    .getOrNull()?.takeIf { it.isNotBlank() }?.let { transcript = it; recogniser = "android_on_device" }
            } finally { f.delete() }
        }
        val seconds = pcm.size / (RATE * 2.0)
        val clipId = if (wake.sendClip && pcm.isNotEmpty()) newId().also { id ->
            synchronized(clips) { pruneClips(); clips[id] = Clip(agent.id, WakeRules.wav(pcm), heardAt, seconds) }
        } else null
        val phrase = WakeRules.normalize(wake.phrase)
        app.interactions.event(agent.id, "listen.heard", WakeRules.heardEvent(wake, transcript, isoAt(heardAt), endedBy, seconds, recogniser, clipId))
        app.log.system("audit", "wakewords", "Heard \"$phrase\" for ${agent.name}: \"${WakeRules.forLog(transcript)}\"",
            data = JSONObject().put("agent_id", agent.id).put("ended_by", endedBy).put("clip", clipId != null))
        lastHit = LastHit(phrase, agent.name, WakeRules.forLog(transcript), heardAt)
        BridgeService.running?.refreshNotification()
    }

    /** The clip [id] if it belongs to [agentId] and is under 10 minutes old. */
    fun clip(id: String, agentId: String): Clip? = synchronized(clips) { pruneClips(); clips[id]?.takeIf { it.agentId == agentId } }
    private fun pruneClips() {
        val cutoff = System.currentTimeMillis() - WakeRules.CLIP_KEEP_MS
        clips.entries.removeAll { it.value.at < cutoff }
        while (clips.size > 5) clips.remove(clips.keys.first())
    }

    /** JSON for GET /v1/listen: the state, and only this agent's wake words. */
    fun status(agentId: String): JSONObject {
        val s = _state.value
        return JSONObject().put("enabled", app.settings.isOn(SWITCH))
            .put("state", when (s) { is State.Off -> "off"; State.Listening -> "listening"; is State.Hearing -> "hearing"; is State.Paused -> "paused" })
            .put("reason", when (s) { is State.Off -> s.reason; is State.Paused -> s.reason; else -> JSONObject.NULL })
            .put("muted_until", if (mutedUntil > System.currentTimeMillis()) isoAt(mutedUntil) else JSONObject.NULL)
            .put("model", when (val ms = model.state.value) { WakeModel.State.Ready -> "ready"; WakeModel.State.Absent -> "not_downloaded"
                is WakeModel.State.Downloading -> "downloading"; is WakeModel.State.Failed -> "failed" })
            .put("model_name", WakeModel.NAME)
            .put("max_seconds", WakeRules.MAX_SECONDS)
            .put("wake_words", JSONArray(activeWords().filter { it.agentId == agentId }.map {
                JSONObject().put("phrase", WakeRules.normalize(it.phrase)).put("send_clip", it.sendClip) }))
    }

    /** Names of the agents with wake words, for the notification. */
    fun agentNames(): String = activeWords().mapNotNull { app.agents.get(it.agentId)?.name }.distinct().joinToString(", ")

    private fun tick() = runCatching {
        app.getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    }

    private fun newId(): String { val b = ByteArray(9); SecureRandom().nextBytes(b); return "c" + b.joinToString("") { "%02x".format(it) } }

    /** The spotter's last few seconds, RAM only. */
    private class Ring(cap: Int) {
        private val data = ByteArray(cap)
        private var pos = 0
        var size = 0; private set
        fun write(b: ByteArray, n: Int) { for (i in 0 until n) { data[pos] = b[i]; pos = (pos + 1) % data.size }; size = minOf(data.size, size + n) }
        fun last(n: Int): ByteArray { val k = minOf(n, size); return ByteArray(k) { data[(pos - k + it + data.size) % data.size] } }
        fun clear() { data.fill(0); pos = 0; size = 0 }
    }

    companion object {
        const val SWITCH = "wakewords"
        const val RATE = WakeRules.SAMPLE_RATE
        const val FRAME_MS = 100L
        const val FRAME = (RATE / 10) * 2
        const val PREROLL_FRAMES = 3
    }
}
