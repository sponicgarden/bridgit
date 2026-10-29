package net.finleg.bridget.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Each agent's callback URL and secret (EVT-05, EVT-06), in their own prefs file (`callbacks`), never in
 * the agents JSON. Bridgit must keep the secret to sign deliveries, so it is stored sealed with a
 * non-exportable Android Keystore AES-GCM key (the PendingKeyVault pattern). The plaintext secret
 * leaves this class only for signing and for the one-time dialog or a refreshed setup prompt.
 * App-private and excluded from backup like the other prefs (SEC-15).
 */
class CallbackStore(context: Context) {
    private val prefs = context.getSharedPreferences("callbacks", Context.MODE_PRIVATE)

    /** What's safe to show and log: the URL (shown to Rahul only) and its host. */
    data class Config(val agentId: String, val url: String) {
        val host get() = CallbackRules.hostOf(url)
    }

    fun get(agentId: String): Config? = prefs.getString("url_$agentId", null)?.let { Config(agentId, it) }

    fun all(): List<Config> = prefs.all.keys.filter { it.startsWith("url_") }.mapNotNull { get(it.removePrefix("url_")) }

    /** Saves [url] for [agentId] with a new secret, and returns that secret (shown once). */
    @Synchronized
    fun set(agentId: String, url: String): String {
        val secret = CallbackRules.newSecret()
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("url_$agentId", url.trim())
            .putString("iv_$agentId", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("secret_$agentId", Base64.encodeToString(sealed, Base64.NO_WRAP))
            .commit()
        return secret
    }

    /** The secret for signing, or null (none set, or unreadable: then the config is wiped). */
    @Synchronized
    fun secret(agentId: String): String? {
        val iv = prefs.getString("iv_$agentId", null) ?: return null
        val data = prefs.getString("secret_$agentId", null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrElse { remove(agentId); null }
    }

    /** Removes the URL and deletes the secret. */
    @Synchronized
    fun remove(agentId: String) {
        prefs.edit().remove("url_$agentId").remove("iv_$agentId").remove("secret_$agentId").commit()
    }

    /** Agent removal deletes its callback; returns the ids removed. Rotation keeps it (same agent id). */
    @Synchronized
    fun prune(existingIds: Set<String>): List<String> {
        val gone = all().map { it.agentId }.filter { it !in existingIds }
        gone.forEach { remove(it) }
        return gone
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    companion object {
        private const val ALIAS = "bridget_callback_secret"
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
