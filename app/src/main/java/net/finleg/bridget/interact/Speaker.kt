package net.finleg.bridget.interact

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Reads interactions aloud (CAP-31 speak, and Play aloud on the card). One TextToSpeech engine,
 * created on first use and released after 60 s without speech. Everything runs on the main looper.
 */
object Speaker {
    private const val IDLE_MS = 60_000L
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private val queue = ArrayList<String>()
    private var seq = 0
    private val release = Runnable { if (tts?.isSpeaking == true) scheduleRelease() else shutdown() }

    /** Speaks "<Agent> says: <title>. <text>" after anything already queued. Safe from any thread. */
    fun speak(ctx: Context, agent: String, title: String, text: String?) {
        val line = NotifRules.utterance(agent, title, text)
        val app = ctx.applicationContext
        main.post {
            if (tts == null) {
                ready = false
                tts = TextToSpeech(app) { status -> main.post { onInit(status) } }
            }
            if (ready) say(line) else queue += line
            scheduleRelease()
        }
    }

    /** Stops speaking and drops anything queued. */
    fun stop() = main.post { queue.clear(); tts?.stop() }

    private fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) { queue.clear(); shutdown(); return }
        ready = true
        runCatching { t.language = Locale.getDefault() }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { scheduleRelease() } }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { main.post { scheduleRelease() } }
        })
        queue.forEach { say(it) }
        queue.clear()
        scheduleRelease()
    }

    private fun say(line: String) { tts?.speak(line, TextToSpeech.QUEUE_ADD, null, "bridget-${++seq}") }

    private fun scheduleRelease() { main.removeCallbacks(release); main.postDelayed(release, IDLE_MS) }

    private fun shutdown() {
        main.removeCallbacks(release)
        runCatching { tts?.shutdown() }
        tts = null; ready = false
    }
}
