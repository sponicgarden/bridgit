package net.finleg.bridget.platform

import android.app.KeyguardManager
import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import net.finleg.bridget.*
import org.json.JSONArray
import org.json.JSONObject

class NotificationBridge:NotificationListenerService() {
    private val app get()=application as BridgeApp
    override fun onListenerConnected() { super.onListenerConnected(); instance=this; app.platform.changed() }
    override fun onListenerDisconnected() { instance=null; super.onListenerDisconnected() }
    override fun onNotificationPosted(sbn:StatusBarNotification?) { sbn ?: return; if(sbn.packageName==packageName) return; app.policy.agents().filter { permitted(it.id,sbn.packageName) }.forEach { app.platform.store.emit(it.id,"notification.changed") } }
    private fun permitted(agent:String,pkg:String):Boolean = app.platform.store.setting("notification_apps:$agent").split(',').map { it.trim() }.contains(pkg)
    private fun fingerprint(s:StatusBarNotification)=PlatformStore.digest(JSONObject().put("key",s.key).put("posted_at",s.postTime).put("text",s.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()))
    fun list(agent:String):JSONObject {
        app.platform.authorize(agent,"notifications.read")
        if(getSystemService(KeyguardManager::class.java).isDeviceLocked) throw ApiError(409,"device_locked","Unlock to read notifications")
        return JSONObject().put("content_is_untrusted",true).put("notifications",JSONArray(activeNotifications.filter { it.packageName!=packageName && permitted(agent,it.packageName) }.take(100).map { s ->
            JSONObject().put("id",s.key).put("package",s.packageName).put("posted_at",s.postTime).put("fingerprint",fingerprint(s))
                .put("title",s.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(512) ?: "")
                .put("text",s.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.take(4096) ?: "")
                .put("reply_actions",JSONArray(s.notification.actions?.mapIndexedNotNull { index,a -> if(a.remoteInputs?.any { it.allowFreeFormInput }==true) JSONObject().put("index",index).put("label",a.title.toString()) else null } ?: emptyList<JSONObject>()))
        }))
    }
    fun reply(agent:String,args:JSONObject):JSONObject {
        args.only("id","fingerprint","action_index","text")
        app.platform.authorize(agent,"notifications.reply"); app.platform.authorize(agent,"notifications.read")
        if(getSystemService(KeyguardManager::class.java).isDeviceLocked) throw ApiError(409,"device_locked","Unlock to reply")
        val s=activeNotifications.firstOrNull { it.key==args.text("id",1024) && permitted(agent,it.packageName) } ?: throw ApiError(409,"notification_expired","Notification no longer available")
        if(fingerprint(s)!=args.text("fingerprint",64)) throw ApiError(409,"stale_notification","Notification changed; review again")
        val index=args.whole("action_index",0,0,20)
        val action=s.notification.actions?.getOrNull(index) ?: throw ApiError(422,"unsupported_action","No such notification action")
        val inputs=action.remoteInputs?.filter { it.allowFreeFormInput }?.toTypedArray()?.takeIf { it.isNotEmpty() } ?: throw ApiError(422,"reply_not_supported","App does not expose inline reply")
        val text=args.text("text",4096)
        val fill=Intent(); RemoteInput.addResultsToIntent(inputs,fill,android.os.Bundle().apply { inputs.forEach { putCharSequence(it.resultKey,text) } })
        RemoteInput.setResultsSource(fill,RemoteInput.SOURCE_FREE_FORM_INPUT)
        action.actionIntent.send(this,0,fill)
        return JSONObject().put("dispatched",true).put("delivered",JSONObject.NULL).put("outcome","unverified")
    }
    companion object { @Volatile var instance:NotificationBridge?=null; private set }
}
