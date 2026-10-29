package net.finleg.bridget

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
    private lateinit var interactionBrand: LinearLayout
    private var interactionSig = ""
    private lateinit var agentsBox: LinearLayout
    private var agentsSig = ""
    private lateinit var pairTitle: TextView
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
        if (on && !policy.hasAgents) {
            toastDialog("Add an agent first", "Tap \"Add agent\". Adding one turns the listener on.")
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

    private fun addAgent() {
        val input = android.widget.EditText(this).apply {
            hint = "Name, e.g. Muse or Grok"; isSingleLine = true
        }
        val box = LinearLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0); addView(input)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Add agent")
            .setMessage("Each agent gets its own key, so you can see who asked and remove one without affecting the others.")
            .setView(box)
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                val err = policy.checkName(name)
                if (err != null) { input.error = err; return@setOnClickListener }
                val (agent, token) = policy.addAgent(name)
                app.audit.event("pairing", "added agent ${agent.name}; key issued")
                openPairing(agent, token)
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    private fun openPairing(agent: Policy.Agent, token: String) {
        app.pairing.open(agent.id, agent.name, token)
        policy.listenerEnabled = true
        BridgeService.start(this)
        refresh()
    }

    private fun rotateAgent(a: Policy.Agent) {
        AlertDialog.Builder(this).setCustomTitle(agentIdentity(a))
            .setMessage("Rotate key? ${a.name}'s current key stops working immediately. A pairing window opens so ${a.name} can fetch the new one. Other agents are not affected.")
            .setPositiveButton("Rotate") { _, _ ->
                val (agent, token) = policy.rotate(a.id)
                app.audit.event("pairing", "rotated key for ${a.name}; old key invalid")
                openPairing(agent, token)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun removeAgent(a: Policy.Agent) {
        AlertDialog.Builder(this)
            .setCustomTitle(agentIdentity(a))
            .setMessage("Remove agent? Deletes ${a.name}'s key. Removing the last agent also stops the listener.")
            .setPositiveButton("Remove") { _, _ ->
                policy.remove(a.id)
                if (app.pairing.window?.agentId == a.id) app.pairing.close()
                app.audit.event("pairing", "removed agent ${a.name}; key deleted")
                if (!policy.hasAgents) {
                    policy.listenerEnabled = false
                    BridgeService.stop(this)
                }
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
            append("\nAgents: ${policy.agents().size}")
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

        renderAgents()
        val active = policy.agents().firstOrNull { it.id == svc?.captureAgentId }
        val activeSig = active?.let { "${it.id}:${it.name}:${it.iconBase64}:${it.wordmarkBase64}" } ?: ""
        if (interactionSig != activeSig) {
            interactionSig = activeSig
            interactionBrand.removeAllViews()
            active?.let { interactionBrand.addView(agentIdentity(it)) }
        }
        interactionBrand.visibility = if (active == null) View.GONE else View.VISIBLE

        val w = app.pairing.window
        if (w != null && app.pairing.isOpen()) {
            pairPanel.visibility = View.VISIBLE
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            pairTitle.text = "Pairing code for ${w.agentName}"
            pairCode.text = w.code
            if (pairToken.text.toString() != w.token) {
                pairToken.text = w.token
                val payload = JSONObject().put("app", "bridget").put("agent", w.agentName)
                    .put("base_url", "http://${addr ?: ts ?: "?"}:${policy.port}/v1")
                    .put("token", w.token).toString()
                pairQr.setImageBitmap(qr(payload))
            }
            val left = ((w.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            pairCountdown.text = "Pairing window closes in ${left / 60}:${"%02d".format(left % 60)}. " +
                "${w.agentName} calls POST /v1/pair with this code, or scan the QR / copy the token."
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

        interactionBrand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        root.addView(TextView(this).apply { text = "BRIDGIT"; textSize = 28f; setTypeface(typeface, Typeface.BOLD) })
        body("Device Manager for Android")
        body("Turn any Android device into an agentic appliance.")
        body("Lets your AI agents take a photo, record a short clip or fetch a gallery photo over Tailscale. " +
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

        heading("Agents")
        body("Each agent has its own key. Every request is logged with the agent's name.")
        agentsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(agentsBox)
        button("Add agent") { addAgent() }
        pairPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A0F5F57"))
            setPadding(px(16), px(16), px(16), px(16))
        }
        pairTitle = TextView(this).apply { text = "Pairing code"; textSize = 14f }
        pairPanel.addView(pairTitle)
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
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(interactionBrand, LinearLayout.LayoutParams(-1, -2))
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom); insets
        }
        return frame
    }

    private fun renderAgents() {
        val list = policy.agents()
        val sig = list.joinToString { "${it.id}:${it.name}:${it.iconBase64}:${it.wordmarkBase64}:${it.lastUsedAt / 60000}" }
        if (sig == agentsSig) return
        agentsSig = sig
        agentsBox.removeAllViews()
        val dp = resources.displayMetrics.density
        if (list.isEmpty()) {
            agentsBox.addView(TextView(this).apply { text = "No agents yet."; textSize = 14f })
            return
        }
        val fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        for (a in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            }
            val identity = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            identity.addView(agentIdentity(a))
            identity.addView(TextView(this).apply {
                textSize = 12f
                text = if (a.lastUsedAt > 0) "last used ${fmt.format(java.util.Date(a.lastUsedAt))}" else "not used yet"
            })
            row.addView(identity, LinearLayout.LayoutParams(-1, -2))
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(actions)
            actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Rotate key"; setOnClickListener { rotateAgent(a) }
            })
            actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Remove"; setOnClickListener { removeAgent(a) }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (6 * dp).toInt() })
            agentsBox.addView(row)
        }
    }

    /** Shared identity treatment for the interaction header, agent list and confirmations. */
    private fun agentIdentity(agent: Policy.Agent): LinearLayout {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), px(8), px(8), px(8))
            AgentBranding.bitmap(agent.iconBase64)?.let { bitmap ->
                addView(ImageView(context).apply {
                    setImageBitmap(bitmap); contentDescription = "${agent.name} icon"
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(px(40), px(40)).apply { marginEnd = px(8) })
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                AgentBranding.bitmap(agent.wordmarkBase64)?.let { bitmap ->
                    addView(ImageView(context).apply {
                        setImageBitmap(bitmap); contentDescription = "${agent.name} wordmark"
                        scaleType = ImageView.ScaleType.FIT_START
                        adjustViewBounds = true
                    }, LinearLayout.LayoutParams(-1, px(32)))
                }
                addView(TextView(context).apply {
                    text = agent.name; textSize = 16f; setTypeface(typeface, Typeface.BOLD)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
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
