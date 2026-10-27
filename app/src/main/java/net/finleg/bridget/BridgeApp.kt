package net.finleg.bridget

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.finleg.bridget.capability.Registry
import net.finleg.bridget.core.AgentRegistry
import net.finleg.bridget.core.PendingKeyVault
import net.finleg.bridget.core.Settings
import net.finleg.bridget.feedback.FeedbackNotifier
import net.finleg.bridget.feedback.FeedbackStore
import net.finleg.bridget.log.LogStore

/** Process-wide state. Everything here lives as long as the process. */
class BridgeApp : Application() {
    lateinit var settings: Settings
    lateinit var agents: AgentRegistry
    lateinit var log: LogStore
    lateinit var registry: Registry
    lateinit var vault: PendingKeyVault
    lateinit var feedback: FeedbackStore
    val pairing = Pairing()

    /** What the listener is doing, for Home, the notification and the Add-agent check. */
    data class ListenerState(
        val running: Boolean = false,
        val boundTo: List<String> = emptyList(),
        /** The phone's MagicDNS name, from a reverse lookup of its Tailscale IP (null if MagicDNS is off). */
        val magicDns: String? = null,
        val port: Int = 0,
        val startedAt: Long = 0,
        val cameraReady: Boolean = false,
        val micReady: Boolean = false,
        val bindError: String? = null,
        val capturing: String? = null,
        /** True when the service last started from the background (boot or restart), so camera/mic are off (ARC-03). */
        val startedInBackground: Boolean = false,
    ) {
        val listening get() = running && boundTo.isNotEmpty()
    }

    private val _listener = MutableStateFlow(ListenerState())
    val listener: StateFlow<ListenerState> get() = _listener
    fun updateListener(f: (ListenerState) -> ListenerState) { _listener.value = f(_listener.value) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        agents = AgentRegistry(settings.agentStorage(this))
        log = LogStore(this) { settings.retention() }
        registry = Registry(this)
        vault = PendingKeyVault(this)
        feedback = FeedbackStore(this)
        feedback.onStatusChange { FeedbackNotifier.statusChanged(this, it) }
        applyAppearance()
        createChannels()
    }

    fun applyAppearance() = AppCompatDelegate.setDefaultNightMode(when (settings.appearance) {
        "light" -> AppCompatDelegate.MODE_NIGHT_NO
        "dark" -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    })

    /**
     * UI-21 channels that P1a uses. Questions, notices, files, grants and feedback
     * channels are added with their features (P1b, P2, P4, P5).
     */
    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("bridge") // 1.2's single channel, if a debug build carried it over
        nm.createNotificationChannel(NotificationChannel(CHANNEL_LISTENER, getString(R.string.channel_listener),
            NotificationManager.IMPORTANCE_LOW).apply { description = getString(R.string.channel_listener_desc) })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_CAPTURE, getString(R.string.channel_capture),
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = getString(R.string.channel_capture_desc); setSound(null, null)
        })
        FeedbackNotifier.createChannel(this)
    }

    /** PRD-05: captures need Bridget's notifications on, including the capture channel. */
    fun captureNotificationsOn(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return false
        return (nm.getNotificationChannel(CHANNEL_CAPTURE)?.importance ?: 0) != NotificationManager.IMPORTANCE_NONE
    }

    companion object {
        const val CHANNEL_LISTENER = "listener"
        const val CHANNEL_CAPTURE = "capture"
        lateinit var instance: BridgeApp; private set
    }
}

/**
 * Pair with code (AGT-13): a 6-digit code for 5 minutes. POST /v1/pair with it
 * returns the agent's key once. Five wrong codes close the window.
 */
class Pairing {
    class Window(val agentId: String, val agentName: String, val code: String, val key: String,
                 val expiresAt: Long, var wrongAttempts: Int = 0) {
        @Volatile var redeemed: Boolean = false
    }

    @Volatile var window: Window? = null
        private set

    fun open(agentId: String, agentName: String, key: String): Window {
        val code = (java.security.SecureRandom().nextInt(900000) + 100000).toString()
        return Window(agentId, agentName, code, key, System.currentTimeMillis() + WINDOW_MS).also { window = it }
    }

    fun close() { window = null }

    @Synchronized
    fun redeem(code: String?): Window? {
        val w = window ?: return null
        if (System.currentTimeMillis() > w.expiresAt) { window = null; return null }
        if (code != null && java.security.MessageDigest.isEqual(code.toByteArray(), w.code.toByteArray())) {
            window = null
            w.redeemed = true
            return w
        }
        w.wrongAttempts++
        if (w.wrongAttempts >= 5) window = null
        return null
    }

    fun isOpen(): Boolean = window?.let { System.currentTimeMillis() <= it.expiresAt } ?: false

    companion object { const val WINDOW_MS = 5 * 60 * 1000L }
}
