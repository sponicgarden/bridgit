package net.finleg.bridget

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Bridgit's always-on listener. One long-lived foreground service that both runs the
 * HTTP listener and owns camera/microphone access. It must be started by a tap
 * while the app is on screen: Android only grants camera/microphone access to a
 * foreground service that started while the app was visible.
 */
class BridgeService : LifecycleService() {
    private val app get() = application as BridgeApp
    private var server: ApiServer? = null
    private var boundTo: String? = null
    private var watcher: Job? = null
    var startedAt = System.currentTimeMillis(); private set
    @Volatile var cameraReady = false; private set
    @Volatile var micReady = false; private set
    @Volatile var locationReady = false; private set
    @Volatile private var captureNote: String? = null
    @Volatile var captureAgentId: String? = null; private set

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            app.policy.listenerEnabled = false
            app.audit.event("listener", "stopped from notification")
            stopEverything()
            return START_NOT_STICKY
        }
        if (!app.policy.listenerEnabled) { stopEverything(); return START_NOT_STICKY }
        if (!promote()) return START_NOT_STICKY
        if (watcher == null) {
            startedAt = System.currentTimeMillis()
            watcher = lifecycleScope.launch(Dispatchers.IO) { watchTailscale() }
        }
        running = this
        return START_STICKY
    }

    /** (Re)enters the foreground with every type whose permission is held. */
    private fun promote(): Boolean {
        val wantCamera = granted(Manifest.permission.CAMERA)
        val wantMic = granted(Manifest.permission.RECORD_AUDIO)
        val wantLocation = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (wantCamera) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (wantMic) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (wantLocation) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), types)
            cameraReady = wantCamera; micReady = wantMic; locationReady = wantLocation
            return true
        } catch (e: Exception) {
            Log.w(TAG, "camera/mic foreground start refused, falling back", e)
        }
        // Started from the background (for example a system restart): Android
        // refuses camera/mic types. Keep the listener up without them.
        return try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            cameraReady = false; micReady = false; locationReady = false
            app.audit.event("listener", "started without camera/mic access; open the app once to restore")
            true
        } catch (e: Exception) {
            Log.e(TAG, "foreground start refused", e)
            app.audit.event("listener", "Android refused to start the listener: ${e.javaClass.simpleName}")
            stopSelf()
            false
        }
    }

    /** Binds to the Tailscale address and rebinds when it changes. Never binds elsewhere. */
    private suspend fun watchTailscale() {
        while (lifecycleScope.isActive) {
            val addr = tailscaleAddress()
            if (addr != boundTo) {
                server?.stop(); server = null; boundTo = null
                if (addr != null) {
                    try {
                        ApiServer(this, addr, app.policy.port).also { it.start(); server = it }
                        boundTo = addr
                        app.audit.event("listener", "listening on $addr:${app.policy.port}")
                    } catch (e: Exception) {
                        Log.e(TAG, "bind failed", e)
                        app.audit.event("listener", "could not bind $addr: ${e.message}")
                    }
                } else {
                    app.audit.event("listener", "Tailscale address gone; listener down")
                }
                refreshNotification()
                notifyUi()
            }
            runCatching { app.platform.workflows.tick(); app.platform.delivery.tick() }
            delay(1_000)
        }
    }

    fun showCapture(note: String?, agentId: String? = null) {
        captureAgentId = agentId
        captureNote = note
        refreshNotification()
        notifyUi()
    }

    fun profileChanged() {
        refreshNotification()
        notifyUi()
    }

    fun onPaired(agent: String) {
        app.audit.event("pairing", "$agent fetched its token")
        notifyUi()
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val text = captureNote ?: boundTo?.let { "Listening on Tailscale $it:${app.policy.port}" }
            ?: "Waiting for Tailscale - not reachable"
        val agent = app.policy.agents().firstOrNull { it.id == captureAgentId }
        return NotificationCompat.Builder(this, if (captureNote != null) BridgeApp.CAPTURE_CHANNEL else BridgeApp.CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(if (captureNote != null) "BRIDGIT - capturing" else "BRIDGIT Device Manager")
            .setContentText(text)
            .setLargeIcon(AgentBranding.bitmap(agent?.iconBase64))
            .setOngoing(true)
            .setOnlyAlertOnce(captureNote == null)
            .setContentIntent(open)
            .addAction(0, "Stop listener", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun stopEverything() {
        app.platform.stopAll()
        watcher?.cancel(); watcher = null
        server?.stop(); server = null; boundTo = null
        cameraReady = false; micReady = false; locationReady = false
        if (running === this) running = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        notifyUi()
    }

    override fun onDestroy() {
        server?.stop(); server = null
        if (running === this) running = null
        super.onDestroy()
    }

    private fun notifyUi() = sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))

    val bindAddress: String? get() = boundTo

    companion object {
        private const val TAG = "BridgeService"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "net.finleg.bridget.STOP"
        const val ACTION_STATE = "net.finleg.bridget.STATE"

        @Volatile var running: BridgeService? = null; private set

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, BridgeService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, BridgeService::class.java).setAction(ACTION_STOP))

        /** The phone's Tailscale IPv4 (100.64.0.0/10), or null if Tailscale is down. */
        fun tailscaleAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress!! }
                .firstOrNull { ApiServer.isTailnet(it) }
        }.getOrNull()
    }
}
