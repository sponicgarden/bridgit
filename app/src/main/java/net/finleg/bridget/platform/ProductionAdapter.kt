package net.finleg.bridget

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import net.finleg.bridget.log.LogEntry
import org.json.JSONObject

typealias ApiError=net.finleg.bridget.api.ApiError
typealias Gallery=net.finleg.bridget.capability.Gallery
typealias MicCapture=net.finleg.bridget.capability.MicCapture
fun Context.granted(permission:String)=ContextCompat.checkSelfPermission(this,permission)==android.content.pm.PackageManager.PERMISSION_GRANTED
class CameraCapture(context:Context,owner:LifecycleOwner) {
    private val delegate=net.finleg.bridget.capability.CameraCapture(context,owner)
    suspend fun snapshot(rear:Boolean,flash:Boolean,maxWidth:Int?)=delegate.snapshot(rear,flash,net.finleg.bridget.api.Sizing.Spec(null,maxWidth,85))
}
object BuildConfigInfo { fun versionName(context:Context):String=context.packageManager.getPackageInfo(context.packageName,0).versionName ?: "unknown" }
/** Platform records appear in the existing native Activity screen, with no input payloads. */
class PlatformAudit(private val app:BridgeApp) {
    fun append(record:JSONObject) { app.log.append(LogEntry(ts=System.currentTimeMillis(),channel="audit",kind="request",agentId=record.optString("agent_id").takeIf { it.isNotBlank() },agent=record.optString("agent").takeIf { it.isNotBlank() },endpoint=record.optString("endpoint"),result=record.optString("result"),requestId=record.optString("request_id"),data=JSONObject().put("operation_id",record.optString("operation_id")))) }
}
