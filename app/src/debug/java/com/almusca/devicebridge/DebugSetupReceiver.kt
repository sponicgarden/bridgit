package com.almusca.devicebridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug-only test hook. Only the ADB shell holds DUMP, so only ADB can send it.
 *   am broadcast -a com.almusca.devicebridge.debug.SETUP --ez pair true --ez camera true ...
 * Result data is the pairing code (never the token).
 */
class DebugSetupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as BridgeApp
        for (c in Policy.Capability.values()) {
            if (intent.hasExtra(c.key)) app.policy.setEnabled(c, intent.getBooleanExtra(c.key, false))
        }
        var code = ""
        if (intent.getBooleanExtra("pair", false)) {
            code = app.pairing.open(app.policy.issueToken()).code
            app.audit.event("pairing", "debug test pairing")
        }
        if (intent.getBooleanExtra("listener", true)) {
            app.policy.listenerEnabled = true
            BridgeService.start(context)
        }
        resultData = code
    }
}
