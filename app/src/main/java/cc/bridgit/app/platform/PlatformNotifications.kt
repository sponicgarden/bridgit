package cc.bridgit.app.platform

import android.app.*
import android.content.*
import androidx.core.app.NotificationCompat
import cc.bridgit.app.*
import org.json.JSONObject

object PlatformNotifications {
    fun show(app:BridgeApp,record:JSONObject) {
        if(!app.granted(android.Manifest.permission.POST_NOTIFICATIONS)) return
        val id=record.getString("id"); val name=record.optJSONObject("identity")?.optString("name") ?: "Agent"
        val pending=PendingIntent.getActivity(app,id.hashCode(),Intent(app,ControlCenterActivity::class.java).putExtra("review_id",id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title=if(record.optString("kind")=="interaction") "$name: ${record.optString("title")}" else "$name requests ${record.optString("command",record.optString("kind"))}"
        val text=if(record.optString("kind")=="interaction") record.optString("text") else "Review the exact request and confirm on this phone."
        app.getSystemService(NotificationManager::class.java).notify(id.hashCode(),NotificationCompat.Builder(app,BridgeApp.INTERACTION_CHANNEL).setSmallIcon(cc.bridgit.app.R.drawable.ic_notif).setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(pending).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(NotificationCompat.Builder(app,BridgeApp.INTERACTION_CHANNEL).setSmallIcon(cc.bridgit.app.R.drawable.ic_notif).setContentTitle("BRIDGIT agent request").build()).build())
    }
}
class PauseTile:android.service.quicksettings.TileService() {
    override fun onStartListening() { super.onStartListening(); val app=application as BridgeApp; qsTile.state=if(app.policy.listenerEnabled) android.service.quicksettings.Tile.STATE_ACTIVE else android.service.quicksettings.Tile.STATE_INACTIVE; qsTile.label="BRIDGIT"; qsTile.updateTile() }
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated") // the Intent form is the only one before Android 14
    override fun onClick() {
        super.onClick(); val app=application as BridgeApp
        if(app.policy.listenerEnabled) { app.platform.stopAll(); app.policy.listenerEnabled=false; BridgeService.stop(this); onStartListening() }
        else { val pending=PendingIntent.getActivity(this,0,Intent(this,cc.bridgit.app.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),PendingIntent.FLAG_IMMUTABLE)
            if(android.os.Build.VERSION.SDK_INT>=34) startActivityAndCollapse(pending) else @Suppress("DEPRECATION") startActivityAndCollapse(Intent(this,cc.bridgit.app.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
class ApplianceAdmin:android.app.admin.DeviceAdminReceiver()
