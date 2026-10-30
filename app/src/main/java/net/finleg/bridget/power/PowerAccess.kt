package net.finleg.bridget.power

import android.accessibilityservice.AccessibilityService
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.service.notification.NotificationListenerService
import net.finleg.bridget.BridgeApp
import net.finleg.bridget.R
import net.finleg.bridget.capability.granted
import net.finleg.bridget.power.PowerPlan.Kind
import net.finleg.bridget.power.PowerPlan.Step
import net.finleg.bridget.ui.home.UiActions

/** Android side of [PowerPlan]: what this build declares, what is on, and the real page for each step. */
class PowerAccess(private val app: BridgeApp) {
    private val pm get() = app.packageManager

    fun steps(): List<Step> {
        val info = runCatching { pm.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())) }.getOrNull()
        val declared = info?.requestedPermissions?.toList().orEmpty()
        return PowerPlan.steps(declared, ::dangerous, services(AccessibilityService.SERVICE_INTERFACE, android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE),
            services(NotificationListenerService.SERVICE_INTERFACE, android.Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE), sideloaded())
    }

    private fun dangerous(p: String): Boolean = runCatching {
        pm.getPermissionInfo(p, 0).protection == PermissionInfo.PROTECTION_DANGEROUS
    }.getOrDefault(false)

    /** Bridgit's own services bound with [permission] for [action], so a service added later joins the list. */
    private fun services(action: String, permission: String): List<String> =
        pm.queryIntentServices(Intent(action).setPackage(app.packageName), PackageManager.ResolveInfoFlags.of(0))
            .mapNotNull { it.serviceInfo }.filter { it.permission == permission }
            .map { ComponentName(it.packageName, it.name).flattenToString() }.sorted()

    /** Not installed by a store: Android 13+ may block accessibility and notification access as "restricted settings". */
    fun sideloaded(): Boolean = runCatching {
        pm.getInstallSourceInfo(app.packageName).installingPackageName !in STORES
    }.getOrDefault(true)

    fun granted(s: Step): Boolean = when {
        s.id == "notifications" -> app.granted(PowerPlan.NOTIFICATIONS) && app.captureNotificationsOn()
        s.id == "photos" -> s.permissions.filter { it != "android.permission.READ_MEDIA_VISUAL_USER_SELECTED" }.all(app::granted)
        s.kind == Kind.RUNTIME -> s.permissions.all(app::granted)
        s.id == "battery" -> app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) == true
        s.id == "usage" -> app.getSystemService(AppOpsManager::class.java)
            .checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName) == AppOpsManager.MODE_ALLOWED
        s.id == "write_settings" -> Settings.System.canWrite(app)
        s.id == "dnd" -> app.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        s.id == "installs" -> pm.canRequestPackageInstalls()
        s.id.startsWith(PowerPlan.A11Y) -> enabledIn(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, s.target)
        s.id.startsWith(PowerPlan.LISTENER) -> enabledIn("enabled_notification_listeners", s.target)
        else -> false
    }

    private fun enabledIn(key: String, flat: String?): Boolean {
        val me = flat?.let(ComponentName::unflattenFromString) ?: return false
        return Settings.Secure.getString(app.contentResolver, key).orEmpty().split(':')
            .any { ComponentName.unflattenFromString(it) == me }
    }

    /** The settings page for a SPECIAL or RESTRICTED step. App info is the fallback when it doesn't resolve. */
    fun intent(s: Step): Intent {
        val pkg = Uri.parse("package:${app.packageName}")
        return when {
            s.id == "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
            s.id == "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            s.id == "usage" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkg)
            s.id == "write_settings" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg)
            s.id == "dnd" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            s.id == "installs" -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg)
            s.id.startsWith(PowerPlan.LISTENER) -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, s.target)
            s.id.startsWith(PowerPlan.A11Y) -> Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, s.target)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        }
    }

    /** Fallback page when a step's own page doesn't open. */
    fun fallback(s: Step): Intent = when {
        s.id.startsWith(PowerPlan.A11Y) -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        s.id.startsWith(PowerPlan.LISTENER) -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
    }

    fun title(s: Step): String = when {
        s.id.startsWith(PowerPlan.A11Y) || s.id.startsWith(PowerPlan.LISTENER) ->
            app.getString(if (s.id.startsWith(PowerPlan.A11Y)) R.string.power_a11y else R.string.power_listener, serviceLabel(s.target))
        s.id.startsWith("perm:") -> permissionLabel(s.permissions.first())
        else -> TITLES[s.id]?.let(app::getString) ?: s.id
    }

    fun unlocks(s: Step): String = when {
        s.id.startsWith(PowerPlan.A11Y) -> app.getString(R.string.power_a11y_unlocks)
        s.id.startsWith(PowerPlan.LISTENER) -> app.getString(R.string.power_listener_unlocks)
        else -> UNLOCKS[s.id]?.let(app::getString) ?: app.getString(R.string.power_other_unlocks)
    }

    private fun serviceLabel(flat: String?): String {
        val cn = flat?.let(ComponentName::unflattenFromString) ?: return app.getString(R.string.app_name)
        return runCatching { pm.getServiceInfo(cn, PackageManager.ComponentInfoFlags.of(0)).loadLabel(pm).toString() }
            .getOrDefault(cn.shortClassName)
    }

    private fun permissionLabel(p: String): String = runCatching {
        pm.getPermissionInfo(p, 0).loadLabel(pm).toString().replaceFirstChar { it.uppercase() }
    }.getOrDefault(p.substringAfterLast('.'))

    // ---- Switches and agent grants

    /** Every master switch on, each with its audit line (UI-18). Capabilities added later are picked up from the registry. */
    fun allSwitchesOn() = app.registry.switchable().forEach { UiActions.setSwitch(app, it, true) }

    /** Every master switch off. Android permissions stay as they are; Access links to each one. */
    fun allSwitchesOff() = app.registry.switchable().forEach { UiActions.setSwitch(app, it, false) }

    fun everySwitchOn(): Boolean = app.registry.switchable().all { it.enabled }

    /**
     * Lets [agentId] use every capability, including ones added in later versions (`caps = null`,
     * the same grant as "Allow all capabilities" on the agent's page). Logged like that dialog.
     */
    fun grantAll(agentId: String) {
        val old = app.agents.get(agentId) ?: return
        if (old.caps == null) return
        app.agents.update(agentId) { it.copy(caps = null) }
        app.log.system("audit", "agent", "Owner changed ${old.name}'s grants: ${old.caps.sorted().joinToString(",")} -> all")
    }

    /** Maximum powers for an agent: it may use everything and every switch is on. */
    fun isMaximum(agentId: String): Boolean = app.agents.get(agentId)?.caps == null && everySwitchOn()

    companion object {
        private val STORES = setOf("com.android.vending", "com.google.android.feedback")
        private val TITLES = mapOf(
            "notifications" to R.string.access_notifications, "camera" to R.string.access_camera, "mic" to R.string.access_mic,
            "photos" to R.string.access_photos, "location" to R.string.power_location, "background_location" to R.string.power_background_location,
            "activity" to R.string.power_activity, "health" to R.string.power_health, "battery" to R.string.access_battery,
            "usage" to R.string.power_usage, "write_settings" to R.string.power_write_settings, "dnd" to R.string.power_dnd,
            "installs" to R.string.access_installs, PowerPlan.RESTRICTED to R.string.power_restricted,
        )
        private val UNLOCKS = mapOf(
            "notifications" to R.string.access_notifications_unlocks, "camera" to R.string.access_camera_unlocks,
            "mic" to R.string.access_mic_unlocks, "photos" to R.string.access_photos_unlocks, "location" to R.string.power_location_unlocks,
            "background_location" to R.string.power_location_unlocks, "activity" to R.string.power_activity_unlocks,
            "health" to R.string.power_health_unlocks, "battery" to R.string.access_battery_unlocks, "usage" to R.string.power_usage_unlocks,
            "write_settings" to R.string.power_write_settings_unlocks, "dnd" to R.string.power_dnd_unlocks,
            "installs" to R.string.power_installs_unlocks, PowerPlan.RESTRICTED to R.string.power_restricted_unlocks,
        )
    }
}
