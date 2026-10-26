package com.almusca.devicebridge

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Settings, capability toggles and the paired-token hash, kept in app-private
 * storage. Only a salted SHA-256 hash of the token is stored; the token itself
 * lives in memory only while a pairing window is open.
 */
class Policy(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("policy", Context.MODE_PRIVATE)

    enum class Capability(val key: String) { CAMERA("camera"), MIC("mic"), GALLERY("gallery") }

    fun isEnabled(c: Capability) = prefs.getBoolean("cap_${c.key}", false)
    fun setEnabled(c: Capability, on: Boolean) = prefs.edit().putBoolean("cap_${c.key}", on).apply()

    var listenerEnabled: Boolean
        get() = prefs.getBoolean("listener", false)
        set(v) = prefs.edit().putBoolean("listener", v).apply()

    var port: Int
        get() = prefs.getInt("port", 8734)
        set(v) = prefs.edit().putInt("port", v).apply()

    val isPaired: Boolean get() = prefs.getString("token_hash", null) != null

    /** Generates a fresh 256-bit token, stores its salted hash, returns the token. */
    fun issueToken(): String {
        val rnd = SecureRandom()
        val token = ByteArray(32).also { rnd.nextBytes(it) }.toHex()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }.toHex()
        prefs.edit()
            .putString("token_salt", salt)
            .putString("token_hash", hash(salt, token))
            .putLong("paired_at", System.currentTimeMillis())
            .apply()
        return token
    }

    fun verifyToken(candidate: String?): Boolean {
        if (candidate.isNullOrEmpty()) return false
        val salt = prefs.getString("token_salt", null) ?: return false
        val stored = prefs.getString("token_hash", null) ?: return false
        return MessageDigest.isEqual(
            stored.toByteArray(), hash(salt, candidate).toByteArray()
        )
    }

    fun unpair() {
        prefs.edit().remove("token_salt").remove("token_hash").remove("paired_at").apply()
    }

    private fun hash(salt: String, token: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + ":" + token).toByteArray()).toHex()
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
