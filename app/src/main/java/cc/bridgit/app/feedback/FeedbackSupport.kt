package cc.bridgit.app.feedback

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BuildConfig
import cc.bridgit.app.R
import cc.bridgit.app.capability.appVersion
import cc.bridgit.app.capability.isoAt
import cc.bridgit.app.log.LogQuery
import cc.bridgit.app.log.Redactor
import cc.bridgit.app.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** What a report carries automatically (FBK-06, FBK-04 item 7). */
object FeedbackContext {
    /**
     * [screen] is the screen the owner was on ("Activity"), [screenContext] a short hint
     * such as the selected agent or filter (≤500 chars). Agent names appear only
     * when the owner was on an agent screen.
     */
    fun build(app: BridgeApp, screen: String?, screenContext: String?, captureSource: String): JSONObject {
        val l = app.listener.value
        val switches = JSONObject()
        app.registry.switchable().forEach { switches.put(it.id, it.enabled) }
        return JSONObject()
            .put("app_version", app.appVersion()).put("version_code", app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode)
            .put("git_commit", BuildConfig.GIT_COMMIT).put("dirty_build", BuildConfig.DIRTY_BUILD)
            .put("build_type", if (BuildConfig.DEBUG) "debug" else "release")
            .put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}").put("android_version", Build.VERSION.RELEASE)
            .put("api_level", Build.VERSION.SDK_INT).put("security_patch", Build.VERSION.SECURITY_PATCH)
            .put("locale", Locale.getDefault().toLanguageTag())
            .put("screen", screen ?: JSONObject.NULL)
            .put("screen_context", screenContext?.let { Redactor.string(it, FeedbackLimits.CONTEXT_MAX) } ?: JSONObject.NULL)
            .put("listener", JSONObject().put("running", l.running).put("listening", l.listening)
                .put("camera_ready", l.cameraReady).put("mic_ready", l.micReady).put("bind_error", l.bindError ?: JSONObject.NULL))
            .put("tailscale", if (l.boundTo.isNotEmpty()) "connected" else "not connected")
            .put("switches", switches).put("agent_count", app.agents.count)
            .put("capture_source", captureSource)
    }

    /** The last 50 audit and diagnostic entries, as stored (already redacted, LOG-02). */
    fun recentLog(app: BridgeApp): JSONArray {
        val q = LogQuery(channels = listOf("audit", "diagnostic"), limit = FeedbackLimits.LOG_ENTRIES)
        return JSONArray(app.log.query(q).map { e -> e.toJson(::isoAt, e.agentId?.let { app.agents.get(it)?.name } ?: e.agent) })
    }
}

/**
 * Tells the owner when a developer agent marks a report fixed / won't fix / duplicate
 * (FBK-11), with Confirm and Still broken actions (Spotka's verify step).
 */
object FeedbackNotifier {
    const val CHANNEL = "feedback"
    const val ACTION = "cc.bridgit.app.FEEDBACK_VERIFY"

    fun createChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            android.app.NotificationChannel(CHANNEL, ctx.getString(R.string.channel_feedback), NotificationManager.IMPORTANCE_LOW)
                .apply { description = ctx.getString(R.string.channel_feedback_desc) })
    }

    fun statusChanged(ctx: Context, r: Report) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val notifId = 5000 + r.id.toInt()
        if (!StatusMachine.notifiesOwner(r.status)) { nm.cancel(notifId); return }
        fun action(to: ReportStatus, code: Int) = PendingIntent.getBroadcast(ctx, notifId * 10 + code,
            Intent(ctx, FeedbackActionReceiver::class.java).setAction(ACTION).putExtra("id", r.id).putExtra("to", to.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(ctx, notifId, Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_REPORT_ID, r.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = ctx.getString(R.string.feedback_notif_title, r.number, StatusMachine.label(r.status).substringBefore(" —").lowercase())
        nm.notify(notifId, NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif).setColor(ctx.getColor(R.color.brand_primary))
            .setContentTitle(title)
            .setContentText(r.history.lastOrNull()?.note ?: r.firstLine)
            .setContentIntent(open).setAutoCancel(true)
            .addAction(0, ctx.getString(R.string.feedback_confirm), action(ReportStatus.CONFIRMED, 1))
            .addAction(0, ctx.getString(R.string.feedback_still_broken), action(ReportStatus.REOPENED, 2))
            .build())
    }
}

/** Confirm / Still broken from the notification. Not exported: only our own PendingIntents reach it. */
class FeedbackActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FeedbackNotifier.ACTION) return
        val app = context.applicationContext as BridgeApp
        val id = intent.getLongExtra("id", -1)
        val to = ReportStatus.of(intent.getStringExtra("to")) ?: return
        runCatching {
            val r = app.feedback.move(id, to, Actor.OWNER, "owner", null)
            app.log.system("audit", "feedback", "You marked report ${r.number} ${if (to == ReportStatus.CONFIRMED) "confirmed" else "still broken"}")
        }
        context.getSystemService(NotificationManager::class.java).cancel(5000 + id.toInt())
    }
}
