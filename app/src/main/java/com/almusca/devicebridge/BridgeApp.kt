package com.almusca.devicebridge

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class BridgeApp : Application() {
    lateinit var policy: Policy
    lateinit var audit: AuditLog
    val pairing = Pairing()

    override fun onCreate() {
        super.onCreate()
        policy = Policy(this)
        audit = AuditLog(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Device Bridge listener", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the listener runs and during every capture"
            }
        )
    }

    companion object {
        const val CHANNEL = "bridge"
    }
}

/**
 * A short pairing window. While open, POST /v1/pair with the on-screen code
 * returns the new token exactly once. Closes after 5 minutes, after a
 * successful pair, or after 5 wrong codes.
 */
class Pairing {
    data class Window(val code: String, val token: String, val expiresAt: Long, var wrongAttempts: Int = 0)

    @Volatile var window: Window? = null
        private set

    fun open(token: String): Window {
        val code = (java.security.SecureRandom().nextInt(900000) + 100000).toString()
        return Window(code, token, System.currentTimeMillis() + WINDOW_MS).also { window = it }
    }

    fun close() { window = null }

    /** Returns the token on a correct code, or null. */
    @Synchronized
    fun redeem(code: String?): String? {
        val w = window ?: return null
        if (System.currentTimeMillis() > w.expiresAt) { window = null; return null }
        if (code != null && java.security.MessageDigest.isEqual(code.toByteArray(), w.code.toByteArray())) {
            window = null
            return w.token
        }
        w.wrongAttempts++
        if (w.wrongAttempts >= 5) window = null
        return null
    }

    fun isOpen(): Boolean = window?.let { System.currentTimeMillis() <= it.expiresAt } ?: false

    companion object { const val WINDOW_MS = 5 * 60 * 1000L }
}
