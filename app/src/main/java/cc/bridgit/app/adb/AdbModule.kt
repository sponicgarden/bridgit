package cc.bridgit.app.adb

import io.ktor.server.routing.Route
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Param
import cc.bridgit.app.api.Reply
import cc.bridgit.app.capability.Capability
import org.json.JSONArray
import org.json.JSONObject

/**
 * ADB control (8.26). The owner pairs Bridgit with Wireless debugging in ADB setup; agents with the Developer
 * switch can then run shell commands as Android's shell user. Off by default, and every command is logged in full.
 */
class AdbModule(app: BridgeApp) : Capability(app) {
    override val id = "adb"
    override val titleRes = R.string.cap_adb
    override val descriptionRes = R.string.cap_adb_desc
    override val group = "controls"
    override val switchKey = "adb"
    override val endpoints = ENDPOINTS

    override fun blockedReason(): String? = when {
        !AdbClient.developerOptions(app) -> "developer_options_off"
        !AdbClient.wirelessDebugging(app) && !app.adb.connected && !AdbGrants.holdsSecureSettings(app) -> "wireless_debugging_off"
        !app.adb.paired -> "adb_not_paired"
        else -> null
    }

    override fun details(): JSONObject = statusJson()

    fun statusJson(): JSONObject = JSONObject()
        .put("developer_options", AdbClient.developerOptions(app))
        .put("usb_debugging", AdbClient.usbDebugging(app))
        .put("wireless_debugging", AdbClient.wirelessDebugging(app))
        .put("paired", app.adb.paired)
        .put("connected", app.adb.connected)
        .put("shell_switch", enabled)
        .put("grants", AdbGrants.status(app))
        .put("setup_guide", "/v1/adb/setup")

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(SETUP) { Reply.Text(setupGuide(), "text/markdown; charset=utf-8") }
        endpoint(STATUS) { Reply.Json(statusJson().put("ready", blockedReason() == null).put("blocked_reason", blockedReason() ?: JSONObject.NULL)) }
        endpoint(SHELL) {
            usable()
            val command = body.optString("command")
            if (command.isBlank() || command.length > 8000) throw ErrorCatalog.error("invalid_parameter", "command must be 1-8000 characters.")
            val timeout = body.optInt("timeout_seconds", 30)
            if (timeout !in 1..120) throw ErrorCatalog.error("invalid_parameter", "timeout_seconds must be 1-120.")
            val r = runCatching { app.adb.shell(command, timeout * 1000L) }.getOrElse { throw notConnected(it) }
            message = "adb shell: ${command.take(200)}"
            Reply.Json(JSONObject().put("exit_code", r.exitCode ?: JSONObject.NULL).put("output", r.output)
                .put("truncated", r.truncated).put("timed_out", r.timedOut))
        }
        endpoint(GRANT) {
            usable()
            val only = body.optJSONArray("only")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            val lines = runCatching { AdbGrants.grantAll(app, only) }.getOrElse { throw notConnected(it) }
            Reply.Json(JSONObject().put("results", JSONArray(lines)).put("grants", AdbGrants.status(app)))
        }
    }

    private fun usable() {
        if (!enabled) throw ErrorCatalog.error("capability_disabled",
            "ADB control is switched off. Ask the owner to turn it on in Bridgit: More → ADB setup → Let agents use ADB.", mapOf("setting" to switchKey))
        // Not paired yet is no reason to refuse: an allowed `adb tcpip` port connects without pairing.
        blockedReason()?.takeIf { it != "adb_not_paired" }?.let { throw ErrorCatalog.error("service_start_blocked",
            "ADB isn't set up yet ($it). Read GET /v1/adb/setup and walk the owner through it.", mapOf("blocked_reason" to it)) }
    }

    private fun notConnected(e: Throwable) = ErrorCatalog.error("service_start_blocked",
        if (e is io.github.muntashirakon.adb.AdbPairingRequiredException)
            "Bridgit isn't paired with Wireless debugging yet. Ask the owner to tap Pair in Bridgit → More → ADB setup (GET /v1/adb/setup has the steps)."
        else "Bridgit couldn't reach ADB: ${e.message ?: e.javaClass.simpleName}. GET /v1/adb/status shows what's missing.",
        mapOf("blocked_reason" to "adb_not_connected"))

    /** GET /v1/adb/setup: how an agent helps the owner turn ADB on, and what to do once it's on. No key needed. */
    fun setupGuide(): String = buildString {
        val s = statusJson()
        appendLine("# Bridgit ADB setup")
        appendLine()
        appendLine("Bridgit can run ADB shell commands on this phone by itself (it pairs with the phone's own Wireless debugging; no computer needed).")
        appendLine("With it you can tap, type, swipe, open any app or screen, read the UI tree, take screenshots, change settings and more.")
        appendLine()
        appendLine("## Right now")
        appendLine("- Developer options: ${onOff(s.getBoolean("developer_options"))}")
        appendLine("- Wireless debugging: ${onOff(s.getBoolean("wireless_debugging"))}")
        appendLine("- Bridgit paired: ${if (s.getBoolean("paired")) "yes" else "no"}")
        appendLine("- Agents may use ADB (switch): ${onOff(s.getBoolean("shell_switch"))}")
        appendLine()
        appendLine("## Walk the owner through it")
        appendLine("Give one step at a time and wait for them. Everything happens on the phone. Bridgit's own screen is **More → ADB setup**;")
        appendLine("it shows each step's status live and has a button that opens the right Settings page.")
        appendLine()
        appendLine("1. **Developer options.** Settings → About phone → (Samsung: Software information) → tap **Build number** 7 times, then enter the PIN.")
        appendLine("2. **Wireless debugging.** Settings → System → Developer options (Samsung: Settings → Developer options) → turn on **Wireless debugging** → Allow.")
        appendLine("   The phone must be on Wi-Fi. Tick \"Always allow on this network\" if asked.")
        appendLine("3. **Pair Bridgit.** In Bridgit's ADB setup tap **Pair**. Bridgit posts a notification. In Wireless debugging tap **Pair device with pairing code**,")
        appendLine("   then pull down the notification shade, tap **Enter code** on Bridgit's notification and type the 6-digit code. Keep the pairing dialog open meanwhile.")
        appendLine("4. **Give Bridgit access.** In ADB setup tap **Give Bridgit full access**. (Or, once steps 5-6 are done, call `POST /v1/adb/grant`.)")
        appendLine("5. **Let agents use ADB.** In ADB setup turn on **Let agents use ADB**.")
        appendLine("6. **Developer switch for you.** Bridgit → Agents → your agent → turn on **Developer**. Only developer agents may run shell commands.")
        appendLine()
        appendLine("Check progress any time with `GET /v1/adb/status` (`blocked_reason` names the step that's missing).")
        appendLine()
        appendLine("## Once ADB is on")
        appendLine("1. `POST /v1/adb/grant` – grants Bridgit every permission and access that helps you (runtime permissions, accessibility,")
        appendLine("   notification and usage access, Do Not Disturb, overlay, screen capture without a prompt, no battery limits, and")
        appendLine("   WRITE_SECURE_SETTINGS so Bridgit can turn Wireless debugging back on after a reboot). Check `grants` in the reply.")
        appendLine("2. `POST /v1/adb/shell {\"command\":\"...\",\"timeout_seconds\":30}` – runs as uid shell; returns exit_code and output (stdout+stderr, 256 KB max).")
        appendLine()
        appendLine("Useful commands:")
        appendLine("- `input tap X Y`, `input swipe X1 Y1 X2 Y2 300`, `input text 'hello'`, `input keyevent KEYCODE_HOME` (BACK, ENTER, WAKEUP, …)")
        appendLine("- `uiautomator dump /sdcard/ui.xml && cat /sdcard/ui.xml` – the UI tree with text and bounds, to find what to tap")
        appendLine("- `screencap -p /sdcard/s.png` then `base64 /sdcard/s.png` – a screenshot (or use Bridgit's screenshot endpoints)")
        appendLine("- `am start -a android.intent.action.VIEW -d https://example.com`, `monkey -p <package> 1` – open a link or app")
        appendLine("- `dumpsys activity activities | grep -m1 ResumedActivity` – which app is in front")
        appendLine("- `pm list packages -3`, `settings get|put system|secure|global <key> [value]`, `cmd wifi status`, `dumpsys battery`")
        appendLine()
        appendLine("## Rules")
        appendLine("1. Ask the owner first before anything that loses data or is hard to undo: uninstalling, clearing app data, factory reset, deleting files, sending messages or paying.")
        appendLine("2. Never turn off Wireless debugging, Developer options, or Bridgit's own access; never force-stop or uninstall Bridgit.")
        appendLine("3. Every command you run is logged on the phone with your agent name, and the owner can switch ADB off at any time.")
    }

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    companion object {
        val SETUP = EndpointSpec("GET", "/adb/setup", "adb", "How to help the owner turn on ADB for Bridgit, and what to do once it's on (Markdown)",
            auth = false, example = "GET /v1/adb/setup", returns = "text/markdown")
        val STATUS = EndpointSpec("GET", "/adb/status", "adb", "ADB setup progress: developer options, wireless debugging, paired, grants",
            example = "GET /v1/adb/status", returns = "developer_options, usb_debugging, wireless_debugging, paired, connected, shell_switch, ready, blocked_reason, grants[key, title, granted]")
        val SHELL = EndpointSpec("POST", "/adb/shell", "adb", "Run a shell command as Android's shell user (developer agents only)",
            developerOnly = true,
            body = listOf(Param("command", "string", "Shell command, 1-8000 characters", required = true),
                Param("timeout_seconds", "integer", "1-120, default 30")),
            example = "POST /v1/adb/shell {\"command\":\"input keyevent KEYCODE_HOME\"}", returns = "exit_code, output, truncated, timed_out",
            errors = listOf("capability_disabled", "service_start_blocked", "forbidden", "invalid_parameter"))
        val GRANT = EndpointSpec("POST", "/adb/grant", "adb", "Give Bridgit every useful permission and access through ADB (developer agents only)",
            developerOnly = true, body = listOf(Param("only", "array", "Grant keys to run (default all); see grants in /v1/adb/status")),
            example = "POST /v1/adb/grant {}", returns = "results[], grants[key, title, granted]",
            errors = listOf("capability_disabled", "service_start_blocked", "forbidden"))
        val ENDPOINTS = listOf(SETUP, STATUS, SHELL, GRANT)
    }
}
