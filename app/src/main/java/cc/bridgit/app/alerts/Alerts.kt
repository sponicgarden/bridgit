package cc.bridgit.app.alerts

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.log.LogEntry
import java.util.concurrent.TimeUnit

/**
 * Email alerts (Settings › Email alerts): what's pending, the relay, and the WorkManager jobs
 * that send. A one-time job is queued for the exact moment something becomes due (the end of a
 * 10-minute burst, or the next digest boundary) when a call arrives. A 15-minute periodic job is
 * the safety net: it catches anything a one-time job missed (a call that arrived while a job was
 * running, a reboot, Doze). Both just run [AlertDispatcher.run], which is idempotent.
 */
class Alerts(private val app: BridgeApp) {
    val store = AlertStore(app)
    val relay = AlertRelay(app)
    val dispatcher = AlertDispatcher(store, relay,
        frequency = { AlertFrequency.of(app.settings.alertFrequency) },
        confirmed = { app.settings.alertState == STATE_CONFIRMED },
        onGone = {
            app.settings.alertState = STATE_NONE
            app.log.system("audit", "setting", "Email alerts stopped: bridgit.cc says the address is unsubscribed or unconfirmed")
        },
        phone = { android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "your phone" })

    /** Set while a job is queued, so recording a call doesn't touch WorkManager every time. */
    @Volatile private var queued = false

    /** Hooked to the log: every agent API call (HTTP or MCP). Runs on the log writer thread. */
    fun onLog(e: LogEntry) {
        if (e.channel != "audit" || e.kind != "request" || e.agentId == null) return
        val kept = dispatcher.record(AlertEvent(ts = e.ts, agentId = e.agentId, agent = e.agent ?: e.agentId,
            method = e.method ?: "", endpoint = e.endpoint ?: "", http = e.http ?: 0, message = e.message))
        if (kept && !queued) dispatcher.nextCheck()?.let { schedule(it, replace = false) }
    }

    /** At startup and after every settings change: keep the safety net in step and look again now. */
    fun poke() {
        val wm = WorkManager.getInstance(app)
        if (AlertFrequency.of(app.settings.alertFrequency) == AlertFrequency.NONE) {
            wm.cancelUniqueWork(PERIODIC)
            wm.cancelUniqueWork(WORK)
            queued = false
            store.clear()
            return
        } else {
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES).setConstraints(network()).build())
        }
        schedule(System.currentTimeMillis(), replace = true)
    }

    private fun network() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun schedule(at: Long, replace: Boolean) {
        queued = true
        val delay = (at - System.currentTimeMillis()).coerceAtLeast(0)
        val req = OneTimeWorkRequestBuilder<AlertWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(network())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(WORK, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
    }

    /** One dispatcher pass. Afterwards the next arriving call queues a fresh one-time job. */
    internal fun runOnce(): AlertDispatcher.Outcome {
        queued = false
        // The owner may confirm from the email without reopening Settings, so the job checks too.
        if (app.settings.alertState == STATE_PENDING && AlertFrequency.of(app.settings.alertFrequency) != AlertFrequency.NONE) {
            when (relay.status()) {
                AlertRelay.State.CONFIRMED -> app.settings.alertState = STATE_CONFIRMED
                AlertRelay.State.NONE -> app.settings.alertState = STATE_NONE
                else -> {}
            }
        }
        return dispatcher.run()
    }

    companion object {
        const val WORK = "bridgit-email-alerts"
        const val PERIODIC = "bridgit-email-alerts-periodic"
        const val STATE_NONE = "none"
        const val STATE_PENDING = "pending"
        const val STATE_CONFIRMED = "confirmed"
    }
}

class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        when ((applicationContext as BridgeApp).alerts.runOnce()) {
            AlertDispatcher.Outcome.Retry -> Result.retry()
            else -> Result.success()
        }
    }
}
