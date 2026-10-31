package cc.bridgit.app.core

import android.content.Context
import android.content.SharedPreferences
import cc.bridgit.app.BuildConfig
import cc.bridgit.app.log.LogStore

/**
 * Settings and master switches (SEC-03: every switch starts off), kept in
 * app-private SharedPreferences and excluded from backup (SEC-15).
 */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun changed() = synchronized(listeners) { listeners.toList() }.forEach { it() }

    fun isOn(switchKey: String) = prefs.getBoolean("switch_$switchKey", false)
    fun setOn(switchKey: String, on: Boolean) { prefs.edit().putBoolean("switch_$switchKey", on).apply(); changed() }

    var listenerEnabled: Boolean
        get() = prefs.getBoolean("listener", false)
        set(v) { prefs.edit().putBoolean("listener", v).apply(); changed() }

    var port: Int
        get() = prefs.getInt("port", BuildConfig.DEFAULT_PORT)
        set(v) { prefs.edit().putInt("port", v).apply(); changed() }

    /** Section 10: "all" (default, D12) or "own". Developer agents always see everything. */
    var logVisibility: String
        get() = prefs.getString("log_visibility", "all") ?: "all"
        set(v) { prefs.edit().putString("log_visibility", v).apply(); changed() }

    var retentionEntries: Int
        get() = prefs.getInt("retention_entries", 20_000)
        set(v) { prefs.edit().putInt("retention_entries", v.coerceIn(1_000, 100_000)).apply(); changed() }

    var retentionDays: Int
        get() = prefs.getInt("retention_days", 30)
        set(v) { prefs.edit().putInt("retention_days", v.coerceIn(7, 365)).apply(); changed() }

    /** "system", "light" or "dark" (UI-19 Appearance). */
    var appearance: String
        get() = prefs.getString("appearance", "system") ?: "system"
        set(v) { prefs.edit().putString("appearance", v).apply(); changed() }

    /** SPK-01: agents' audio is never louder than this share of the media volume. */
    var speakerCeiling: Int
        get() = prefs.getInt("speaker_ceiling", 70)
        set(v) { prefs.edit().putInt("speaker_ceiling", v.coerceIn(10, 100)).apply(); changed() }

    /** EVT-05: a public HTTPS callback URL needs this (off by default). Tailnet callbacks never do. */
    var allowInternetCallbacks: Boolean
        get() = prefs.getBoolean(PREF_INTERNET_CALLBACKS, false)
        set(v) { prefs.edit().putBoolean(PREF_INTERNET_CALLBACKS, v).apply(); changed() }

    /** Email alerts: "every", "hourly", "daily", "weekly" or "none" (default), see alerts.AlertFrequency. */
    var alertFrequency: String
        get() = prefs.getString("alert_frequency", "none") ?: "none"
        set(v) { prefs.edit().putString("alert_frequency", v).apply(); changed() }

    /** The one address that gets email alerts for every agent, or null. */
    var alertEmail: String?
        get() = prefs.getString("alert_email", null)
        set(v) { prefs.edit().putString("alert_email", v).apply(); changed() }

    /** Whether bridgit.cc has [alertEmail] confirmed: "none", "pending" or "confirmed". */
    var alertState: String
        get() = prefs.getString("alert_state", "none") ?: "none"
        set(v) { prefs.edit().putString("alert_state", v).apply(); changed() }

    /** Wake words (CAP-47) as JSON: [{phrase, agent_id, send_clip}]. Edited only on the phone. */
    var wakeWords: String
        get() = prefs.getString("wake_words", "[]") ?: "[]"
        set(v) { prefs.edit().putString("wake_words", v).apply(); changed() }

    /** "Listen for any sounds" as JSON {agent_id, sensitivity, send_clip}, or empty. */
    var soundsConfig: String
        get() = prefs.getString("sounds_config", "") ?: ""
        set(v) { prefs.edit().putString("sounds_config", v).apply(); changed() }

    var firstRunDone: Boolean
        get() = prefs.getBoolean("first_run_done", false)
        set(v) { prefs.edit().putBoolean("first_run_done", v).apply() }

    fun retention() = LogStore.Retention(retentionEntries, retentionDays)

    /** For the agents' persistence: one JSON blob in its own prefs file. */
    fun agentStorage(context: Context): AgentStorage = object : AgentStorage {
        private val p = context.getSharedPreferences("agents", Context.MODE_PRIVATE)
        override fun load(): String? = p.getString("agents", null)
        override fun save(json: String) { p.edit().putString("agents", json).apply() }
    }

    companion object {
        const val PREF_INTERNET_CALLBACKS = "allow_internet_callbacks"
    }
}
