package cc.bridgit.app.planned

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import org.json.JSONObject

/** Owner-only actions, reached from Features or a notification. No remote intent may invoke an approval. */
class OwnerToolsActivity : AppCompatActivity() {
    private val app get() = application as BridgeApp
    private lateinit var list: LinearLayout
    private var projectionRequest: JSONObject? = null
    private val projectionConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request = projectionRequest; projectionRequest = null
        if (request != null) {
            if (result.resultCode == RESULT_OK && result.data != null) {
                val bounds = getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
                startForegroundService(Intent(this, ProjectionService::class.java).putExtra("agent_id", request.getString("agent_id"))
                    .putExtra("grant_id", request.getString("id")).putExtra("data", result.data).putExtra("width", bounds.width()).putExtra("height", bounds.height()))
            } else app.screenGrants.decide(request.getString("id"), "denied")
        }
        render()
    }
    private var exporting: JSONObject? = null
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val o = exporting; exporting = null
        if (uri != null && o != null) lifecycleScope.launch(Dispatchers.IO) { runCatching { contentResolver.openOutputStream(uri)?.use { out -> app.transfers.file(o).inputStream().use { it.copyTo(out) } } } }
    }
    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) chooseAgent("downloads", uri, null) }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { render() }
    override fun onCreate(s: Bundle?) { super.onCreate(s)
        projectionRequest = s?.getString("projection_request")?.let { id -> app.screenGrants.all().firstOrNull { it.optString("id") == id } }
        exporting = s?.getString("exporting")?.let { id -> runCatching { app.transfers.get(id) }.getOrNull() }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 48) }; setContentView(ScrollView(this).apply {
            addView(list)
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
            }
        }) }
    override fun onSaveInstanceState(out: Bundle) {
        projectionRequest?.let { out.putString("projection_request", it.getString("id")) }
        exporting?.let { out.putString("exporting", it.getString("id")) }
        super.onSaveInstanceState(out)
    }
    override fun onResume() { super.onResume(); render() }
    private fun text(s: String) { list.addView(TextView(this).apply { text = s; textSize = 16f; setPadding(0, 16, 0, 8) }) }
    private fun button(s: String, action: () -> Unit) { list.addView(Button(this).apply { text = s; setOnClickListener { runCatching(action).onFailure { Toast.makeText(this@OwnerToolsActivity, it.message ?: "Action failed", Toast.LENGTH_LONG).show() } } }) }
    private fun open(action: String, data: Uri? = null) { runCatching { startActivity(Intent(action, data)) }.onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } }
    private fun render() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        text("Device tools")
        button("Back") { finish() }
        button("Refresh requests and files") { render() }
        text("Special access — grant only what you want to use; feature switches still apply.")
        button("Do Not Disturb access") { open(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }
        button("Modify system settings") { open(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")) }
        button("Usage access (${if (UsageModule.usageAllowed(this)) "on" else "off"})") { open(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")) }
        button("Notification access (${if (NotificationMemory.allowed(this)) "on" else "off"})") { open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
        button("Grant video access") { permissions.launch(arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) }
        button("Grant step sensor access") { permissions.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)) }
        button("Grant Health Connect reads (steps, sleep, heart rate)") { permissions.launch(arrayOf("android.permission.health.READ_STEPS", "android.permission.health.READ_SLEEP", "android.permission.health.READ_HEART_RATE")) }
        button("Open Health Connect") { open("android.health.connect.action.HEALTH_HOME_SETTINGS") }
        text("Screen sharing requests — Android will ask you which screen to share.")
        app.screenGrants.all().filter { it.optString("status") == "pending" }.forEach { o ->
            val seconds = o.getInt("duration_seconds")
            text("${app.agents.get(o.optString("agent_id"))?.name ?: "Removed agent"}: ${if (seconds == 0) "one capture" else "${seconds / 3600} hour(s)"}")
            button("Review screen sharing") {
                if (ProjectionService.instance != null) { Toast.makeText(this, "Stop the active screen share first", Toast.LENGTH_LONG).show(); return@button }
                projectionRequest = o
                projectionConsent.launch(getSystemService(android.media.projection.MediaProjectionManager::class.java).createScreenCaptureIntent())
            }
            button("Deny screen access") { app.screenGrants.decide(o.getString("id"), "denied"); render() }
        }
        if (ProjectionService.instance != null) button("Stop screen sharing") { ProjectionService.instance?.stopSelf(); render() }
        text("Active controls")
        button("Turn flashlight off") { app.hardware.light(false, 1); app.log.system("audit", "setting", "Owner turned the flashlight off") }
        button("Stop ringing") { app.hardware.ring(false, 1); app.log.system("audit", "setting", "Owner stopped ringing") }
        button("Undo timed phone settings") { app.hardware.restoreDue(true); app.log.system("audit", "setting", "Owner requested undo of timed phone settings"); render() }
        text("Clipboard requests — inspect the exact text before approving. Your tap copies it; nothing sends a message.")
        app.clipboardRequests.all().filter { it.optString("status") == "pending" }.forEach { o ->
            text("${app.agents.get(o.optString("agent_id"))?.name ?: "Removed agent"}:\n${o.optString("text")}")
            button("Copy this text") { app.clipboardRequests.decide(o.getString("id"), true); render() }
            button("Deny") { app.clipboardRequests.decide(o.getString("id"), false); render() }
        }
        text("Files — private storage, 30-day retention, 25 MB each / 100 MB total; agent uploads: 25 MB / 25 files per agent, 50 MB / 50 files combined. Agents see only their own files. Selecting a download makes a private copy for the chosen agent.")
        button("Choose a file from Downloads") {
            if (!app.settings.isOn("downloads")) Toast.makeText(this, "Enable Selected Downloads in Features first", Toast.LENGTH_LONG).show() else pick.launch(arrayOf("*/*"))
        }
        app.transfers.list(kind = intent.getStringExtra("filter")).forEach { o ->
            text("${o.getString("name")} · ${o.getLong("size_bytes")} bytes · ${app.agents.get(o.optString("agent_id"))?.name ?: "Removed agent"} · ${o.getString("kind")}")
            button("Open ${o.getString("name")}") {
                if (Rules.installable(o.getString("name"), o.getString("mime"))) error("App packages cannot be opened here. Use the verified updater.")
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", app.transfers.file(o))
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, o.getString("mime")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
            button("Save a copy…") { exporting = o; export.launch(o.getString("name")) }
            button("Delete ${o.getString("name")}") { app.transfers.delete(o.getString("id")); app.log.system("audit", "setting", "Owner deleted a transfer"); render() }
        }
    }
    private fun chooseAgent(kind: String, uri: Uri?, text: String?) {
        val agents = app.agents.all().filter { it.allows(kind) }
        if (agents.isEmpty()) { Toast.makeText(this, "Add an agent with $kind access first", Toast.LENGTH_LONG).show(); return }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Send selected file to…").setItems(agents.map { it.name }.toTypedArray()) { _, n ->
            val agent = agents[n]
            lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        if (!app.settings.isOn(kind) || app.agents.get(agent.id)?.allows(kind) != true) error("Access was disabled")
                        val name = uri?.let { u -> contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } } ?: "shared.txt"
                        val input = uri?.let { contentResolver.openInputStream(it) } ?: text.orEmpty().byteInputStream()
                        val o = app.transfers.save(agent.id, name, uri?.let { contentResolver.getType(it) } ?: "text/plain", kind, input)
                        app.interactions.event(agent.id, "share.created", JSONObject().put("id", o.getString("id")).put("kind", kind).put("name", name))
                        app.log.system("audit", "share", "Owner shared one $kind file with ${agent.name}")
                    }
                }.onFailure { Toast.makeText(this@OwnerToolsActivity, it.message ?: "Could not share", Toast.LENGTH_LONG).show() }
                render()
            }
        }.setNegativeButton("Cancel", null).show()
    }
    companion object {
        fun notify(app: BridgeApp, title: String, text: String, id: Int = 701) {
            val open = PendingIntent.getActivity(app, id, Intent(app, OwnerToolsActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            app.getSystemService(NotificationManager::class.java).notify(id, NotificationCompat.Builder(app, BridgeApp.CHANNEL_CAPTURE)
                .setSmallIcon(R.drawable.ic_nav_more).setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).build())
        }
    }
}

/** Android share sheet receiver: explicit recipient selection, then bounded copy into private storage. */
class ShareActivity : AppCompatActivity() {
    private val app get() = application as BridgeApp
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        if (!app.settings.isOn("shares")) { fail("Turn on Share to an agent in Features first."); return }
        val agents = app.agents.all().filter { it.allows("shares") }
        if (agents.isEmpty()) { fail("Add an agent with sharing access first."); return }
        val uris = if (intent.action == Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (uris.size > 10 || uris.any { it.scheme != "content" || it.authority == "$packageName.fileprovider" } || (uris.isEmpty() && sharedText.isNullOrBlank()) || (sharedText?.length ?: 0) > 200_000) { fail("Share up to ten content files or 200 KB of text."); return }
        val view = TextView(this).apply { text = "Choose which agent receives this share. It may retain a copy on its own servers."; setPadding(32, 48, 32, 32) }; setContentView(view)
        lifecycleScope.launch {
            val preview = withContext(Dispatchers.IO) { runCatching {
                if (uris.isEmpty()) sharedText.orEmpty() else uris.mapIndexed { n, uri ->
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) "${n + 1}. ${c.getString(0)} (${if (c.isNull(1)) "unknown size" else "${c.getLong(1)} bytes"})" else "${n + 1}. Unnamed file"
                    } ?: "${n + 1}. Unnamed file"
                }.joinToString("\n")
            }.getOrElse { "Could not inspect the shared files" } }
            view.text = "${if (uris.isEmpty()) "Shared text" else "${uris.size} shared file(s)"}:\n\n$preview\n\nChoose an agent below. It may retain a copy on its own servers."
            androidx.appcompat.app.AlertDialog.Builder(this@ShareActivity).setTitle("Review share")
                .setMessage(view.text).setPositiveButton("Choose agent") { _, _ -> chooseRecipient(agents, uris, sharedText) }
                .setNegativeButton("Cancel") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
        }
    }
    private fun chooseRecipient(agents: List<cc.bridgit.app.core.Agent>, uris: List<Uri>, sharedText: String?) {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Share to agent").setItems(agents.map { it.name }.toTypedArray()) { _, index ->
            val a = agents[index]
            lifecycleScope.launch {
                val result = runCatching { withContext(Dispatchers.IO) {
                    if (!app.settings.isOn("shares") || app.agents.get(a.id)?.allows("shares") != true) error("Sharing access was revoked")
                    val sources = if (uris.isEmpty()) listOf<Uri?>(null) else uris
                    sources.forEach { uri ->
                        val name = uri?.let { contentResolver.query(it, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } } ?: "shared.txt"
                        val input = uri?.let { contentResolver.openInputStream(it) } ?: sharedText.orEmpty().byteInputStream()
                        val o = app.transfers.save(a.id, name, uri?.let { contentResolver.getType(it) } ?: "text/plain", "shares", input)
                        app.interactions.event(a.id, "share.created", JSONObject().put("id", o.getString("id")).put("kind", "shares").put("name", name))
                    }
                } }
                if (result.isFailure) fail(result.exceptionOrNull()?.message ?: "Could not share") else { Toast.makeText(this@ShareActivity, "Shared with ${a.name}", Toast.LENGTH_SHORT).show(); finish() }
            }
        }.setNegativeButton("Cancel") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
    }
    private fun fail(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); finish() }
}
