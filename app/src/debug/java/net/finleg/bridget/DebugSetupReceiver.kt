package net.finleg.bridget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug-only test hook. Only the ADB shell holds DUMP, so only ADB can send it.
 *   am broadcast -a net.finleg.bridget.debug.SETUP --es agent Tester --ez camera true ...
 * Result data is the pairing code (never the token).
 */
class DebugSetupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as BridgeApp
        for (c in Policy.Capability.values()) {
            if (intent.hasExtra(c.key)) app.policy.setEnabled(c, intent.getBooleanExtra(c.key, false))
        }
        var code = ""
        intent.getStringExtra("agent")?.let { name ->
            val (agent, token) = app.policy.addAgent(name)
            code = app.pairing.open(agent.id, agent.name, token).code
            app.audit.event("pairing", "debug test agent ${agent.name}")
        }
        if (intent.getBooleanExtra("listener", true)) {
            app.policy.listenerEnabled = true
            BridgeService.start(context)
        }
        resultData = code
    }
}
