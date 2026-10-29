package net.finleg.bridget.platform

import android.app.KeyguardManager
import android.content.Intent
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import net.finleg.bridget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One authority and operation path for HTTP, MCP, phone UI and local workflows. */
class AgentPlatform(val app: BridgeApp) {
    val store = PlatformStore(app)
    val epoch = UUID.randomUUID().toString()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val tools by lazy { DeviceTools(app) }
    val workflows by lazy { WorkflowEngine(this) }
    val delivery by lazy { DeliveryManager(this) }
    val files by lazy { DeviceFiles(app,store) }
    val monitor by lazy { CameraMonitor(this) }
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val sensorLock = Mutex()
    private val credentialEpochs = java.util.concurrent.ConcurrentHashMap<String,String>()
    @Volatile var activeSensorAgent: String? = null; private set
    @Volatile var activeSensorScope: String? = null; private set

    init {
        // Preserve only existing authority; new capabilities and newly paired agents start denied.
        if (store.setting("legacy_migrated") != "true") {
            app.policy.agents().forEach { a -> listOf("camera.snapshot","microphone.clip","gallery.read","audit.read").forEach { store.grant(a.id,it,Long.MAX_VALUE) } }
            store.setting("legacy_migrated","true",true)
        }
        store.list(kind="operation",limit=1000).filter { it.optString("status") in setOf("running","accepted") }.forEach {
            store.save(it.getString("agent_id"),"operation",it.put("status","unknown_outcome").put("error","interrupted_reconcile_before_retry"))
        }
        store.list(kind="session").filter { it.optString("status") == "active" }.forEach { store.save(it.getString("agent_id"),"session",it.put("status","interrupted")) }
        store.list(kind="stream").filter { it.optString("status") == "active" }.forEach { store.save(it.getString("agent_id"),"stream",it.put("status","interrupted")) }
        scope.launch {
            store.list("owner","restore").forEach { record -> launch { delay((record.optLong("at")-System.currentTimeMillis()).coerceAtLeast(0)); runCatching { tools.restoreSettings(record) } } }
        }
        store.list(kind="execution").filter { it.optString("status") in setOf("running","dispatching") }.forEach { execution ->
            store.save(execution.getString("agent_id"),"execution",execution.put("status","unknown_outcome"))
            store.find(execution.optString("workflow_id"))?.let { store.save(it.getString("agent_id"),"workflow",it.put("status","blocked").put("blocked_reason","interrupted_reconcile_execution")) }
        }
        scope.launch { while(isActive) { delay(250); runCatching { sweep() } } }
    }
    fun hasGrant(agent: String, id: String): Boolean = app.policy.agents().any { it.id == agent } && store.allowed(agent,id)
    fun authorize(agent: String, id: String) {
        val cap = Capabilities.all.firstOrNull { it.id == id } ?: throw ApiError(422,"unknown_scope","Unknown capability")
        if (!hasGrant(agent,id)) throw ApiError(403,"agent_not_allowed","Owner must grant $id to this agent")
        val blockers = Capabilities.blockers(app,cap)
        if (blockers.isNotEmpty()) throw ApiError(403,blockers.first(),"Open Device tools on the phone: ${cap.label}",mapOf("blockers" to JSONArray(blockers),"user_action_required" to true))
    }
    fun grant(agent: String,id: String,until: Long) { require(Capabilities.all.any { it.id == id }); store.grant(agent,id,until); store.emit(agent,"grant.changed",id) }
    fun revoke(agent: String,id: String? = null) { store.revoke(agent,id); invalidate(agent); store.emit(agent,"grant.revoked",id ?: "all") }
    fun invalidateScope(id: String) { app.policy.agents().forEach { a -> invalidate(a.id) } }
    fun invalidate(agent: String) {
        jobs.entries.filter { it.key.startsWith("$agent:") }.forEach { it.value.cancel() }
        store.list(agent,"session").filter { it.optString("status") in setOf("active","awaiting_owner") }.forEach { closeSession(it,"revoked") }
        store.list(agent,"interaction").filter { it.optString("status") == "pending" }.forEach { store.save(agent,"interaction",it.put("status","cancelled")); store.emit(agent,"interaction.cancelled",it.getString("id")) }
        store.list(agent,"operation",1000).filter { it.optString("status") in setOf("accepted","awaiting_owner","running") }.forEach { store.save(agent,"operation",it.put("status",if (it.optString("status") == "running") "unknown_outcome" else "cancelled")) }
        monitor.stopAgent(agent)
        workflows.pauseAgent(agent)
    }
    fun stopAll() { app.policy.agents().forEach { invalidate(it.id) }; ProjectionService.instance?.stopSelf(); tools.stopAudio() }
    fun getAgent(id: String) = app.policy.agents().firstOrNull { it.id == id } ?: throw ApiError(401,"unauthorized","Agent was removed")
    fun changed() { app.sendBroadcast(Intent(BridgeService.ACTION_STATE).setPackage(app.packageName)) }
    private fun sweep() {
        app.policy.agents().forEach { a ->
            val old = credentialEpochs.put(a.id,a.hash)
            if (old != null && old != a.hash) invalidate(a.id)
        }
        val now = System.currentTimeMillis()
        store.list(kind="interaction").filter { it.optString("status") == "pending" && it.optLong("expires_at") <= now }.forEach { store.save(it.getString("agent_id"),"interaction",it.put("status","expired")); store.emit(it.getString("agent_id"),"interaction.expired",it.getString("id")) }
        store.list(kind="operation",limit=1000).filter { it.optString("status")=="awaiting_owner" && it.optLong("expires_at")<=now }.forEach { store.save(it.getString("agent_id"),"operation",it.put("status","expired")) }
        store.list(kind="session").filter { it.optString("status") == "active" }.forEach { s ->
            if (s.optLong("expires_at") <= now || s.optLong("heartbeat_until") <= now || !hasGrant(s.getString("agent_id"),"screen.control") || !Capabilities.enabled(app,"screen.control") || !Capabilities.visible(app) || app.getSystemService(KeyguardManager::class.java).isDeviceLocked) closeSession(s,"expired")
        }
    }
    fun session(agent: String,id: String): JSONObject {
        val s = store.require(id,agent,"session")
        if (s.optString("status") != "active" || s.optString("epoch") != epoch || s.optLong("expires_at") <= System.currentTimeMillis() || s.optLong("heartbeat_until") <= System.currentTimeMillis()) throw ApiError(409,"session_inactive","Start or renew a control session on the phone")
        authorize(agent,"screen.control"); return s
    }
    fun closeSession(s: JSONObject,state: String) {
        val agent=s.getString("agent_id")
        store.list(agent,"operation",1000).filter { it.optString("command")=="ui.action" && it.optJSONObject("input")?.optString("session_id")==s.optString("id") && it.optString("status") in setOf("accepted","awaiting_owner","running") }.forEach { cancelOperation(agent,it.getString("id")) }
        store.save(agent,"session",s.put("status",state)); DeviceControlService.instance?.clearObservation()
        store.emit(s.getString("agent_id"),"session.$state",s.getString("id")); changed()
    }
    fun approveSession(id: String) {
        val s = store.find(id,kind="session") ?: return
        if (s.optString("status") != "awaiting_owner" || s.optLong("request_expires_at") <= System.currentTimeMillis()) throw ApiError(409,"approval_expired","Request expired")
        authorize(s.getString("agent_id"),"screen.control")
        if (store.list(kind="session").any { it.optString("status") == "active" && it.optLong("expires_at") > System.currentTimeMillis() && it.optLong("heartbeat_until") > System.currentTimeMillis() }) throw ApiError(409,"controller_busy","Stop the current session first")
        val now = System.currentTimeMillis()
        store.save(s.getString("agent_id"),"session",s.put("status","active").put("expires_at",now+s.getInt("duration_seconds")*1000L).put("heartbeat_until",now+60_000).put("epoch",epoch).put("fence",UUID.randomUUID().toString()))
        DeviceControlService.instance?.showController(s.getString("agent_id")); store.emit(s.getString("agent_id"),"session.active",id); changed()
    }
    fun answer(id: String,value: String,dismiss: Boolean = false) {
        val item = store.find(id,kind="interaction") ?: return
        synchronized(store) {
            if (item.optString("status") != "pending" || item.optLong("expires_at") <= System.currentTimeMillis()) throw ApiError(409,"interaction_closed","Question is already closed")
            if (value.length > 4096) throw ApiError(422,"invalid_parameter","Answer is too long")
            store.save(item.getString("agent_id"),"interaction",item.put("status",if(dismiss) "dismissed" else "answered").put("answer",value).put("answered_at",System.currentTimeMillis()))
            store.emit(item.getString("agent_id"),"interaction.answered",id)
        }
        changed()
    }
    fun interaction(agent: String,b: JSONObject): JSONObject {
        b.only("kind","title","text","choices","expires_seconds")
        val kind = b.text("kind",16,"question"); if (kind !in listOf("question","notice")) throw ApiError(422,"invalid_parameter","kind must be notice or question")
        if (store.list(agent,"interaction").count { it.optString("status") == "pending" } >= 20) throw ApiError(429,"interaction_limit","Too many unanswered interactions")
        val a = getAgent(agent)
        val item = store.save(agent,"interaction",JSONObject().put("interaction_kind",kind).put("title",b.text("title",120)).put("text",b.text("text",4096,""))
            .put("choices",if(b.has("choices")) JSONArray(b.strings("choices",6)) else JSONArray()).put("status",if(kind=="notice") "delivered" else "pending")
            .put("identity",AgentBranding.json(a)).put("expires_at",System.currentTimeMillis()+b.whole("expires_seconds",3600,30,86400)*1000L))
        store.emit(agent,"interaction.created",item.getString("id")); PlatformNotifications.show(app,item); changed(); return item
    }
    suspend fun <T> capture(agent: String,id: String,block: suspend () -> T): T = coroutineScope {
        authorize(agent,id)
        if (app.getSystemService(KeyguardManager::class.java).isDeviceLocked) throw ApiError(409,"device_locked","Unlock the phone to capture")
        if (!sensorLock.tryLock()) throw ApiError(409,"sensor_busy","A sensor operation already owns the camera/microphone")
        val credential = getAgent(agent).hash
        activeSensorAgent = agent; activeSensorScope = id
        val parent = currentCoroutineContext()[Job]!!
        val watchdog = launch {
            while (isActive) { delay(125); if (!runCatching { authorize(agent,id); getAgent(agent).hash == credential }.getOrDefault(false) || app.getSystemService(KeyguardManager::class.java).isDeviceLocked) parent.cancel(CancellationException("Capture authority or visibility lost")) }
        }
        try { BridgeService.running?.showCapture("${getAgent(agent).name}: ${id.substringBefore('.')}",agent); block() }
        finally { watchdog.cancel(); activeSensorAgent=null; activeSensorScope=null; BridgeService.running?.showCapture(null); sensorLock.unlock() }
    }
    private fun sensitive(command: String,args: JSONObject): Boolean = command in setOf("ui.action","device.action","notification.reply","peripheral.action","translation.download","speech.transcribe","stream.start","alert.trigger")
    fun mutate(agent: String, command: String,args: JSONObject,key: String): JSONObject {
        val scopeId = commandScope(command,args)
        authorize(agent,scopeId)
        val (op,fresh) = store.reserve(agent,key,command,args)
        if (!fresh) return publicOperation(op)
        op.put("expires_at",System.currentTimeMillis()+5*60_000).put("identity",AgentBranding.json(getAgent(agent)))
        val pending = sensitive(command,args)
        store.save(agent,"operation",op.put("status",if(pending) "awaiting_owner" else "accepted"))
        if (!pending) launchOperation(op) else { PlatformNotifications.show(app,op); store.emit(agent,"operation.awaiting_owner",op.getString("id")); changed() }
        return publicOperation(store.require(op.getString("id"),agent,"operation"))
    }
    fun approveOperation(id: String) {
        val op = store.find(id,kind="operation") ?: return
        synchronized(store) {
            if (op.optString("status") != "awaiting_owner" || op.optLong("expires_at") <= System.currentTimeMillis()) throw ApiError(409,"approval_expired","Request is no longer pending")
            // An operation is immutable; approvals bind to the canonical command/input digest.
            if (op.getString("digest") != PlatformStore.digest(JSONObject().put("command",op.getString("command")).put("body",op.getJSONObject("input")))) throw ApiError(409,"approval_changed","Action changed")
            authorize(op.getString("agent_id"),commandScope(op.getString("command"),op.getJSONObject("input")))
            store.save(op.getString("agent_id"),"operation",op.put("status","accepted"))
        }
        launchOperation(op)
    }
    fun cancelOperation(agent: String,id: String): JSONObject {
        val op = store.require(id,agent,"operation")
        jobs["$agent:$id"]?.cancel()
        if (op.optString("status") !in setOf("completed","failed","cancelled")) store.save(agent,"operation",op.put("status",if (op.optString("status")=="running") "unknown_outcome" else "cancelled"))
        store.emit(agent,"operation.cancelled",id); changed(); return publicOperation(store.require(id,agent,"operation"))
    }
    private fun launchOperation(original: JSONObject) {
        val agent = original.getString("agent_id"); val id = original.getString("id")
        val job = scope.launch(start=CoroutineStart.LAZY) {
            var op = store.require(id,agent,"operation")
            try {
                authorize(agent,commandScope(op.getString("command"),op.getJSONObject("input")))
                if (op.optString("status") != "accepted") return@launch
                op = store.save(agent,"operation",op.put("status","running"))
                val result = withTimeout(150_000) { execute(agent,op.getString("command"),op.getJSONObject("input")) }
                // Revocation cannot be overwritten with a late success.
                if (store.require(id,agent).optString("status") == "running") store.save(agent,"operation",op.put("status","completed").put("result",result).put("finished_at",System.currentTimeMillis()))
            } catch(e: CancellationException) {
                store.save(agent,"operation",op.put("status","unknown_outcome").put("error","cancelled_reconcile_before_retry")); throw e
            } catch(e: Exception) {
                store.save(agent,"operation",op.put("status","failed").put("error",if(e is ApiError) e.code else "execution_failed").put("message",if(e is ApiError) e.message else "Operation failed; inspect readiness"))
            } finally {
                jobs.remove("$agent:$id"); store.emit(agent,"operation.finished",id)
                app.audit.append(JSONObject().put("agent_id",agent).put("endpoint","platform:${op.optString("command")}").put("operation_id",id).put("result",store.find(id,agent)?.optString("status")))
                changed()
            }
        }
        jobs["$agent:$id"] = job; job.start()
    }
    private fun commandScope(command:String,args:JSONObject) = if(command=="device.action") DeviceTools.scopeFor(args.optString("type")) else Commands.scope(command)
    fun publicOperation(op: JSONObject): JSONObject = PlatformStore.copy(op).apply { remove("input"); remove("identity"); remove("digest") }
    suspend fun read(agent: String,command: String,args: JSONObject): JSONObject {
        if(command == "capabilities") return Capabilities.describe(app,getAgent(agent))
        if(command !in setOf("operation.get","operation.list","session.list")) authorize(agent,Commands.scope(command))
        return when(command) {
            "events" -> {
                val after = args.optLong("after",0); val wait=args.whole("wait",0,0,55); val end=System.currentTimeMillis()+wait*1000L
                var result=store.events(agent,after)
                while(result.getJSONArray("events").length()==0 && System.currentTimeMillis()<end) { delay(200); authorize(agent,"events.read"); result=store.events(agent,after) }
                result.put("epoch",epoch).put("device_id",store.deviceId)
            }
            "operation.get" -> publicOperation(store.require(args.text("id"),agent,"operation"))
            "operation.list" -> JSONObject().put("operations",JSONArray(store.list(agent,"operation").map(::publicOperation)))
            "session.list" -> JSONObject().put("sessions",JSONArray(store.list(agent,"session")))
            "screen.observe" -> DeviceControlService.instance?.observe(agent,args) ?: ProjectionService.instance?.observe(agent) ?: throw ApiError(409,"screen_share_required","Start screen sharing on the phone")
            "screen.wait" -> DeviceControlService.instance?.waitFor(agent,args) ?: throw ApiError(409,"accessibility_required","Enable private control")
            "interaction.get" -> {
                val end=System.currentTimeMillis()+args.whole("wait",0,0,55)*1000L
                var item=store.require(args.text("id"),agent,"interaction")
                while(item.optString("status")=="pending" && System.currentTimeMillis()<end) { delay(200); authorize(agent,"interactions"); item=store.require(args.text("id"),agent,"interaction") }; item
            }
            "interaction.list" -> JSONObject().put("interactions",JSONArray(store.list(agent,"interaction")))
            "notification.list" -> NotificationBridge.instance?.list(agent) ?: throw ApiError(409,"notification_access_required","Enable notification access")
            "file.list" -> files.list(agent)
            "file.get" -> files.get(agent,args.text("id"))
            "alert.list" -> delivery.list(agent)
            "workflow.list" -> JSONObject().put("workflows",JSONArray(store.list(agent,"workflow")))
            "workflow.validate" -> workflows.validate(args)
            "stream.status" -> monitor.status(agent,args.text("id"))
            "stream.frame" -> monitor.frame(agent,args.text("id"))
            "phone.status", "phone.context", "location.fix", "sensors.read", "images.ocr", "images.barcode", "translation.run", "peripheral.list", "diagnostics" -> tools.read(agent,command,args)
            else -> throw ApiError(404,"unknown_tool","Unknown read tool")
        }
    }
    private suspend fun execute(agent: String,command: String,args: JSONObject): JSONObject = when(command) {
        "interaction.create" -> interaction(agent,args)
        "interaction.cancel" -> store.require(args.text("id"),agent,"interaction").also { store.save(agent,"interaction",it.put("status","cancelled")); store.emit(agent,"interaction.cancelled",it.getString("id")) }
        "session.request" -> {
            args.only("apps","duration_seconds","purpose")
            val apps=args.strings("apps",10)
            if(apps.isEmpty() || apps.any { !it.matches(Regex("[a-zA-Z][a-zA-Z0-9_.]+")) || it==app.packageName }) throw ApiError(422,"invalid_parameter","Choose at least one external app")
            store.save(agent,"session",JSONObject().put("apps",JSONArray(apps)).put("duration_seconds",args.whole("duration_seconds",300,30,1800)).put("purpose",args.text("purpose",256)).put("status","awaiting_owner").put("request_expires_at",System.currentTimeMillis()+300_000).put("identity",AgentBranding.json(getAgent(agent)))).also { store.emit(agent,"session.requested",it.getString("id")); PlatformNotifications.show(app,it); changed() }
        }
        "session.heartbeat" -> session(agent,args.text("id")).let { store.save(agent,"session",it.put("heartbeat_until",minOf(it.getLong("expires_at"),System.currentTimeMillis()+60_000))) }
        "session.stop" -> store.require(args.text("id"),agent,"session").also { closeSession(it,"stopped") }
        "ui.action" -> DeviceControlService.instance?.act(agent,args) ?: throw ApiError(409,"accessibility_required","Enable private control")
        "device.action" -> tools.action(agent,args)
        "speech.transcribe" -> tools.transcribe(agent,args)
        "translation.download" -> tools.downloadTranslation(args)
        "notification.reply" -> NotificationBridge.instance?.reply(agent,args) ?: throw ApiError(409,"notification_access_required","Enable notification access")
        "file.put" -> files.put(agent,args)
        "workflow.propose" -> workflows.propose(agent,args)
        "workflow.pause" -> workflows.pause(agent,args.text("id"))
        "workflow.run" -> workflows.runOnce(agent,args.text("id"))
        "stream.start" -> monitor.start(agent,args)
        "stream.stop" -> monitor.stop(agent,args.text("id"))
        "peripheral.action" -> tools.peripheralAction(agent,args)
        "alert.trigger" -> delivery.trigger(agent,args)
        "alert.cancel" -> delivery.cancel(agent,args.text("id"))
        "note.create","feedback.create" -> {
            val text=args.text("text",4096); val kind=if(command=="note.create") "note" else "feedback"
            store.save(agent,kind,JSONObject().put("text",text).put("status","new")).also { store.emit(agent,"$kind.created",it.getString("id")) }
        }
        else -> throw ApiError(404,"unknown_tool","Unknown action tool")
    }
}

object Commands {
    val reads = setOf("capabilities","events","operation.get","operation.list","session.list","screen.observe","screen.wait","interaction.get","interaction.list","notification.list","file.list","file.get","workflow.list","workflow.validate","stream.status","stream.frame","phone.status","phone.context","location.fix","sensors.read","images.ocr","images.barcode","translation.run","peripheral.list","diagnostics","alert.list")
    val mutations = setOf("interaction.create","interaction.cancel","session.request","session.heartbeat","session.stop","ui.action","device.action","speech.transcribe","translation.download","notification.reply","file.put","workflow.propose","workflow.pause","workflow.run","stream.start","stream.stop","peripheral.action","note.create","feedback.create","alert.trigger","alert.cancel")
    fun scope(name: String): String = when {
        name.startsWith("session.") || name=="ui.action" || name=="screen.wait" -> "screen.control"
        name=="screen.observe" -> "screen.observe"
        name.startsWith("interaction.") || name=="note.create" || name=="feedback.create" -> "interactions"
        name=="events" -> "events.read"
        name.startsWith("notification.") -> if(name.endsWith("reply")) "notifications.reply" else "notifications.read"
        name.startsWith("file.") -> "files"
        name.startsWith("workflow.") -> "workflows"
        name.startsWith("stream.") -> "monitoring"
        name=="device.action" -> "intents" // Adapters additionally enforce their specific scope.
        name.startsWith("alert.") -> "alerts"
        name.startsWith("translation.") -> "translation"
        name.startsWith("peripheral.") -> "peripherals"
        name in setOf("phone.status","phone.context","diagnostics","operation.get","operation.list") -> "diagnostics"
        name=="location.fix" -> "location"
        name=="sensors.read" -> "sensors"
        name in setOf("images.ocr","images.barcode","speech.transcribe") -> name
        else -> throw ApiError(404,"unknown_tool","Unknown tool")
    }
}
