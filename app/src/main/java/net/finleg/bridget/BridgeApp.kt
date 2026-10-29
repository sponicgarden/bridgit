package net.finleg.bridget

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class BridgeApp : Application() {
    lateinit var policy: Policy
    lateinit var audit: AuditLog
    val pairing = Pairing()
    val platform by lazy { net.finleg.bridget.platform.AgentPlatform(this) }

    override fun onCreate() {
        super.onCreate()
        policy = Policy(this)
        audit = AuditLog(this)
        policy.credentialsChanged = { id -> platform.invalidate(id) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "BRIDGIT Device Manager", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the listener runs and during every capture"
            }
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CAPTURE_CHANNEL, "Agent captures and control", NotificationManager.IMPORTANCE_DEFAULT)
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(INTERACTION_CHANNEL, "Agent questions and notices", NotificationManager.IMPORTANCE_DEFAULT)
        )
        platform
    }

    companion object {
        const val CHANNEL = "bridge"
        const val CAPTURE_CHANNEL = "agent_capture"
        const val INTERACTION_CHANNEL = "agent_interaction"
    }
}

/**
 * A short pairing window for one agent. While open, POST /v1/pair with the
 * on-screen code returns that agent's new token exactly once. Closes after
 * 5 minutes, after a successful pair, or after 5 wrong codes.
 */
class Pairing {
    class Window(val agentId: String, val agentName: String, val code: String, val token: String,
                 val expiresAt: Long, var wrongAttempts: Int = 0)

    @Volatile var window: Window? = null
        private set

    fun open(agentId: String, agentName: String, token: String): Window {
        val code = (java.security.SecureRandom().nextInt(900000) + 100000).toString()
        return Window(agentId, agentName, code, token, System.currentTimeMillis() + WINDOW_MS).also { window = it }
    }

    fun close() { window = null }

    /** Returns the window on a correct code (and closes it), or null. */
    @Synchronized
    fun redeem(code: String?): Window? {
        val w = window ?: return null
        if (System.currentTimeMillis() > w.expiresAt) { window = null; return null }
        if (code != null && java.security.MessageDigest.isEqual(code.toByteArray(), w.code.toByteArray())) {
            window = null
            return w
        }
        w.wrongAttempts++
        if (w.wrongAttempts >= 5) window = null
        return null
    }

    fun isOpen(): Boolean = window?.let { System.currentTimeMillis() <= it.expiresAt } ?: false

    companion object { const val WINDOW_MS = 5 * 60 * 1000L }
}
