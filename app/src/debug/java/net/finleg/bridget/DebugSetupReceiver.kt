package net.finleg.bridget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import net.finleg.bridget.core.Connection

/**
 * Debug-only test hook (TST-02). Only the ADB shell holds DUMP, so only ADB can send it.
 *   am broadcast -a net.finleg.bridget.debug.SETUP --es agent Tester --ez camera true ...
 * Extras: camera/mic/gallery (bool switches), agent (add + open a pairing window),
 * rotate (agent name: new key + pairing window), remove (agent name), developer (agent name, "name=true|false"), log_visibility (all|own),
 * listener (bool, default true).
 * Result data is the pairing code for a new agent (never the key); the test
 * redeems it with POST /v1/pair, the same way an agent would.
 */
class DebugSetupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as BridgeApp
        for (m in app.registry.switchable()) {
            val k = m.switchKey!!
            if (intent.hasExtra(k)) app.settings.setOn(k, intent.getBooleanExtra(k, false))
        }
        intent.getStringExtra("log_visibility")?.let { app.settings.logVisibility = it }
        intent.getStringExtra("remove")?.let { n -> app.agents.byName(n)?.let { app.agents.remove(it.id) } }
        intent.getStringExtra("developer")?.let { spec ->
            val (n, v) = spec.split("=").let { it[0] to (it.getOrNull(1) != "false") }
            app.agents.byName(n)?.let { a -> app.agents.update(a.id) { it.copy(developer = v) } }
        }
        var code = ""
        intent.getStringExtra("rotate")?.let { n ->
            app.agents.byName(n)?.let { a ->
                val (agent, key) = app.agents.rotate(a.id)
                code = app.pairing.open(agent.id, agent.name, key).code
            }
        }
        intent.getStringExtra("agent")?.let { name ->
            app.agents.byName(name)?.let { app.agents.remove(it.id) }
            val (agent, key) = app.agents.add(name, Connection.of(intent.getStringExtra("connection")))
            code = app.pairing.open(agent.id, agent.name, key).code
            app.log.system("audit", "agent", "Debug hook added test agent ${agent.name}")
        }
        if (intent.getBooleanExtra("listener", true)) {
            app.settings.listenerEnabled = true
            BridgeService.start(context)
        }
        resultData = code
    }
}
