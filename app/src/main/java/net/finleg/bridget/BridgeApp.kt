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
    lateinit var interactions: net.finleg.bridget.interact.InteractionStore
    lateinit var speaker: net.finleg.bridget.capability.SpeakerQueue
    lateinit var tours: net.finleg.bridget.tour.TourManager
    lateinit var callbacks: net.finleg.bridget.core.CallbackStore
    lateinit var callbackDispatcher: net.finleg.bridget.interact.CallbackDispatcher
    lateinit var screenGrants: net.finleg.bridget.planned.ScreenGrants
    lateinit var hardware: net.finleg.bridget.planned.Hardware
    lateinit var transfers: net.finleg.bridget.planned.Transfers
    lateinit var clipboardRequests: net.finleg.bridget.planned.ClipboardRequests
    lateinit var alerts: net.finleg.bridget.alerts.Alerts
    lateinit var wake: net.finleg.bridget.listen.WakeListener
    val pairing = Pairing()
    val requests = net.finleg.bridget.api.RequestState()

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
        net.finleg.bridget.core.CrashReports.start(this)
        settings = Settings(this)
        agents = AgentRegistry(settings.agentStorage(this))
        log = LogStore(this) { settings.retention() }
        alerts = net.finleg.bridget.alerts.Alerts(this)
        log.onAppended = alerts::onLog
        runCatching { alerts.poke() }
        createChannels()
        java.io.File(cacheDir, "uploads").listFiles()?.forEach { it.delete() }
        registry = Registry(this)
        screenGrants = net.finleg.bridget.planned.ScreenGrants(this)
        hardware = net.finleg.bridget.planned.Hardware(this)
        transfers = net.finleg.bridget.planned.Transfers(this)
        clipboardRequests = net.finleg.bridget.planned.ClipboardRequests(this)
        settings.addListener { if (!settings.listenerEnabled || !settings.isOn("notifications")) net.finleg.bridget.planned.NotificationMemory.clear() }
        vault = PendingKeyVault(this)
        feedback = FeedbackStore(this)
        interactions = net.finleg.bridget.interact.InteractionStore(this)
        speaker = net.finleg.bridget.capability.SpeakerQueue(this)
        tours = net.finleg.bridget.tour.TourManager(this)
        wake = net.finleg.bridget.listen.WakeListener(this)
        agents.addListener {
            tours.current()?.let { t -> if (agents.get(t.agentId)?.let { it.allows("tour") && it.allows("interactions") && it.allows("location") && it.allows("speaker") } != true) tours.end("Agent access revoked") }
            speaker.recheckAccess()
        }
        callbacks = net.finleg.bridget.core.CallbackStore(this)
        callbackDispatcher = net.finleg.bridget.interact.CallbackDispatcher(this, callbacks)
        interactions.addEventListener { callbackDispatcher.onEvent(it) } // EVT-06
        interactions.recoverInterruptedTours()
        // Play aloud (UI-16): the owner's own tap, so silent/DND don't block it; a call still does. Same queue as everything else.
        net.finleg.bridget.ui.interactions.SpeakHook.speak = { _, agent, title, text ->
            if (!speaker.inCall()) speaker.speakText("owner", agent, net.finleg.bridget.interact.NotifRules.utterance(agent, title, text), allowInQuiet = true)
        }
        feedback.onStatusChange { FeedbackNotifier.statusChanged(this, it) }
        interactions.addListener { i, what -> net.finleg.bridget.interact.InteractionNotifier.onChange(this, i, what) }
        // UI-23: keep the Quick Settings tile in step when Home, the notification or the tile pauses or resumes.
        var tileListening = settings.listenerEnabled
        settings.addListener {
            val now = settings.listenerEnabled
            if (now != tileListening) { tileListening = now; net.finleg.bridget.ui.home.ListenerTile.requestUpdate(this) }
        }
        wake.start()
        agents.addListener { wake.sync() }
        applyAppearance()
    }

    fun speakerReady() = ::speaker.isInitialized

    fun applyAppearance() = AppCompatDelegate.setDefaultNightMode(when (settings.appearance) {
        "light" -> AppCompatDelegate.MODE_NIGHT_NO
        "dark" -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    })

    /**
     * UI-21 channels: listener, capture, questions and notices (1.5), feedback.
     * Files and grants channels are added with their features (P4, P5).
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
        nm.createNotificationChannel(NotificationChannel(net.finleg.bridget.interact.InteractionNotifier.CHANNEL_QUESTIONS,
            getString(R.string.channel_questions), NotificationManager.IMPORTANCE_HIGH).apply { description = getString(R.string.channel_questions_desc) })
        nm.createNotificationChannel(NotificationChannel(net.finleg.bridget.interact.InteractionNotifier.CHANNEL_NOTICES,
            getString(R.string.channel_notices), NotificationManager.IMPORTANCE_DEFAULT).apply { description = getString(R.string.channel_notices_desc) })
        FeedbackNotifier.createChannel(this)
        net.finleg.bridget.update.UpdateInstaller.cleanStale(this) // UPD-05: downloads older than an hour go
    }

    /** PRD-05: captures need Bridgit's notifications on, including the capture channel. */
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
