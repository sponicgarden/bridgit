package cc.bridgit.app.adb

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.capability.ScreenshotService
import cc.bridgit.app.planned.NotificationMemory
import cc.bridgit.app.planned.NotificationReaderService
import cc.bridgit.app.planned.UsageModule
import org.json.JSONArray
import org.json.JSONObject

/**
 * ADB setup (8.26): everything Bridgit can be given from the shell, in the order it runs.
 * Each grant has its shell commands and a check Bridgit can make without ADB, so the wizard and
 * GET /v1/adb/status show what actually took effect, not what was attempted.
 */
object AdbGrants {
    class Grant(val key: String, val title: String, val unlocks: String, val commands: (Context) -> List<String>, val check: (Context) -> Boolean?)

    private fun pkg(c: Context) = c.packageName
    private fun op(c: Context, op: String) = runCatching {
        c.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(op, Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrNull()

    private fun remembered(c: Context, key: String) = c.getSharedPreferences("adb", Context.MODE_PRIVATE).getBoolean("granted_$key", false)

    fun holdsSecureSettings(c: Context) = granted(c, Manifest.permission.WRITE_SECURE_SETTINGS)
    private fun granted(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    /** Runtime permissions in the manifest. `pm grant` gives them all without a dialog each. */
    fun runtimePermissions(c: Context): List<String> = runCatching {
        val pm = c.packageManager
        pm.getPackageInfo(c.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().filter { p ->
            runCatching { pm.getPermissionInfo(p, 0).protection == PermissionInfo.PROTECTION_DANGEROUS }.getOrDefault(false)
        }
    }.getOrDefault(emptyList())

    private fun accessibilityList(c: Context): String {
        val me = ComponentName(c, ScreenshotService::class.java).flattenToString()
        val now = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return (now.split(':').filter { it.isNotBlank() && it != me } + me).joinToString(":")
    }

    val all = listOf(
        Grant("restricted_settings", "Allow restricted settings",
            "Android blocks some access for apps installed from a file. This lifts the block for Bridgit.",
            { listOf("appops set ${pkg(it)} ACCESS_RESTRICTED_SETTINGS allow") },
            // Android 16 doesn't let apps read this op back, so a grant that ran cleanly is remembered.
            { op(it, "android:access_restricted_settings") ?: remembered(it, "restricted_settings") }),
        Grant("secure_settings", "Change secure settings",
            "Lets Bridgit turn Wireless debugging and its own access back on after a reboot, without a computer.",
            { listOf("pm grant ${pkg(it)} ${Manifest.permission.WRITE_SECURE_SETTINGS}") },
            { holdsSecureSettings(it) }),
        Grant("permissions", "Every app permission",
            "Camera, microphone, photos and videos, location, notifications, activity and health reads, with no dialog for each.",
            { c -> runtimePermissions(c).map { "pm grant ${pkg(c)} $it" } },
            { c -> runtimePermissions(c).filter { !it.startsWith("android.permission.health.") }.all { granted(c, it) } }),
        Grant("accessibility", "Accessibility (screenshots)",
            "Turns on Bridgit's screenshot service, so agents can see the screen.",
            { c -> listOf("settings put secure enabled_accessibility_services '${accessibilityList(c)}'", "settings put secure accessibility_enabled 1") },
            { ScreenshotService.isEnabled(it) }),
        Grant("notification_listener", "Notification access",
            "Lets agents read the notifications on the phone.",
            { c -> listOf("cmd notification allow_listener ${ComponentName(c, NotificationReaderService::class.java).flattenToString()}") },
            { NotificationMemory.allowed(it) }),
        Grant("dnd", "Do Not Disturb access",
            "Lets agents change Do Not Disturb.",
            { listOf("cmd notification allow_dnd ${pkg(it)}") },
            { it.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted }),
        Grant("usage", "Usage access",
            "Lets agents see which apps were used and when.",
            { listOf("appops set ${pkg(it)} GET_USAGE_STATS allow") },
            { UsageModule.usageAllowed(it) }),
        Grant("write_settings", "Modify system settings",
            "Lets agents change brightness and sound.",
            { listOf("appops set ${pkg(it)} WRITE_SETTINGS allow") },
            { Settings.System.canWrite(it) }),
        Grant("overlay", "Display over other apps",
            "Lets Bridgit open apps and links while it's in the background.",
            { listOf("appops set ${pkg(it)} SYSTEM_ALERT_WINDOW allow") },
            { Settings.canDrawOverlays(it) }),
        Grant("screen_capture", "Screen sharing without the prompt",
            "Android stops asking before each screen-sharing session an agent starts.",
            { listOf("appops set ${pkg(it)} PROJECT_MEDIA allow") },
            { op(it, "android:project_media") }),
        Grant("installs", "Install updates",
            "Lets Bridgit install its own updates.",
            { listOf("appops set ${pkg(it)} REQUEST_INSTALL_PACKAGES allow") },
            { it.packageManager.canRequestPackageInstalls() }),
        Grant("battery", "Always run in the background",
            "No battery optimisation, never put to sleep, so agents can always reach the phone.",
            { listOf("dumpsys deviceidle whitelist +${pkg(it)}", "cmd appops set ${pkg(it)} RUN_ANY_IN_BACKGROUND allow", "am set-standby-bucket ${pkg(it)} active") },
            { it.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(it.packageName) }),
    )

    fun status(c: Context): JSONArray = JSONArray().apply {
        all.forEach { g -> put(JSONObject().put("key", g.key).put("title", g.title).put("unlocks", g.unlocks).put("granted", g.check(c) ?: JSONObject.NULL)) }
    }

    /** Runs every grant (or just [only]) and returns one line per command, for the wizard and the log. */
    suspend fun grantAll(app: BridgeApp, only: Set<String>? = null, progress: (String) -> Unit = {}): List<String> {
        val lines = mutableListOf<String>()
        for (g in all) {
            if (only != null && g.key !in only) continue
            progress(g.title)
            var allOk = true
            for (cmd in g.commands(app)) {
                val r = runCatching { app.adb.shell(cmd, timeoutMs = 15_000) }
                val ok = r.getOrNull()?.exitCode == 0
                allOk = allOk && ok
                lines += r.fold({ res -> if (ok) "ok   $cmd" else "fail $cmd: ${res.output.trim().lineSequence().firstOrNull().orEmpty()}" },
                    { e -> "fail $cmd: ${e.message}" })
            }
            app.getSharedPreferences("adb", Context.MODE_PRIVATE).edit().putBoolean("granted_${g.key}", allOk).apply()
        }
        app.log.system("audit", "setting", "ADB: granted access (${lines.count { it.startsWith("ok") }} of ${lines.size} commands worked)")
        return lines
    }
}
