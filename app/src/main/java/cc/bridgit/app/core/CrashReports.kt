package cc.bridgit.app.core

import android.content.Context
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.android.core.SentryAndroid
import cc.bridgit.app.BuildConfig

/**
 * Crash reports to Sentry (D32, owner request 27 Oct 2026): crashes, ANRs and unhandled errors only.
 * Sent: the error and stack, app version, Android version and basic device facts (model, battery,
 * memory). Not sent: screenshots, view hierarchy, user or install ids, request data, sessions or
 * tracing; every message and exception text goes through [Keys.scrub] first. The project also drops
 * the IP and the city Sentry would infer from it. On by default, with a Settings switch.
 * Auto-init is off in the manifest so the switch is honoured before anything is sent.
 * The DSN comes from the build (BuildConfig.SENTRY_DSN); builds without one never send anything.
 */
object CrashReports {
    const val PREF = "crash_reports_off"

    /** False for builds made without a Sentry DSN (forks, local builds): no reports, no Settings switch. */
    val configured get() = BuildConfig.SENTRY_DSN.isNotBlank()

    fun enabled(ctx: Context) = !ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(PREF, false)

    fun start(ctx: Context) {
        if (!configured || !enabled(ctx) || Sentry.isEnabled()) return
        SentryAndroid.init(ctx) { o ->
            o.dsn = BuildConfig.SENTRY_DSN
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
            o.isEnableAutoSessionTracking = false // no ping on every launch: Bridgit talks to Sentry only when something breaks
            o.tracesSampleRate = null
            o.setBeforeSend { e, _ -> scrub(e) }
            o.setBeforeBreadcrumb { b, _ -> b.message = b.message?.let(Keys::scrub); b }
        }
        Sentry.setTag("git_commit", BuildConfig.GIT_COMMIT)
    }

    /** Turned off in Settings: stop sending now. Turning it back on calls [start] again. */
    fun stop() { if (Sentry.isEnabled()) Sentry.close() }

    private fun scrub(e: SentryEvent): SentryEvent {
        e.user = null
        e.serverName = null
        e.contexts.device?.let { it.id = null } // Sentry's per-install id
        e.message?.let { m -> m.formatted = m.formatted?.let(Keys::scrub); m.message = m.message?.let(Keys::scrub) }
        e.exceptions?.forEach { x -> x.value = x.value?.let(Keys::scrub) }
        e.breadcrumbs?.forEach { b -> b.message = b.message?.let(Keys::scrub) }
        return e
    }
}
