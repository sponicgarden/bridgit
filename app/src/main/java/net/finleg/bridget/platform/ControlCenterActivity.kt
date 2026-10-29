package net.finleg.bridget.platform

import android.Manifest
import android.app.*
import android.content.*
import android.graphics.Typeface
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.nfc.*
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import net.finleg.bridget.*
import org.json.JSONObject

/** All authority changes and approvals originate in this non-exported owner surface. */
class ControlCenterActivity:AppCompatActivity() {
    private val app get()=application as BridgeApp
    private val runtime get()=app.platform
    private lateinit var root:LinearLayout
    private lateinit var scroll:ScrollView
    private val handler=Handler(Looper.getMainLooper())
    private var chosenAgent:String?=null
    private var importAgent:String?=null
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if(app.policy.listenerEnabled) BridgeService.start(this); render() }
    private val share=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode==Activity.RESULT_OK && result.data!=null) startForegroundService(Intent(this,ProjectionService::class.java).putExtra("consent",result.data))
        render()
    }
    private val document=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) safely { runtime.files.import(importAgent ?: return@safely,uri); render() } }
    override fun onCreate(state:Bundle?) { super.onCreate(state); scroll=ScrollView(this); root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(28,36,28,48) }; scroll.addView(root); setContentView(scroll); render() }
    override fun onResume() { super.onResume(); foreground=true; render() }
    override fun onPause() { foreground=false; NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this); super.onPause() }
    private fun heading(text:String) { root.addView(TextView(this).apply { this.text=text; textSize=22f; setTypeface(typeface,Typeface.BOLD); setPadding(0,24,0,12) }) }
    private fun text(value:String) { root.addView(TextView(this).apply { text=value; textSize=15f; setPadding(0,6,0,10); setTextIsSelectable(true) }) }
    private fun button(label:String,action:()->Unit) { root.addView(MaterialButton(this).apply { text=label; setOnClickListener { safely(action) } }) }
    private fun safely(action:()->Unit) { try { action() } catch(e:Exception) { AlertDialog.Builder(this).setTitle("Needs attention").setMessage(if(e is ApiError) e.message else "The action could not be completed.").setPositiveButton("OK",null).show() } }
    private fun open(action:String,uri:Uri?=null) { try { startActivity(Intent(action,uri)) } catch(e:ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName"))) } }
    private fun render() {
        if(!::root.isInitialized) return
        val position=scroll.scrollY; root.removeAllViews()
        heading("Device tools")
        text("${runtime.store.setting("device_name",Build.MODEL)} • ${BuildConfigInfo.versionName(this)} • ${if(BuildConfig.PRIVATE_CONTROL) "Private control build" else "Play feature set"}\nDevice: ${runtime.store.deviceId}")
        text("New capabilities start off. Enable a feature and separately grant it to a named agent. Stop cancels active authority. Android permissions are always granted through Android.")
        button("Stop all agent work") { runtime.stopAll(); app.policy.listenerEnabled=false; BridgeService.stop(this); render() }
        button("Refresh status and requests") { render() }
        button("Name this device") { input("Device name",runtime.store.setting("device_name",Build.MODEL)) { runtime.store.setting("device_name",it.take(80),true); render() } }
        heading("Android access")
        button("Grant camera, microphone, photos and notifications") { permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS,Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO,Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) }
        button("Grant location") { permissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION)) }
        if(BuildConfig.PRIVATE_CONTROL) {
            button("Grant Nearby devices") { permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT)) }
            button("Enable private app control") { AlertDialog.Builder(this).setTitle("Private app control").setMessage(getString(net.finleg.bridget.R.string.private_control_disclosure)).setPositiveButton("Open Android settings") { _,_-> open(Settings.ACTION_ACCESSIBILITY_SETTINGS) }.setNegativeButton("Cancel",null).show() }
            button("Choose notification access") { open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
            button("Read an NFC tag while this screen is open") { readNfc() }
            button("Grant a connected USB device") { grantUsb() }
        }
        button("Share my screen using Android consent") { share.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()) }
        if(ProjectionService.instance?.active==true) button("Stop screen sharing") { ProjectionService.instance?.stopSelf(); render() }
        button("Do Not Disturb access") { open(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }
        button("Brightness settings access") { open(Settings.ACTION_MANAGE_WRITE_SETTINGS,Uri.parse("package:$packageName")) }
        button("Notification and capture channels") { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName)) }
        button("Battery and app settings") { open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")) }
        text("Private control: ${DeviceControlService.instance!=null}; screen sharing: ${ProjectionService.instance?.active==true}; visible capture channel: ${Capabilities.visible(this)}")
        heading("Enabled device features")
        Capabilities.all.filter { !it.privateOnly || BuildConfig.PRIVATE_CONTROL }.forEach { cap ->
            root.addView(SwitchMaterial(this).apply { text=cap.label; isChecked=Capabilities.enabled(app,cap.id); setOnCheckedChangeListener { _,on -> safely { Capabilities.setEnabled(app,cap.id,on) } } })
        }
        heading("Agent access")
        val agents=app.policy.agents()
        if(agents.isEmpty()) text("Add an agent from the main screen first.")
        else {
            if(agents.none { it.id==chosenAgent }) chosenAgent=agents.first().id
            val spinner=Spinner(this).apply { adapter=ArrayAdapter(this@ControlCenterActivity,android.R.layout.simple_spinner_dropdown_item,agents.map { it.name }); setSelection(agents.indexOfFirst { it.id==chosenAgent }) }
            var first=true
            spinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener { override fun onNothingSelected(parent:AdapterView<*>?)=Unit; override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) { if(first) { first=false; return }; chosenAgent=agents[position].id; render() } }
            root.addView(spinner)
            val agent=agents.first { it.id==chosenAgent }; identity(agent,root)
            text("Grant each selected capability for 16 hours. Unchecking revokes it immediately. Existing camera/microphone/gallery authority was preserved; no new scope was added by upgrade.")
            Capabilities.all.filter { !it.privateOnly || BuildConfig.PRIVATE_CONTROL }.forEach { cap -> root.addView(SwitchMaterial(this).apply { text=cap.label; isChecked=runtime.hasGrant(agent.id,cap.id); setOnCheckedChangeListener { _,on -> safely { if(on) runtime.grant(agent.id,cap.id,System.currentTimeMillis()+16*3600_000L) else runtime.revoke(agent.id,cap.id) } } }) }
            button("Revoke every grant for ${agent.name}") { runtime.revoke(agent.id); render() }
            if(BuildConfig.PRIVATE_CONTROL) button("Choose notification apps for ${agent.name}") { input("Allowed package names, separated by commas",runtime.store.setting("notification_apps:${agent.id}")) { value ->
                val packages=value.split(',').map { it.trim() }.filter { it.isNotEmpty() }; if(packages.size>20 || packages.any { !it.matches(Regex("[A-Za-z][A-Za-z0-9_.]+")) }) throw ApiError(422,"invalid_parameter","Use up to 20 package names")
                runtime.store.setting("notification_apps:${agent.id}",packages.joinToString(","),true)
            } }
            button("Share a selected file with ${agent.name}") { importAgent=agent.id; document.launch(arrayOf("image/*","text/plain","application/pdf","audio/*")) }
        }
        heading("Questions and approvals")
        val pending=runtime.store.list(limit=300).filter { (it.optString("kind")=="operation" && it.optString("status")=="awaiting_owner") || (it.optString("kind")=="session" && it.optString("status")=="awaiting_owner") || (it.optString("kind")=="interaction" && it.optString("status")=="pending") || (it.optString("kind")=="workflow" && it.optString("status")=="awaiting_owner") }
        if(pending.isEmpty()) text("No requests are waiting.")
        pending.forEach { record -> button("${record.optJSONObject("identity")?.optString("name") ?: "Agent"}: ${record.optString("title",record.optString("command",record.optString("kind")))}") { review(record) } }
        heading("Control sessions")
        runtime.store.list(kind="session").filter { it.optString("status")=="active" }.forEach { record -> text("${record.optJSONObject("identity")?.optString("name")} • ${record.optString("purpose")}\n${record.optString("apps")}"); button("Stop this control session") { runtime.closeSession(record,"stopped_by_owner"); render() } }
        heading("Local appliances and workflows")
        text("Native templates only. Interval checks run with the listener; Android maintenance checks may be deferred. No emergency response or continuous availability is promised.")
        runtime.store.list(kind="workflow").forEach { record ->
            val definition=record.getJSONObject("definition"); text("${definition.optString("name")} • ${record.optString("status")}\n${definition.optJSONObject("ui")?.optString("text") ?: ""}")
            if(record.optString("status")=="active") {
                button(definition.optJSONObject("ui")?.optString("title")?.takeIf { it.isNotBlank() } ?: "Run / Help") { runtime.mutate(record.getString("agent_id"),"workflow.run",JSONObject().put("id",record.getString("id")),java.util.UUID.randomUUID().toString()); render() }
                button("Pause this workflow") { runtime.workflows.pause(record.getString("agent_id"),record.getString("id")); render() }
            } else button("Review and activate workflow") { review(record) }
        }
        heading("Camera monitoring")
        text("Explicitly approved, time-limited JPEG frames. No microphone streaming. Captures stop on lock, lost visibility or revoked access. Viewers must inspect freshness.")
        runtime.store.list(kind="stream").forEach { stream ->
            val id=stream.getString("id"); text(runtime.monitor.status(stream.getString("agent_id"),id).toString(2))
            if(stream.optString("status")=="active") {
                button("Stop this camera session") { runtime.monitor.stop(stream.getString("agent_id"),id); render() }
                button("Issue a viewer credential") { val token=runtime.monitor.issueViewer(stream.getString("agent_id"),id); AlertDialog.Builder(this).setTitle("Viewer credential — shown once").setMessage("Read-only for this stream. Store securely.\n\n$token\n\n/v2/viewer/streams/$id").setPositiveButton("Copy") { _,_-> getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Bridgit viewer",token)) }.setNegativeButton("Close",null).show() }
                button("Revoke viewer credential") { runtime.monitor.revokeViewer(id) }
            }
        }
        heading("Files and notices")
        runtime.store.list(kind="file").forEach { record -> button("Open ${record.optString("name")}") { val agent=record.getString("agent_id"); val id=record.getString("id"); val uri=androidx.core.content.FileProvider.getUriForFile(this,"$packageName.files",runtime.files.file(agent,id)); startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,record.getString("mime")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); runtime.store.emit(agent,"file.opened",id) } }
        runtime.store.list(kind="interaction").filter { it.optString("status")=="delivered" }.take(20).forEach { r -> text("${r.optJSONObject("identity")?.optString("name")}: ${r.optString("title")}\n${r.optString("text")}") }
        heading("Recent operations")
        runtime.store.list(kind="operation").take(20).forEach { record -> text("${record.optString("command")} • ${record.optString("status")}\n${record.optString("message")}"); if(record.optString("status") in listOf("accepted","running","awaiting_owner")) button("Cancel this operation") { runtime.cancelOperation(record.getString("agent_id"),record.getString("id")); render() } }
        text("Build readiness and blockers are also available through /v2/capabilities. Agent artwork is self-supplied, not a verified identity badge.")
        scroll.post { scroll.scrollTo(0,position) }
    }
    private fun review(record:JSONObject) {
        val agent=record.optJSONObject("identity") ?: AgentBranding.json(runtime.getAgent(record.getString("agent_id")))
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,16,24,16) }; identityJson(agent,box)
        val body=when(record.getString("kind")) { "operation" -> record.getJSONObject("input").toString(2); "session" -> "Purpose: ${record.optString("purpose")}\nApps: ${record.optString("apps")}\nDuration: ${record.optInt("duration_seconds")} seconds\nEvery action still requires review."; "workflow" -> record.getJSONObject("definition").toString(2); else -> record.optString("text") }
        box.addView(TextView(this).apply { text=body; textSize=15f; setTextIsSelectable(true) })
        if(record.optString("kind")=="interaction") {
            val edit=EditText(this).apply { hint="Your answer" }; box.addView(edit)
            val dialog=AlertDialog.Builder(this).setTitle(record.optString("title")).setView(ScrollView(this).apply { addView(box) }).setPositiveButton("Reply") { _,_-> safely { runtime.answer(record.getString("id"),edit.text.toString()); render() } }.setNegativeButton("Dismiss") { _,_-> safely { runtime.answer(record.getString("id"),"",true); render() } }.create()
            record.optJSONArray("choices")?.let { choices -> for(i in 0 until choices.length()) box.addView(MaterialButton(this).apply { text=choices.getString(i); setOnClickListener { safely { runtime.answer(record.getString("id"),choices.getString(i)); dialog.dismiss(); render() } } }) }
            dialog.show(); return
        }
        AlertDialog.Builder(this).setTitle("Review ${record.optString("command",record.optString("kind"))}").setView(ScrollView(this).apply { addView(box) }).setPositiveButton("Continue") { _,_->
            val confirm=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,16,24,16) }; identityJson(agent,confirm)
            confirm.addView(TextView(this).apply { text="Confirm the exact action above. Its authority cannot grow after approval." })
            AlertDialog.Builder(this).setTitle("Confirm ${agent.optString("name")}'s request").setView(confirm).setPositiveButton("Approve") { _,_-> safely {
                when(record.getString("kind")) {
                    "session" -> { runtime.approveSession(record.getString("id")); val pkg=record.strings("apps").first(); packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) } }
                    "workflow" -> runtime.workflows.activate(record.getString("id"))
                    else -> {
                        if(record.optString("command")=="ui.action") {
                            val input=record.getJSONObject("input"); val session=runtime.session(record.getString("agent_id"),input.text("session_id")); packageManager.getLaunchIntentForPackage(session.strings("apps").first())?.let { startActivity(it) }
                            handler.postDelayed({ safely { runtime.approveOperation(record.getString("id")) } },1200)
                        } else runtime.approveOperation(record.getString("id"))
                    }
                }
                render()
            } }.setNegativeButton("Cancel",null).show()
        }.setNegativeButton("Decline") { _,_-> safely {
            when(record.getString("kind")) { "session" -> runtime.closeSession(record,"declined"); "workflow" -> runtime.workflows.pause(record.getString("agent_id"),record.getString("id")); else -> runtime.cancelOperation(record.getString("agent_id"),record.getString("id")) }; render()
        } }.show()
    }
    private fun identity(agent:Policy.Agent,box:LinearLayout)=identityJson(AgentBranding.json(agent),box)
    private fun identityJson(agent:JSONObject,box:LinearLayout) {
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setPadding(8,12,8,12) }
        AgentBranding.bitmap(agent.optString("icon_base64").takeIf { it.isNotBlank() && it!="null" })?.let { bitmap -> row.addView(ImageView(this).apply { setImageBitmap(bitmap); contentDescription="${agent.optString("name")} icon" },LinearLayout.LayoutParams(72,72)) }
        row.addView(TextView(this).apply { text=agent.optString("name"); textSize=20f; setTypeface(typeface,Typeface.BOLD); setPadding(12,0,0,0) })
        box.addView(row)
        AgentBranding.bitmap(agent.optString("wordmark_base64").takeIf { it.isNotBlank() && it!="null" })?.let { bitmap -> box.addView(ImageView(this).apply { setImageBitmap(bitmap); adjustViewBounds=true; contentDescription="${agent.optString("name")} wordmark" },LinearLayout.LayoutParams(-1,72)) }
    }
    private fun input(title:String,value:String,save:(String)->Unit) { val field=EditText(this).apply { setText(value) }; AlertDialog.Builder(this).setTitle(title).setView(field).setPositiveButton("Save") { _,_-> safely { save(field.text.toString()) } }.setNegativeButton("Cancel",null).show() }
    private fun grantUsb() {
        val manager=getSystemService(UsbManager::class.java); val devices=manager.deviceList.values.toList()
        if(devices.isEmpty()) throw ApiError(409,"no_usb_device","Connect a USB device first")
        AlertDialog.Builder(this).setTitle("Choose USB device").setItems(devices.map { it.productName ?: it.deviceName }.toTypedArray()) { _,index -> manager.requestPermission(devices[index],PendingIntent.getBroadcast(this,987,Intent("$packageName.USB_PERMISSION").setPackage(packageName),PendingIntent.FLAG_IMMUTABLE)) }.show()
    }
    private fun readNfc() {
        val nfc=NfcAdapter.getDefaultAdapter(this) ?: throw ApiError(409,"nfc_unavailable","This device has no NFC reader")
        nfc.enableReaderMode(this,{ tag ->
            val ndef=android.nfc.tech.Ndef.get(tag); val messages=ndef?.cachedNdefMessage?.records?.take(16)?.map { r -> JSONObject().put("type",String(r.type)).put("payload_base64",android.util.Base64.encodeToString(r.payload.take(4096).toByteArray(),android.util.Base64.NO_WRAP)) } ?: emptyList()
            runtime.store.setting("last_nfc",JSONObject().put("id",tag.id.toHex()).put("technologies",org.json.JSONArray(tag.techList.toList())).put("records",org.json.JSONArray(messages)).put("as_of",System.currentTimeMillis()).put("content_is_untrusted",true).toString(),true)
            handler.post { Toast.makeText(this,"NFC tag read",Toast.LENGTH_SHORT).show(); nfc.disableReaderMode(this) }
        },NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,null)
        Toast.makeText(this,"Hold your own tag near the phone",Toast.LENGTH_LONG).show()
    }
    companion object { @Volatile var foreground=false; private set }
}
class ShareReceiveActivity:AppCompatActivity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state); val app=application as BridgeApp
        if(intent.action!=Intent.ACTION_SEND) { finish(); return }
        val agents=app.policy.agents().filter { app.platform.hasGrant(it.id,"files") && Capabilities.enabled(app,"files") }
        if(agents.isEmpty()) { AlertDialog.Builder(this).setTitle("No agent has file access").setMessage("Enable Files and shares and grant it to your agent in Device tools.").setPositiveButton("Open Device tools") { _,_-> startActivity(Intent(this,ControlCenterActivity::class.java)); finish() }.setNegativeButton("Cancel") { _,_-> finish() }.show(); return }
        AlertDialog.Builder(this).setTitle("Share with which agent?").setItems(agents.map { it.name }.toTypedArray()) { _,index ->
            try {
                val a=agents[index]; app.platform.authorize(a.id,"files")
                val uri=intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri::class.java)
                if(uri!=null && uri.scheme=="content") app.platform.files.import(a.id,uri)
                else { val text=intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.take(32768) ?: throw ApiError(422,"unsupported_share","Share text or a supported document"); app.platform.files.put(a.id,JSONObject().put("name","Shared text.txt").put("mime","text/plain").put("data_base64",android.util.Base64.encodeToString(text.toByteArray(),android.util.Base64.NO_WRAP))) }
                Toast.makeText(this,"Shared with ${a.name}",Toast.LENGTH_SHORT).show()
            } catch(e:Exception) { Toast.makeText(this,if(e is ApiError) e.message else "Could not import this share",Toast.LENGTH_LONG).show() }
            finish()
        }.setNegativeButton("Cancel") { _,_-> finish() }.setOnCancelListener { finish() }.show()
    }
}
