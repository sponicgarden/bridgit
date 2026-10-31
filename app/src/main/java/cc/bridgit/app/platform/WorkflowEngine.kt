package cc.bridgit.app.platform

import android.app.job.*
import android.content.*
import android.os.BatteryManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import cc.bridgit.app.*
import org.json.JSONArray
import org.json.JSONObject

/** A closed, bounded DSL. No script, shell, downloads, arbitrary URLs or UI automation. */
class WorkflowEngine(private val runtime:AgentPlatform) {
    private val locks=java.util.concurrent.ConcurrentHashMap<String,Mutex>()
    private val active=java.util.concurrent.ConcurrentHashMap<String,Job>()
    fun validate(b:JSONObject):JSONObject {
        b.only("name","trigger","steps","ui")
        b.text("name",120)
        val trigger=b.optJSONObject("trigger") ?: throw ApiError(422,"invalid_workflow","trigger is required")
        val type=trigger.text("type",32)
        when(type) {
            "button","charging" -> trigger.only("type")
            "interval" -> { trigger.only("type","seconds"); trigger.whole("seconds",900,30,86400) }
            "geofence" -> {
                trigger.only("type","latitude","longitude","radius_m")
                if(!trigger.has("latitude") || !trigger.has("longitude") || !trigger.optDouble("latitude").isFinite() || !trigger.optDouble("longitude").isFinite() || trigger.optDouble("latitude") !in -90.0..90.0 || trigger.optDouble("longitude") !in -180.0..180.0) throw ApiError(422,"invalid_workflow","Invalid geofence coordinates")
                trigger.whole("radius_m",200,100,10000)
            }
            else -> throw ApiError(422,"invalid_workflow","Triggers: button, interval, charging, geofence")
        }
        val steps=b.optJSONArray("steps") ?: throw ApiError(422,"invalid_workflow","steps are required")
        if(steps.length() !in 1..32 || b.toString().toByteArray().size>65536) throw ApiError(422,"invalid_workflow","Use 1–32 steps, at most 64 KiB")
        val scopes=linkedSetOf("workflows")
        if(type=="geofence") scopes.add("location")
        for(i in 0 until steps.length()) {
            val step=steps.optJSONObject(i) ?: throw ApiError(422,"invalid_workflow","Each step must be an object")
            step.only("type","text","title","seconds","destinations")
            when(step.text("type",32)) {
                "notice" -> { step.text("title",120); step.text("text",4096,""); scopes.add("interactions") }
                "speak" -> { step.text("text",4096); scopes.add("audio.speak") }
                "ring" -> { step.whole("seconds",10,1,30); scopes.add("phone.control") }
                "alert" -> { step.text("text",1024); runtime.delivery.validateDestinations(step.strings("destinations",3)); scopes.add("alerts") }
                "event" -> step.text("text",120)
                else -> throw ApiError(422,"invalid_workflow","Steps: notice, speak, ring, event, alert")
            }
        }
        val ui=b.optJSONObject("ui")
        if(ui!=null) { ui.only("template","title","text"); if(ui.text("template",16) !in setOf("button","status","help")) throw ApiError(422,"invalid_workflow","Unknown native template"); ui.text("title",120,""); ui.text("text",1024,"") }
        return JSONObject().put("valid",true).put("required_scopes",JSONArray(scopes.toList())).put("digest",PlatformStore.digest(b)).put("limits",JSONObject().put("max_steps",32).put("max_run_seconds",120)).put("scheduling","Sub-minute intervals require the active listener; Android may defer maintenance jobs")
    }
    fun propose(agent:String,b:JSONObject):JSONObject {
        val checked=validate(b)
        if(runtime.store.list(agent,"workflow").size>=8) throw ApiError(429,"workflow_limit","At most eight workflow proposals per agent")
        return runtime.store.save(agent,"workflow",JSONObject().put("definition",b).put("digest",checked.getString("digest")).put("required_scopes",checked.getJSONArray("required_scopes")).put("status","awaiting_owner").put("revision",1).put("identity",AgentBranding.json(runtime.getAgent(agent)))).also { runtime.store.emit(agent,"workflow.proposed",it.getString("id")); PlatformNotifications.show(runtime.app,it); runtime.changed() }
    }
    fun activate(id:String) {
        val record=runtime.store.find(id,kind="workflow") ?: return
        validate(record.getJSONObject("definition"))
        if(record.getString("digest")!=PlatformStore.digest(record.getJSONObject("definition"))) throw ApiError(409,"workflow_changed","Workflow changed")
        record.strings("required_scopes").forEach { runtime.authorize(record.getString("agent_id"),it) }
        if(record.optString("status") !in setOf("awaiting_owner","paused","blocked")) throw ApiError(409,"workflow_state","Workflow cannot be activated")
        runtime.store.save(record.getString("agent_id"),"workflow",record.put("status","active").put("approved_digest",record.getString("digest")).put("next_at",System.currentTimeMillis()+1000))
        schedule(); runtime.changed()
    }
    fun pause(agent:String,id:String):JSONObject {
        val r=runtime.store.require(id,agent,"workflow"); active[id]?.cancel()
        return runtime.store.save(agent,"workflow",r.put("status","paused")).also { runtime.store.emit(agent,"workflow.paused",id); runtime.changed() }
    }
    fun pauseAgent(agent:String) { runtime.store.list(agent,"workflow").filter { it.optString("status")=="active" }.forEach { pause(agent,it.getString("id")) } }
    private fun schedule() {
        val scheduler=runtime.app.getSystemService(JobScheduler::class.java)
        scheduler.schedule(JobInfo.Builder(8702,ComponentName(runtime.app,WorkflowJobService::class.java)).setPersisted(true).setPeriodic(15*60*1000L).build())
    }
    suspend fun tick() {
        val charging=runtime.app.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0
        for(record in runtime.store.list(kind="workflow").filter { it.optString("status")=="active" }) {
            val id=record.getString("id"); val trigger=record.getJSONObject("definition").getJSONObject("trigger")
            val now=System.currentTimeMillis()
            val due=when(trigger.getString("type")) {
                "interval" -> record.optLong("next_at")<=now
                "charging" -> charging && !record.optBoolean("last_charging")
                "geofence" -> {
                    if(now-record.optLong("last_location_check")<60_000) false else {
                        record.put("last_location_check",now)
                        val location=runCatching { runtime.authorize(record.getString("agent_id"),"location"); runtime.tools.read(record.getString("agent_id"),"location.fix",JSONObject()) }.getOrNull()
                        if(location==null) false else {
                            val distance=FloatArray(1); android.location.Location.distanceBetween(trigger.getDouble("latitude"),trigger.getDouble("longitude"),location.getDouble("latitude"),location.getDouble("longitude"),distance)
                            val inside=distance[0]<=trigger.getInt("radius_m"); val entered=inside && !record.optBoolean("last_inside"); record.put("last_inside",inside); entered
                        }
                    }
                }
                else -> false
            }
            record.put("last_charging",charging)
            runtime.store.save(record.getString("agent_id"),"workflow",record)
            if(due && active[id]?.isActive!=true) {
                active[id]=runtime.scope.launch { runCatching { runOnce(record.getString("agent_id"),id) } }
            }
        }
    }
    suspend fun runOnce(agent:String,id:String):JSONObject {
        val mutex=locks.getOrPut(id) { Mutex() }
        if(!mutex.tryLock()) throw ApiError(409,"workflow_busy","Workflow already running")
        val record=runtime.store.require(id,agent,"workflow")
        try {
            if(record.optString("status")!="active" || record.optString("approved_digest")!=record.optString("digest")) throw ApiError(403,"workflow_not_active","Activate this exact workflow on the phone")
            val scopes=record.strings("required_scopes"); scopes.forEach { runtime.authorize(agent,it) }
            val definition=record.getJSONObject("definition"); val trigger=definition.getJSONObject("trigger")
            // Checkpoint before effects. Interrupted runs are reconciled instead of silently replayed.
            val execution=runtime.store.save(agent,"execution",JSONObject().put("workflow_id",id).put("status","running").put("step",0))
            runtime.store.save(agent,"workflow",record.put("last_run",System.currentTimeMillis()).put("next_at",System.currentTimeMillis()+trigger.optInt("seconds",900)*1000L))
            try {
                withTimeout(120_000) {
                    val steps=definition.getJSONArray("steps")
                    for(i in 0 until steps.length()) {
                        scopes.forEach { runtime.authorize(agent,it) }
                        if(runtime.store.require(id,agent).optString("status")!="active") throw CancellationException("Workflow paused")
                        val step=steps.getJSONObject(i)
                        runtime.store.save(agent,"execution",execution.put("step",i).put("status","dispatching"))
                        when(step.getString("type")) {
                            "notice" -> runtime.interaction(agent,JSONObject().put("kind","notice").put("title",step.getString("title")).put("text",step.optString("text")))
                            "speak" -> runtime.tools.action(agent,JSONObject().put("type","speak").put("text",step.getString("text")))
                            "ring" -> runtime.tools.action(agent,JSONObject().put("type","ring").put("duration_seconds",step.optInt("seconds",10)))
                            "alert" -> runtime.delivery.trigger(agent,JSONObject().put("destinations",step.getJSONArray("destinations")).put("text",step.getString("text")))
                            "event" -> runtime.store.emit(agent,"workflow.signal",id)
                        }
                        runtime.store.save(agent,"execution",execution.put("step",i+1).put("status","running"))
                    }
                }
                runtime.store.save(agent,"execution",execution.put("status","completed")); runtime.store.emit(agent,"workflow.completed",id)
                return JSONObject().put("execution_id",execution.getString("id")).put("status","completed")
            } catch(e:Exception) {
                runtime.store.save(agent,"execution",execution.put("status","unknown_outcome"))
                if(runtime.store.require(id,agent).optString("status")!="paused") runtime.store.save(agent,"workflow",record.put("status","blocked").put("blocked_reason",if(e is ApiError) e.code else "interrupted_review_execution")); throw e
            }
        } finally { mutex.unlock(); runtime.changed() }
    }
}
class WorkflowJobService:JobService() {
    private val jobs=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    override fun onStartJob(params:JobParameters):Boolean { jobs.launch { try { (application as BridgeApp).platform.workflows.tick() } finally { jobFinished(params,false) } }; return true }
    override fun onStopJob(params:JobParameters):Boolean { jobs.coroutineContext.cancelChildren(); return true }
    override fun onDestroy() { jobs.cancel(); super.onDestroy() }
}
