package net.finleg.bridget.core

import android.content.Context
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.android.core.SentryAndroid
import net.finleg.bridget.BuildConfig

/**
 * Crash reports to Sentry (D32, owner request 27 Oct 2026): crashes, ANRs and unhandled errors only.
 * On by default, with a Settings switch. No screenshots, view hierarchy, user or device ids, request
 * data or performance tracing; every message and exception text goes through [Keys.scrub] first.
 * Auto-init is off in the manifest so the switch is honoured before anything is sent.
 */
object CrashReports {
    const val PREF = "crash_reports_off"
    private const val DSN = "https://e367c30722ee1a5f17ff3bce4eaae8e5@o4511667182436352.ingest.de.sentry.io/4512329089941584"

    fun enabled(ctx: Context) = !ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(PREF, false)

    fun start(ctx: Context) {
        if (!enabled(ctx) || Sentry.isEnabled()) return
        SentryAndroid.init(ctx) { o ->
            o.dsn = DSN
            o.release = "${ctx.packageName}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            o.environment = if (BuildConfig.DEBUG) "debug" else "release"
            o.dist = BuildConfig.VERSION_CODE.toString()
            o.isSendDefaultPii = false
            o.isAttachScreenshot = false
            o.isAttachViewHierarchy = false
            o.isEnableUserInteractionBreadcrumbs = false
            o.isEnableNetworkEventBreadcrumbs = false
            o.isEnableSystemEventBreadcrumbs = false
            o.isEnableAppComponentBreadcrumbs = false
            o.isAnrEnabled = true
            o.isEnableAutoSessionTracking = true
            o.tracesSampleRate = null
            o.setBeforeSend { e, _ -> scrub(e) }
            o.setBeforeBreadcrumb { b, _ -> b.message = b.message?.let(Keys::scrub); b }
        }
        Sentry.setTag("git_commit", BuildConfig.GIT_COMMIT)
    }

    /** Turned off in Settings: stop sending now. Turning it back on takes effect at the next app start. */
    fun stop() { if (Sentry.isEnabled()) Sentry.close() }

    private fun scrub(e: SentryEvent): SentryEvent {
        e.user = null
        e.serverName = null
        e.message?.let { m -> m.formatted = m.formatted?.let(Keys::scrub); m.message = m.message?.let(Keys::scrub) }
        e.exceptions?.forEach { x -> x.value = x.value?.let(Keys::scrub) }
        e.breadcrumbs?.forEach { b -> b.message = b.message?.let(Keys::scrub) }
        return e
    }
}
