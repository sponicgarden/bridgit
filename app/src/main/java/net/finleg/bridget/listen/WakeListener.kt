package net.finleg.bridget.listen

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.VibrationEffect
import net.finleg.bridget.core.Compat
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

    /** "Listen for any sounds", if the owner set it up for an agent that still exists. */
    fun soundConfig(): SoundConfig? = SoundConfig.fromJson(app.settings.soundsConfig)?.takeIf { app.agents.get(it.agentId) != null }
    fun saveSoundConfig(c: SoundConfig?) { app.settings.soundsConfig = c?.toJson()?.toString() ?: "" }

    private fun micMissing(): String? = when {
        !app.granted(Manifest.permission.RECORD_AUDIO) -> "permission:RECORD_AUDIO"
        !app.captureNotificationsOn() -> "notifications_off"
        !app.listener.value.running -> "listener_off"
        !app.listener.value.micReady -> "open_app_once"
        else -> null
    }

    /** Null when wake words can run; otherwise why not (also the module's blocked reason, minus the switch). */
    fun missing(): String? = micMissing() ?: when {
        !model.ready -> "model_missing"
        activeWords().isEmpty() -> "no_wake_words"
        else -> null
    }

    /** The same for "listen for any sounds": no speech model needed, only an agent. */
    fun soundsMissing(): String? = micMissing() ?: if (soundConfig() == null) "no_agent" else null

    /** What the microphone loop is doing: wake words (empty when off) and sounds (null when off). */
    data class Run(val words: List<WakeWord>, val sound: SoundConfig?)

    @Synchronized fun sync() {
        val wakeOn = app.settings.isOn(SWITCH) && missing() == null
        val soundOn = app.settings.isOn(SOUND_SWITCH) && soundsMissing() == null
        if (!wakeOn && !soundOn) {
            val reason = when {
                app.settings.isOn(SWITCH) -> missing() ?: "error"
                app.settings.isOn(SOUND_SWITCH) -> soundsMissing() ?: "error"
                else -> "switch_off"
            }
            job?.cancel(); job = null; running = null
            if (_state.value != State.Off(reason)) setState(State.Off(reason))
            return
        }
        val run = Run(if (wakeOn) activeWords() else emptyList(), if (soundOn) soundConfig() else null)
        if (job?.isActive == true && running == run) return
        job?.cancel()
        running = run
        job = scope.launch { runCatching { loop(run) }.onFailure { e ->
            if (e is CancellationException) throw e
            app.log.system("diagnostic", "wakewords", "Listening stopped: ${e.javaClass.simpleName}: ${e.message}", level = "error")
            setState(State.Off("error"))
        } }
    }
    @Volatile var running: Run? = null; private set

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

    private suspend fun loop(run: Run) {
        val m = if (run.words.isEmpty()) null else withContext(Dispatchers.IO) { model.load() }
        while (currentCoroutineContext().isActive) {
            val why = pausedReason()
            if (why != null) { if (_state.value != State.Paused(why)) setState(State.Paused(why)); delay(1_000); continue }
            if (!listenUntilPaused(m, run)) delay(5_000)
        }
    }

    /** Spots wake words and sounds until something wants the microphone back. False when the microphone couldn't be opened. */
    @SuppressLint("MissingPermission")
    private suspend fun listenUntilPaused(m: Model?, run: Run): Boolean {
        val words = run.words
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 4)) }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            app.log.system("diagnostic", "wakewords", "Couldn't open the microphone for wake words", level = "warn")
            return false
        }
        val spotter = m?.let { Recognizer(it, RATE.toFloat(), WakeRules.grammar(words)).apply { setWords(true); setPartialWords(true) } }
        val sounds = run.sound?.let { WakeRules.SoundDetector(it.sensitivity) }
        var soundPcm: ByteArrayOutputStream? = null
        var soundAt = 0L
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
                val level = WakeRules.rms(buf, n)
                if (sounds != null) when (sounds.feed(level)) {
                    // The clip starts 500 ms back so the start of the sound is in it.
                    WakeRules.SoundDetector.Ev.START -> { soundAt = System.currentTimeMillis() - 200
                        soundPcm = ByteArrayOutputStream().apply { write(ring.last(5 * FRAME)) } }
                    WakeRules.SoundDetector.Ev.END -> { val p = soundPcm?.toByteArray() ?: ByteArray(0); soundPcm = null
                        val (dur, peak, mean) = Triple(sounds.durationMs / 1000.0, sounds.peakRms, sounds.meanRms)
                        scope.launch { deliverSound(run.sound, p, dur, peak, mean, soundAt) } }
                    null -> soundPcm?.write(buf, 0, n)
                }
                // Silence never reaches Vosk; when sound starts, it gets up to 300 ms of what came just before.
                if (spotter == null || !gate.feed(level)) {
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
                    soundPcm = null
                    follow(rec, m!!, hit.wake, tail)
                    setState(State.Listening)
                }
                // The mic lock is checked every frame so a recording agent isn't kept waiting; the rest once a second.
                if (app.registry.micLock.isLocked || (++frames % 10 == 0 && pausedReason() != null)) return true
            }
            return true
        } finally {
            runCatching { rec.stop() }; rec.release(); spotter?.close(); ring.clear()
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

    private val soundTimes = ArrayDeque<Long>()

    /** "Listen for any sounds": a `sound.heard` event, at most 60 an hour on top of the detector's 30 s gap. */
    private fun deliverSound(cfg: SoundConfig, pcm: ByteArray, seconds: Double, peak: Double, mean: Double, heardAt: Long) {
        val agent = app.agents.get(cfg.agentId) ?: return
        synchronized(soundTimes) {
            while (soundTimes.isNotEmpty() && soundTimes.first() < heardAt - 3_600_000L) soundTimes.removeFirst()
            if (soundTimes.size >= 60) return
            soundTimes.addLast(heardAt)
        }
        val clipId = if (cfg.sendClip && pcm.isNotEmpty()) newId().also { id ->
            synchronized(clips) { pruneClips(); clips[id] = Clip(agent.id, WakeRules.wav(pcm), heardAt, pcm.size / (RATE * 2.0)) }
        } else null
        val e = WakeRules.soundEvent(isoAt(heardAt), seconds, peak, mean, cfg.sensitivity, clipId)
        app.interactions.event(agent.id, "sound.heard", e)
        app.log.system("audit", "sounds", "Heard a sound for ${agent.name}: ${e.getDouble("duration_seconds")} s, peak ${e.getDouble("peak_dbfs")} dBFS",
            data = JSONObject().put("agent_id", agent.id).put("clip", clipId != null))
        lastSound = LastSound(agent.name, e.getDouble("peak_dbfs"), seconds, heardAt)
    }
    data class LastSound(val agentName: String, val peakDbfs: Double, val seconds: Double, val at: Long)
    @Volatile var lastSound: LastSound? = null; private set

    /** The clip [id] if it belongs to [agentId] and is under 10 minutes old. */
    fun clip(id: String, agentId: String): Clip? = synchronized(clips) { pruneClips(); clips[id]?.takeIf { it.agentId == agentId } }
    private fun pruneClips() {
        val cutoff = System.currentTimeMillis() - WakeRules.CLIP_KEEP_MS
        clips.entries.removeAll { it.value.at < cutoff }
        while (clips.size > 5) clips.remove(clips.keys.first())
    }

    /** JSON for GET /v1/listen: the state, and only this agent's wake words. */
    fun status(agentId: String): JSONObject {
        val s = _state.value.let { st ->
            // Sounds alone keep the mic open; for wake words that still counts as off.
            if (st !is State.Off && running?.words.isNullOrEmpty()) State.Off(if (app.settings.isOn(SWITCH)) missing() ?: "error" else "switch_off") else st }
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

    /** JSON for GET /v1/sounds: whether the phone listens for sounds for this agent. */
    fun soundStatus(agentId: String): JSONObject {
        val cfg = soundConfig()
        val mine = cfg?.agentId == agentId
        val st = _state.value
        val on = running?.sound != null
        return JSONObject().put("enabled", app.settings.isOn(SOUND_SWITCH)).put("for_you", mine)
            .put("state", when { !on -> "off"; st is State.Paused -> "paused"; else -> "listening" })
            .put("reason", when { !on -> (if (app.settings.isOn(SOUND_SWITCH)) soundsMissing() ?: "error" else "switch_off"); st is State.Paused -> st.reason; else -> JSONObject.NULL })
            .put("sensitivity", if (mine) cfg!!.sensitivity else JSONObject.NULL).put("send_clip", mine && cfg!!.sendClip)
            .put("cooldown_seconds", WakeRules.SOUND_COOLDOWN_MS / 1000).put("max_seconds", WakeRules.SOUND_MAX_MS / 1000)
    }

    /** Names of the agents that get wake words or sounds, for the notification. */
    fun agentNames(): String = ((running?.words.orEmpty().map { it.agentId }) + listOfNotNull(running?.sound?.agentId))
        .mapNotNull { app.agents.get(it)?.name }.distinct().joinToString(", ")

    private fun tick() = runCatching {
        Compat.vibrator(app)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
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
        const val SOUND_SWITCH = "sounds"
        const val RATE = WakeRules.SAMPLE_RATE
        const val FRAME_MS = 100L
        const val FRAME = (RATE / 10) * 2
        const val PREROLL_FRAMES = 3
    }
}
