package net.finleg.bridget.platform

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import kotlinx.coroutines.*
import net.finleg.bridget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.coroutines.resume

/** Declared only in the private flavor. No lock/secure-window or permission-dialog bypass. */
class DeviceControlService : AccessibilityService() {
    private val app get() = application as BridgeApp
    private val handler=Handler(Looper.getMainLooper())
    private data class Observation(val id: String,val agent: String,val session: String,val fence: String,val app: String,val tree: String,val captured: Long,val targets: Map<String,Target>)
    private data class Target(val path: List<Int>,val signature: String)
    private val observations=java.util.concurrent.ConcurrentHashMap<String,Observation>()
    private var overlay: View?=null
    private var dispatchUntil=0L
    @Volatile private var generation=0L
    override fun onServiceConnected() { super.onServiceConnected(); instance=this; app.platform.changed() }
    override fun onInterrupt() { app.platform.store.list(kind="session").filter { it.optString("status")=="active" }.forEach { app.platform.closeSession(it,"interrupted") } }
    override fun onDestroy() { clearObservation(); hideOverlay(); if(instance===this) instance=null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if(event==null || event.packageName?.toString()==packageName) return
        generation++
        val active=app.platform.store.list(kind="session").firstOrNull { it.optString("status")=="active" } ?: return
        val pkg=event.packageName?.toString() ?: return
        if(event.eventType==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg !in active.strings("apps") && pkg !in setOf("com.android.systemui","android")) app.platform.closeSession(active,"paused_app_changed")
        if(event.eventType==AccessibilityEvent.TYPE_VIEW_CLICKED && SystemClock.elapsedRealtime()>dispatchUntil) {
            // A best-effort takeover signal; the permanent Stop control is authoritative.
            app.platform.closeSession(active,"paused_owner_interaction")
        }
    }
    fun clearObservation() { observations.clear(); handler.post { hideOverlay() } }
    fun showController(agent: String) { handler.post {
        hideOverlay()
        val button=Button(this).apply {
            text="${app.platform.getAgent(agent).name} • Stop control"; contentDescription=text; setTextColor(android.graphics.Color.WHITE); setBackgroundColor(android.graphics.Color.rgb(20,44,50))
            setOnClickListener { app.platform.store.list(agent,"session").filter { it.optString("status")=="active" }.forEach { app.platform.closeSession(it,"stopped_by_owner") } }
        }
        val params=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,android.graphics.PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.END; y=60 }
        getSystemService(WindowManager::class.java).addView(button,params); overlay=button
    } }
    private fun hideOverlay() { overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }; overlay=null }
    private fun root(): AccessibilityNodeInfo = rootInActiveWindow ?: throw ApiError(409,"no_active_window","No accessible foreground window")
    private fun nodeJson(node: AccessibilityNodeInfo,path: List<Int>,refs: MutableMap<String,Target>,count: IntArray): JSONObject? {
        if(count[0]++ >= 512 || path.size>24) return null
        val bounds=Rect(); node.getBoundsInScreen(bounds)
        val secret=node.isPassword
        val out=JSONObject().put("role",node.className?.toString() ?: "").put("resource_id",node.viewIdResourceName ?: "")
            .put("label",if(secret) "[protected field]" else (node.contentDescription?.toString() ?: node.text?.toString() ?: "").take(512))
            .put("text",if(secret) "" else (node.text?.toString() ?: "").take(1024)).put("password",secret)
            .put("enabled",node.isEnabled).put("clickable",node.isClickable).put("editable",node.isEditable).put("scrollable",node.isScrollable).put("checked",node.isChecked)
            .put("bounds",JSONArray(listOf(bounds.left,bounds.top,bounds.right,bounds.bottom)))
        val ref="e"+refs.size
        if(!secret && node.isVisibleToUser) { refs[ref]=Target(path,PlatformStore.digest(out)); out.put("ref",ref) }
        val children=JSONArray()
        for(i in 0 until minOf(node.childCount,100)) { val child=node.getChild(i) ?: continue; try { nodeJson(child,path+i,refs,count)?.let(children::put) } finally { child.recycle() } }
        out.put("children",children); return out
    }
    private fun snapshotTree(root: AccessibilityNodeInfo): Pair<JSONObject,Map<String,Target>> {
        val targets=linkedMapOf<String,Target>(); val tree=nodeJson(root,emptyList(),targets,intArrayOf(0)) ?: JSONObject()
        return tree to targets
    }
    suspend fun observe(agent: String,args: JSONObject): JSONObject = withContext(Dispatchers.Main) {
        app.platform.authorize(agent,"screen.observe")
        if(getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked) throw ApiError(409,"device_locked","Unlock the device")
        val sessionId=args.optString("session_id"); val session=if(sessionId.isNotEmpty()) app.platform.session(agent,sessionId) else null
        val root=root()
        try {
            val pkg=root.packageName?.toString() ?: ""
            if(pkg==packageName || pkg.contains("permissioncontroller") || pkg=="com.android.systemui") throw ApiError(409,"protected_window","Owner must handle system or Bridgit controls")
            if(session!=null && pkg !in session.strings("apps")) throw ApiError(409,"app_not_allowed","Foreground app is outside the approved session")
            val (tree,targets)=snapshotTree(root); val id=UUID.randomUUID().toString()
            observations.entries.removeIf { SystemClock.elapsedRealtime()-it.value.captured>120_000 }
            if(observations.size>=16) observations.clear()
            observations[id]=Observation(id,agent,sessionId,session?.optString("fence") ?: "",pkg,PlatformStore.digest(tree),SystemClock.elapsedRealtime(),targets)
            val metrics=resources.displayMetrics
            val out=JSONObject().put("observation_id",id).put("as_of",System.currentTimeMillis()).put("epoch",app.platform.epoch).put("window_generation",generation).put("package",pkg).put("window_id",root.windowId)
                .put("display_id",display?.displayId ?: 0).put("rotation",display?.rotation ?: 0).put("width_px",metrics.widthPixels).put("height_px",metrics.heightPixels).put("secure_content",false).put("content_is_untrusted",true)
            if(session!=null) out.put("tree",tree).put("fence",session.optString("fence"))
            if(args.optBoolean("screenshot",true)) out.put("image",screenshot())
            out
        } finally { root.recycle() }
    }
    suspend fun screenshot(): JSONObject = suspendCancellableCoroutine { continuation ->
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY,mainExecutor,object:TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer=result.hardwareBuffer
                try {
                    val hardware=Bitmap.wrapHardwareBuffer(buffer,result.colorSpace) ?: throw ApiError(500,"capture_failed","No screenshot buffer")
                    val bitmap=hardware.copy(Bitmap.Config.ARGB_8888,false); hardware.recycle()
                    val out=ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG,85,out); val width=bitmap.width; val height=bitmap.height; bitmap.recycle()
                    if(continuation.isActive) continuation.resume(JSONObject().put("mime","image/jpeg").put("width_px",width).put("height_px",height).put("data_base64",android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP)))
                } catch(e:Exception) { if(continuation.isActive) continuation.resumeWith(Result.failure(e)) } finally { buffer.close() }
            }
            override fun onFailure(errorCode:Int) { if(continuation.isActive) continuation.resumeWith(Result.failure(ApiError(409,if(errorCode==ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) "secure_content" else "capture_unavailable","Android refused the screenshot",mapOf("android_error" to errorCode)))) }
        })
    }
    suspend fun act(agent:String,args:JSONObject):JSONObject = withContext(Dispatchers.Main) {
        args.only("session_id","fence","observation_id","type","ref","text","direction","expect")
        val s=app.platform.session(agent,args.text("session_id")); val obs=observations[args.text("observation_id")] ?: throw ApiError(409,"stale_observation","Observe the screen again")
        if(obs.agent!=agent || obs.session!=s.getString("id") || obs.fence!=args.text("fence") || s.optString("fence")!=obs.fence || SystemClock.elapsedRealtime()-obs.captured>120_000) throw ApiError(409,"stale_observation","Observation or controller expired")
        val root=root()
        try {
            if(root.packageName?.toString()!=obs.app || obs.app !in s.strings("apps") || PlatformStore.digest(snapshotTree(root).first)!=obs.tree) throw ApiError(409,"stale_observation","The screen changed; observe again")
            val type=args.text("type",32); dispatchUntil=SystemClock.elapsedRealtime()+1500
            val dispatched=when(type) {
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "tap","long_press","set_text","scroll" -> {
                    val target=obs.targets[args.text("ref",64)] ?: throw ApiError(422,"unknown_element","Unknown or protected element")
                    var node=AccessibilityNodeInfo.obtain(root)
                    try {
                        target.path.forEach { index -> val child=node.getChild(index) ?: throw ApiError(409,"stale_observation","Element disappeared"); node.recycle(); node=child }
                        if(node.isPassword || !node.isEnabled || !node.isVisibleToUser) throw ApiError(403,"protected_element","Element cannot be operated")
                        when(type) {
                            "tap" -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            "long_press" -> node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                            "set_text" -> { if(!node.isEditable) throw ApiError(422,"not_editable","Choose an editable element"); node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,args.text("text",4096)) }) }
                            else -> node.performAction(when(args.text("direction",16,"forward")) { "forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD; "backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD; else -> throw ApiError(422,"invalid_parameter","direction must be forward or backward") })
                        }
                    } finally { node.recycle() }
                }
                else -> throw ApiError(422,"unsupported_action","Allowed actions: tap, long_press, set_text, scroll, back, home")
            }
            observations.remove(obs.id)
            delay(350)
            val expected=args.optJSONObject("expect")
            val verified=if(dispatched && expected!=null) checkExpected(expected) else false
            app.platform.store.emit(agent,"action.dispatched",s.getString("id"))
            JSONObject().put("dispatched",dispatched).put("effect_observed",verified).put("outcome",if(verified) "verified" else if(dispatched) "unverified" else "not_dispatched").put("as_of",System.currentTimeMillis())
        } finally { root.recycle() }
    }
    private fun checkExpected(args:JSONObject):Boolean {
        args.only("package","text")
        val r=rootInActiveWindow ?: return false
        try {
            if(args.has("package") && r.packageName?.toString()!=args.text("package",256)) return false
            if(args.has("text")) { val text=args.text("text",512); if(text.isEmpty()) throw ApiError(422,"invalid_parameter","Expected text cannot be empty"); val nodes=r.findAccessibilityNodeInfosByText(text); val found=nodes.any { !it.isPassword && it.isVisibleToUser && (it.text?.toString()?.contains(text)==true || it.contentDescription?.toString()?.contains(text)==true) }; nodes.forEach { it.recycle() }; return found }
            return args.has("package")
        } finally { r.recycle() }
    }
    suspend fun waitFor(agent:String,args:JSONObject):JSONObject = withContext(Dispatchers.Main) {
        app.platform.session(agent,args.text("session_id")); val end=SystemClock.elapsedRealtime()+args.whole("timeout_seconds",10,1,30)*1000L
        val condition=args.optJSONObject("expect") ?: throw ApiError(422,"invalid_parameter","expect is required")
        var matched=checkExpected(condition)
        while(!matched && SystemClock.elapsedRealtime()<end) { delay(200); app.platform.session(agent,args.text("session_id")); matched=checkExpected(condition) }
        JSONObject().put("matched",matched).put("as_of",System.currentTimeMillis())
    }
    companion object { @Volatile var instance:DeviceControlService?=null; private set }
}
