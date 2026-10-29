package net.finleg.bridget.relay

import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.finleg.bridget.BridgeApp
import net.finleg.bridget.BridgeService
import net.finleg.bridget.core.Connection
import net.finleg.bridget.platform.Commands
import org.json.JSONObject

/** Local owner setup only. No exported component, network approval endpoint, or secret screenshot. */
class RelayActivity:AppCompatActivity() {
    private val app get()=application as BridgeApp
    private val store by lazy { RelayStore(app) }
    private lateinit var rows:LinearLayout
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);render();intent.getStringExtra("relay_agent_id")?.let { id->app.agents.get(id)?.takeIf { it.connection==Connection.RELAY }?.let { add(it) } }}
    private fun text(value:String)=TextView(this).apply { text=value;setPadding(24,16,24,16);setTextIsSelectable(true) }
    private fun button(label:String,action:()->Unit){rows.addView(Button(this).apply { text=label;setOnClickListener { action() } })}
    private fun field(hintText:String)=EditText(this).apply { hint=hintText;isSingleLine=true;setPadding(24,16,24,16) }
    private fun render(){
        rows=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(16,48,16,32) };setContentView(ScrollView(this).apply { addView(rows) })
        rows.addView(text("Relay · ${if(store.enabled) BridgeService.running?.relay?.status ?: "Listener stopped" else "Off"}"))
        rows.addView(text("Sealed: only this phone and the agent can read content. Connector: the relay can read and control this agent's permitted requests. Content is held in relay memory until the deadline (up to 30 minutes), then deleted; a relay restart can lose it. Encrypted sealed jobs use Cloudflare Durable Object storage until the same deadline. Delivery during Doze is not guaranteed. Direct Tailscale access remains available."))
        button("Show phone enrolment keys"){
            val value=store.enrollment().toString(2)
            MaterialAlertDialogBuilder(this).setTitle("Owner enrolment").setView(text(value)).setMessage("At your relay's /d/${store.device}/admin/ page, sign in as owner and register these public keys. Enter its one-time token here within five minutes. The relay URL must end in /d/${store.device}.").setPositiveButton("Close",null).show()
        }
        val base=field("https://your-relay/d/${store.device}").apply { setText(store.base) };rows.addView(base)
        val token=field("One-time token (blank to resume an enrolled device)").apply { inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD };rows.addView(token)
        button("Confirm this phone and enable relay"){
            lifecycleScope.launch {
                try {store.base=base.text.toString().trim();require(store.base.endsWith("/d/${store.device}"));val t=token.text.toString();token.text.clear()
                    withContext(Dispatchers.IO){(BridgeService.running?.relay ?: error("Resume the listener first")).enroll(t)};render()
                }catch(e:Exception){notice(e.message ?: "Enrolment failed")}
            }
        }
        button("Disable relay and cancel relay activity") {BridgeService.running?.relay?.disable() ?: run {store.enabled=false;store.agents().forEach { app.platform.invalidate(it.getString("id")) }};render()}
        rows.addView(text("Lost phone: use Disable device at ${store.base.ifEmpty { "your relay /d/${store.device}" }}/admin/. Disabling here stops local execution immediately; if offline, the server learns only when this phone reconnects. Admin disable refuses new jobs immediately. After admin disable, the owner must enable the device on that page and confirm here again."))
        button("Add relay agent"){if(store.base.isEmpty())notice("Enrol this phone first") else add()}
        for(c in store.agents()){
            val id=c.getString("id");rows.addView(text("${app.agents.get(id)?.name}: ${c.getString("mode")}${if(c.getString("mode")=="connector") " · the relay can read and control permitted traffic" else " · end-to-end encrypted"}"))
            button("Revoke ${app.agents.get(id)?.name}"){store.remove(id);app.agents.remove(id);render()}
        }
        rows.addView(text("New v2 scopes start denied. Grant them in More → Device tools on the phone. Relay allowlists only restrict those grants. Wake words and transcripts never count as approval. Media streaming, workflows and feedback/developer tools are excluded from this relay release."))
        button("Close"){finish()}
    }
    private fun notice(message:String){MaterialAlertDialogBuilder(this).setMessage(message).setPositiveButton("OK",null).show()}
    private fun add(existing:net.finleg.bridget.core.Agent?=null){
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val name=field("Agent name").apply { setText(existing?.name ?: intent.getStringExtra("suggested_name") ?: "");isEnabled=existing==null };val peer=field("Sealed agent P-256 HPKE public key (base64)")
        val mode=Spinner(this).apply { adapter=ArrayAdapter(this@RelayActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("sealed","connector")) };box.addView(name);box.addView(mode);box.addView(peer)
        MaterialAlertDialogBuilder(this).setTitle("New relay-only agent").setView(box).setPositiveButton("Choose permitted routes") { _,_->
            val n=name.text.toString().trim();app.agents.checkName(n,existing?.id)?.let { notice(it);return@setPositiveButton }
            val chosenMode=mode.selectedItem.toString();val pub=peer.text.toString().trim()
            if(chosenMode=="sealed"&&runCatching { Sealed.validatePublic(pub);false }.getOrDefault(true)){notice("Generate and pin the agent's P-256 HPKE public key first with the sealed helper.");return@setPositiveButton}
            val specs=app.registry.modules.flatMap { it.endpoints }.filter { it.auth&&!it.developerOnly&&it.capability!="feedback"&&it.path!="/mcp"&&it.capability !in setOf("tour","speaker","video","screenshare") }
            val routes=specs.map { "${it.method} ${it.fullPath}" };val tools=(Commands.reads+Commands.mutations-RelayDispatcher.excluded).sorted()
            val options=routes+tools.map { "Tool $it" };val selected=BooleanArray(options.size){i -> if(i<routes.size)specs[i].capability=="status" else tools[i-routes.size] in setOf("diagnostics","phone.status","interaction.create","interaction.get","interaction.list","events","operation.get")}
            MaterialAlertDialogBuilder(this).setTitle("Explicit relay allowlist").setMultiChoiceItems(options.toTypedArray(),selected){_,i,on->selected[i]=on}.setPositiveButton("Create and show setup") { _,_->
                val caps=specs.filterIndexed { i,_->selected[i] }.map { it.capability }.toSet()
                val a=existing?.let { app.agents.get(it.id) } ?: app.agents.add(n,Connection.RELAY).first;app.agents.update(a.id){it.copy(caps=caps,developer=false)}
                val tokenValue=store.addAgent(a.id,chosenMode,pub,routes.filterIndexed { i,_->selected[i] }.toSet(),tools.filterIndexed { i,_->selected[routes.size+i] }.toSet())
                val config=store.agents().first { it.getString("id")==a.id }
                val setup=JSONObject().put("base",store.base).put("device_id",store.device).put("agent_id",a.id).put("generation",a.keyHash).put("mode",chosenMode).put("token",tokenValue).put("phone_hpke_public",Sealed.publicKey(store.hpke())).put("mcp_url",store.base+"/mcp").put("allowlist",config).toString(2)
                MaterialAlertDialogBuilder(this).setTitle("Save setup now · shown once").setMessage(if(chosenMode=="connector") "The relay can read and control only the routes/tools you permitted. OAuth clients must be preregistered by the relay owner. This token cannot call the direct phone API." else "Pin the phone key in the helper config. Keep your agent private key locally. Send sequential requests; concurrent helpers must coordinate their durable sequence counter.").setView(text(setup)).setPositiveButton("Saved"){_,_->render()}.show()
            }.setNegativeButton("Cancel",null).show()
        }.setNegativeButton("Cancel",null).show()
    }
}
