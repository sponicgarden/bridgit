package net.finleg.bridget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import net.finleg.bridget.core.Connection

/**
 * Debug-only test hook (TST-02). Only the ADB shell holds DUMP, so only ADB can send it.
 *   am broadcast -a net.finleg.bridget.debug.SETUP --es agent Tester --ez camera true ...
 * Extras: camera/mic/gallery (bool switches), agent (add + open a pairing window),
 * rotate (agent name: new key + pairing window), remove (agent name), developer (agent name, "name=true|false"), log_visibility (all|own),
 * listener (bool, default true), wake_word ("<agent name>:<phrase>[:clip]" adds a wake word), wake_clear (removes them all).
 * Result data is the pairing code for a new agent (never the key); the test
 * redeems it with POST /v1/pair, the same way an agent would.
 */
class DebugSetupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as BridgeApp
        if (intent.hasExtra("port")) {
            app.settings.port = intent.getIntExtra("port", BuildConfig.DEFAULT_PORT).also { require(it in 1024..65535) }
            BridgeService.running?.poke("test port changed")
        }
        intent.getStringExtra("speaker_ceiling")?.toIntOrNull()?.let { app.settings.speakerCeiling = it }
        for (m in app.registry.switchable()) {
            val k = m.switchKey!!
            if (intent.hasExtra(k)) app.settings.setOn(k, intent.getBooleanExtra(k, false))
        }
        intent.getStringExtra("log_visibility")?.let { app.settings.logVisibility = it }
        // answer=<id>:<button> and dismiss=<id> stand in for the owner's tap in tests.
        intent.getStringExtra("answer")?.let { spec ->
            val (id, button) = spec.split(":", limit = 2).let { it[0].toLong() to it.getOrNull(1) }
            runCatching { app.interactions.answer(id, button, emptyMap()) }
        }
        intent.getStringExtra("dismiss")?.let { runCatching { app.interactions.dismiss(it.toLong()) } }
        // tour_start=<agent name>:<kind,kind>:<interval s>, tour_ask=<text>, tour_end=1 stand in for the City tour screen.
        intent.getStringExtra("tour_start")?.let { spec ->
            val p = spec.split(":")
            app.agents.byName(p[0])?.let { a -> runCatching { app.tours.start(a.id, p.getOrElse(1) { "history" }.split(","), p.getOrElse(2) { "60" }.toInt()) } }
        }
        if (intent.hasExtra("tour_end")) app.tours.end("ended from the test hook")
        intent.getStringExtra("remove")?.let { n -> app.agents.byName(n)?.let { app.agents.remove(it.id) } }
        intent.getStringExtra("developer")?.let { spec ->
            val (n, v) = spec.split("=").let { it[0] to (it.getOrNull(1) != "false") }
            app.agents.byName(n)?.let { a -> app.agents.update(a.id) { it.copy(developer = v) } }
        }
        intent.getStringExtra("caps")?.let { spec ->
            val (name, grants) = spec.split("=", limit = 2)
            app.agents.byName(name)?.let { a -> app.agents.update(a.id) { it.copy(caps = if (grants == "*") null else grants.split(',').filter(String::isNotBlank).toSet()) } }
        }
        intent.getStringExtra("clipboard_decide")?.let { spec ->
            val p = spec.split(":", limit = 2)
            app.clipboardRequests.decide(p[0], p.getOrNull(1) == "approve")
        }
        // sounds_agent=<agent name>[:quiet|normal|loud] stands in for the "Any sounds" card.
        intent.getStringExtra("sounds_agent")?.let { spec ->
            val p = spec.split(":")
            app.agents.byName(p[0])?.let { a -> app.wake.saveSoundConfig(net.finleg.bridget.listen.SoundConfig(a.id, p.getOrElse(1) { "normal" })) }
        }
        if (intent.hasExtra("wake_clear")) app.wake.saveWords(emptyList())
        intent.getStringExtra("wake_word")?.let { spec ->
            val p = spec.split(":")
            app.agents.byName(p[0])?.let { a -> app.wake.saveWords(app.wake.words() + net.finleg.bridget.listen.WakeWord(p[1], a.id, p.getOrNull(2) == "clip")) }
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
        } else if (intent.hasExtra("listener")) {
            app.settings.listenerEnabled = false
            BridgeService.pauseSilently(context)
        }
        intent.getStringExtra("platform_grants")?.let { spec ->
            val parts=spec.split(":",limit=2)
            app.agents.byName(parts[0])?.let { a -> parts.getOrElse(1) { "" }.split(',').filter { it.isNotBlank() }.forEach { id ->
                net.finleg.bridget.platform.Capabilities.setEnabled(app,id,true)
                app.platform.grant(a.id,id,System.currentTimeMillis()+3600_000)
            } }
        }
        intent.getStringExtra("platform_session_approve")?.let { app.platform.approveSession(it) }
        intent.getStringExtra("platform_operation_approve")?.let { app.platform.approveOperation(it) }
        intent.getStringExtra("platform_workflow_activate")?.let { app.platform.workflows.activate(it) }
        intent.getStringExtra("platform_answer")?.let { value -> val p=value.split(":",limit=2); app.platform.answer(p[0],p.getOrElse(1) { "" }) }
        resultData = code
        // Last, after the result is set: never block the main thread (ask() reads the compass on it).
        intent.getStringExtra("tour_ask")?.let { q ->
            val pending = goAsync()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try { app.tours.ask(q, spoken = false) } finally { pending.finish() }
            }
        }
    }
}
