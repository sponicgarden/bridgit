package com.almusca.devicebridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private val app get() = application as BridgeApp
    private val policy get() = app.policy
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var listenerSwitch: MaterialSwitch
    private val capSwitches = mutableMapOf<Policy.Capability, MaterialSwitch>()
    private lateinit var permStatus: TextView
    private lateinit var settingsButton: Button
    private lateinit var batteryStatus: TextView
    private lateinit var batteryButton: Button
    private lateinit var pairButton: Button
    private lateinit var rotateButton: Button
    private lateinit var unpairButton: Button
    private lateinit var pairPanel: LinearLayout
    private lateinit var pairCode: TextView
    private lateinit var pairToken: TextView
    private lateinit var pairQr: ImageView
    private lateinit var pairCountdown: TextView
    private lateinit var auditView: TextView
    private var binding = false

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("asked_perms", true).apply()
        app.audit.event("permissions", it.entries.joinToString { (k, v) -> "${k.substringAfterLast('.')}=${if (v) "granted" else "denied"}" })
        // Re-enter the foreground with the newly granted camera/mic types while we are visible.
        if (policy.listenerEnabled) BridgeService.start(this)
        refresh()
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        if (uri != null) {
            contentResolver.openOutputStream(uri)?.use { it.write(app.audit.exportText().toByteArray()) }
            app.audit.event("audit_export", "exported to a file chosen on the phone")
            refresh()
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = refresh()
    }

    private val ticker = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(stateReceiver, IntentFilter(BridgeService.ACTION_STATE), RECEIVER_NOT_EXPORTED)
        // Opening the app (re)starts the listener with full camera/mic access - this is how
        // it comes back after a reboot or after Android stopped it.
        if (policy.listenerEnabled) BridgeService.start(this)
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(stateReceiver)
        ui.removeCallbacks(ticker)
    }

    // ---- actions ---------------------------------------------------------

    private fun setListener(on: Boolean) {
        if (on && !policy.isPaired) {
            toastDialog("Pair the agent first", "Tap \"Pair agent\". Pairing turns the listener on.")
            refresh(); return
        }
        policy.listenerEnabled = on
        app.audit.event("listener", if (on) "switched on" else "switched off")
        if (on) BridgeService.start(this) else BridgeService.stop(this)
        refresh()
    }

    private fun setCapability(c: Policy.Capability, on: Boolean) {
        if (policy.isEnabled(c) == on) return
        policy.setEnabled(c, on)
        app.audit.event("toggle", "${c.key} ${if (on) "on" else "off"}")
        refresh()
    }

    private fun startPairing(rotate: Boolean) {
        val token = policy.issueToken()
        app.pairing.open(token)
        app.audit.event("pairing", if (rotate) "token rotated; old token invalid" else "new pairing; token issued")
        policy.listenerEnabled = true
        BridgeService.start(this)
        refresh()
    }

    private fun unpair() {
        AlertDialog.Builder(this)
            .setTitle("Unpair the agent?")
            .setMessage("Deletes the token and stops the listener. No request can succeed until you pair again here on the phone.")
            .setPositiveButton("Unpair") { _, _ ->
                policy.unpair(); app.pairing.close()
                policy.listenerEnabled = false
                BridgeService.stop(this)
                app.audit.event("pairing", "unpaired; token deleted")
                refresh()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun clearAudit() {
        AlertDialog.Builder(this)
            .setTitle("Clear the audit log?")
            .setMessage("The clear itself is recorded as the first entry of the new log.")
            .setPositiveButton("Clear") { _, _ -> app.audit.clear(); refresh() }
            .setNegativeButton("Cancel", null).show()
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    // ---- state -----------------------------------------------------------

    private fun refresh() {
        val svc = BridgeService.running
        val addr = svc?.bindAddress
        val ts = BridgeService.tailscaleAddress()
        status.text = buildString {
            append("Listener: ")
            append(when {
                !policy.listenerEnabled -> "off"
                svc == null -> "starting..."
                addr != null -> "on - $addr:${policy.port}"
                else -> "on, waiting for Tailscale"
            })
            append("\nTailscale: ${ts ?: "not running"}")
            append("\nPaired: ${if (policy.isPaired) "yes" else "no"}")
            if (svc != null) {
                append("\nCamera access in service: ${if (svc.cameraReady) "yes" else "no"}")
                append("\nMicrophone access in service: ${if (svc.micReady) "yes" else "no"}")
            }
        }
        binding = true
        listenerSwitch.isChecked = policy.listenerEnabled
        capSwitches.forEach { (c, s) -> s.isChecked = policy.isEnabled(c) }
        binding = false

        val gallery = Gallery(this).accessMode()
        val perms = listOf(
            "Notifications" to granted(Manifest.permission.POST_NOTIFICATIONS),
            "Camera" to granted(Manifest.permission.CAMERA),
            "Microphone" to granted(Manifest.permission.RECORD_AUDIO),
        )
        permStatus.text = perms.joinToString("\n") { (n, g) -> "$n: ${if (g) "granted" else "not granted"}" } +
            "\nPhotos: ${when (gallery) { "full" -> "full access"; "selected" -> "selected photos only"; else -> "not granted" }}"
        val asked = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("asked_perms", false)
        val blocked = asked && listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_MEDIA_IMAGES)
            .any { !granted(it) && !shouldShowRequestPermissionRationale(it) }
        settingsButton.visibility = if (blocked || gallery == "selected") View.VISIBLE else View.GONE

        val pm = getSystemService(PowerManager::class.java)
        val exempt = pm.isIgnoringBatteryOptimizations(packageName)
        batteryStatus.text = "Battery optimisation: ${if (exempt) "exempt (listener stays reachable)" else "on (Android may pause the listener)"}"
        batteryButton.visibility = if (exempt) View.GONE else View.VISIBLE

        pairButton.visibility = if (policy.isPaired) View.GONE else View.VISIBLE
        rotateButton.visibility = if (policy.isPaired) View.VISIBLE else View.GONE
        unpairButton.visibility = if (policy.isPaired) View.VISIBLE else View.GONE

        val w = app.pairing.window
        if (w != null && app.pairing.isOpen()) {
            pairPanel.visibility = View.VISIBLE
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            pairCode.text = w.code
            if (pairToken.text.toString() != w.token) {
                pairToken.text = w.token
                val payload = JSONObject().put("base_url", "http://${addr ?: ts ?: "?"}:${policy.port}/v1")
                    .put("token", w.token).toString()
                pairQr.setImageBitmap(qr(payload))
            }
            val left = ((w.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            pairCountdown.text = "Pairing window closes in ${left / 60}:${"%02d".format(left % 60)}. " +
                "The agent calls POST /v1/pair with this code, or scan the QR / copy the token."
        } else {
            if (pairPanel.visibility == View.VISIBLE) {
                app.pairing.close()
                pairToken.text = ""; pairQr.setImageBitmap(null)
            }
            pairPanel.visibility = View.GONE
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        auditView.text = app.audit.recent(50).joinToString("\n\n") { e ->
            val head = "${e.optString("ts")}  ${e.optString("method")} ${e.optString("endpoint")}".replace("  ", " ")
            val res = "${e.optString("result")}${if (e.has("http")) " (${e.optInt("http")})" else ""}"
            val extra = listOfNotNull(
                e.optString("source").takeIf { it.isNotEmpty() }?.let { "from $it" },
                e.optJSONObject("params")?.takeIf { it.length() > 0 }?.toString(),
                e.optLong("bytes").takeIf { it > 0 }?.let { "$it bytes" },
                e.optString("detail").takeIf { it.isNotEmpty() },
                e.optString("request_id").takeIf { it.isNotEmpty() }?.let { "id $it" },
            ).joinToString(" - ")
            "$head\n$res${if (extra.isNotEmpty()) " - $extra" else ""}"
        }.ifEmpty { "No entries yet." }
    }

    // ---- UI construction ----------------------------------------------------

    private fun buildUi(): View {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(16), px(20), px(32))
        }
        fun heading(t: String) = TextView(this).apply {
            text = t; textSize = 18f; setTypeface(typeface, Typeface.BOLD)
            setPadding(0, px(24), 0, px(6))
        }.also { root.addView(it) }
        fun body(t: String = "") = TextView(this).apply { text = t; textSize = 14f; setLineSpacing(0f, 1.15f) }
            .also { root.addView(it) }
        fun button(t: String, onClick: () -> Unit) = MaterialButton(this).apply {
            text = t; setOnClickListener { onClick() }
        }.also { root.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) }) }
        fun switch(t: String, onChange: (Boolean) -> Unit) = MaterialSwitch(this).apply {
            text = t; textSize = 16f
            setOnCheckedChangeListener { _, on -> if (!binding) onChange(on) }
        }.also { root.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4) }) }

        root.addView(TextView(this).apply { text = "Almusca Device Bridge"; textSize = 24f; setTypeface(typeface, Typeface.BOLD) })
        body("Lets your agent take a photo, record a short clip or fetch a gallery photo over Tailscale. " +
            "Every capture shows a notification and Android's green indicator, and is logged below.")

        heading("Status")
        status = body()
        listenerSwitch = switch("Listener (always on)") { setListener(it) }

        heading("Capabilities")
        body("Each starts off. Turning one off applies from the next request.")
        capSwitches[Policy.Capability.CAMERA] = switch("Camera snapshot") { setCapability(Policy.Capability.CAMERA, it) }
        capSwitches[Policy.Capability.MIC] = switch("Microphone clip (max 120 s)") { setCapability(Policy.Capability.MIC, it) }
        capSwitches[Policy.Capability.GALLERY] = switch("Gallery fetch (read-only)") { setCapability(Policy.Capability.GALLERY, it) }

        heading("Permissions")
        permStatus = body()
        button("Grant permissions") {
            permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        }
        settingsButton = button("Open app settings") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        batteryStatus = body()
        batteryButton = button("Allow listener to run in background") { requestBatteryExemption() }

        heading("Pairing")
        pairButton = button("Pair agent") { startPairing(rotate = false) }
        rotateButton = button("Rotate token") {
            AlertDialog.Builder(this).setTitle("Rotate the token?")
                .setMessage("The current token stops working immediately. A new pairing window opens so the agent can fetch the new one.")
                .setPositiveButton("Rotate") { _, _ -> startPairing(rotate = true) }
                .setNegativeButton("Cancel", null).show()
        }
        unpairButton = button("Unpair") { unpair() }
        pairPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A0F5F57"))
            setPadding(px(16), px(16), px(16), px(16))
        }
        pairPanel.addView(TextView(this).apply { text = "Pairing code"; textSize = 14f })
        pairCode = TextView(this).apply { textSize = 40f; typeface = Typeface.MONOSPACE; letterSpacing = 0.15f }
        pairPanel.addView(pairCode)
        pairPanel.addView(TextView(this).apply { text = "Token (shown once)"; textSize = 14f; setPadding(0, px(12), 0, 0) })
        pairToken = TextView(this).apply { textSize = 13f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        pairPanel.addView(pairToken)
        pairQr = ImageView(this)
        pairPanel.addView(pairQr, LinearLayout.LayoutParams(px(240), px(240)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = px(12) })
        pairCountdown = TextView(this).apply { textSize = 13f }
        pairPanel.addView(pairCountdown)
        pairPanel.addView(MaterialButton(this).apply { text = "Done - hide token"; setOnClickListener { app.pairing.close(); refresh() } })
        root.addView(pairPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) })

        heading("Audit log (newest first)")
        button("Export log") { exportLauncher.launch("device-bridge-audit.jsonl") }
        button("Clear log") { clearAudit() }
        auditView = body().apply { textSize = 12f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }

        val scroll = ScrollView(this).apply { addView(root) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom); insets
        }
        return scroll
    }

    private fun qr(text: String): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 600, 600)
        val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565)
        for (x in 0 until m.width) for (y in 0 until m.height) bmp.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
        return bmp
    }

    private fun toastDialog(title: String, msg: String) =
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
}
