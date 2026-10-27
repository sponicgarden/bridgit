package net.finleg.bridget.tour

import android.location.Location
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.finleg.bridget.BridgeApp
import net.finleg.bridget.R
import net.finleg.bridget.api.ApiServer
import net.finleg.bridget.api.Endpoints
import net.finleg.bridget.api.ErrorCatalog
import net.finleg.bridget.api.Reply
import net.finleg.bridget.capability.Capability
import net.finleg.bridget.capability.Compass
import net.finleg.bridget.capability.Gallery
import net.finleg.bridget.capability.LocationReader
import net.finleg.bridget.capability.isoAt
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** City tour (D30, 8.25): tour kinds Rahul can pick. */
object TourKinds {
    val all = listOf("history", "architecture", "environment", "food", "art", "anything")
    val labels = mapOf("history" to "History", "architecture" to "Architecture", "environment" to "Environment",
        "food" to "Food", "art" to "Art", "anything" to "Anything interesting")
    val intervals = listOf(30, 60, 120, 300)
    const val MAX_HOURS = 4
}

/** What was said during the tour, for the tour screen's history. */
data class Said(val at: Long, val who: String, val text: String)

data class Tour(
    val id: String, val agentId: String, val agent: String, val kinds: List<String>, val intervalS: Int,
    val startedAt: Long, val paused: Boolean = false, val lastTickAt: Long = 0, val ticks: Int = 0,
    val distanceM: Double = 0.0, val lastLat: Double? = null, val lastLon: Double? = null, val photos: Int = 0,
    val heading: Double? = null, val caption: String? = null, val said: List<Said> = emptyList(),
) {
    fun json(): JSONObject = JSONObject().put("tour_id", id).put("kinds", JSONArray(kinds)).put("interval_s", intervalS)
        .put("started_at", isoAt(startedAt)).put("paused", paused).put("ticks", ticks)
        .put("last_tick_at", if (lastTickAt > 0) isoAt(lastTickAt) else JSONObject.NULL)
        .put("distance_m", Math.round(distanceM)).put("photos_sent", photos)
}

/**
 * Runs one tour at a time. Ticks go to the tour's agent as `tour.tick` events (section 9), the
 * agent answers with /v1/speaker (tour_id). Rahul's questions go as `tour.question`.
 * Location is sent only to that agent, only during the tour (TOUR-02).
 */
class TourManager(private val app: BridgeApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val _state = MutableStateFlow<Tour?>(null)
    val state: StateFlow<Tour?> get() = _state
    private val reader = LocationReader(app)

    fun current(): Tour? = _state.value

    /** What's missing before a tour can start (TOUR-01); empty = ready. */
    fun missing(): List<String> = buildList {
        if (!app.settings.isOn("location")) add("Location switch")
        if (reader.precision() == null) add("Location permission")
        if (!reader.enabled()) add("Android's Location setting")
        if (!app.settings.isOn("speaker")) add("Speaker switch")
        if (!app.captureNotificationsOn()) add("Notifications")
    }

    @Synchronized
    fun start(agentId: String, kinds: List<String>, intervalS: Int): Tour {
        val a = app.agents.get(agentId) ?: throw IllegalArgumentException("No such agent")
        require(kinds.isNotEmpty() && kinds.all { it in TourKinds.all }) { "Pick at least one kind of tour" }
        require(intervalS in TourKinds.intervals) { "Bad interval" }
        end("a new tour started")
        val t = Tour("t" + UUID.randomUUID().toString().replace("-", "").take(10), a.id, a.name, kinds, intervalS, System.currentTimeMillis())
        _state.value = t
        app.interactions.event(a.id, "tour.started", JSONObject().put("tour_id", t.id).put("kinds", JSONArray(kinds))
            .put("interval_s", intervalS).put("how_to_reply", "POST /v1/speaker/say {\"text\",\"tour_id\"} or /v1/speaker/play; newer narration replaces queued"))
        app.log.system("audit", "agent", "You started a ${kinds.joinToString("/")} city tour with ${a.name}")
        job = scope.launch { loop(t.id) }
        return t
    }

    private suspend fun loop(id: String) {
        while (scope.isActive) {
            val t = _state.value?.takeIf { it.id == id } ?: return
            if (System.currentTimeMillis() - t.startedAt > TourKinds.MAX_HOURS * 3_600_000L) { end("time limit"); return }
            if (!t.paused) runCatching { tick(t) }.onFailure {
                app.log.system("diagnostic", "service", "Tour tick failed: ${it.javaClass.simpleName}", level = "warn")
            }
            delay(t.intervalS * 1000L)
        }
    }

    private suspend fun tick(t: Tour) {
        val fix: Location? = reader.fresh(high = false, timeoutMs = 10_000) ?: reader.lastKnown()
        val compass = Compass.read(app)
        val since = if (t.lastTickAt > 0) t.lastTickAt else t.startedAt
        val photos = if (app.settings.isOn("gallery") && Gallery(app).accessMode() != null)
            Gallery(app).recent(20).filter { it.addedAt > since }.take(10) else emptyList()
        var moved = 0.0
        if (fix != null && t.lastLat != null && t.lastLon != null) {
            val r = FloatArray(1); Location.distanceBetween(t.lastLat, t.lastLon, fix.latitude, fix.longitude, r); moved = r[0].toDouble()
        }
        val data = JSONObject().put("tour_id", t.id).put("kinds", JSONArray(t.kinds)).put("tick", t.ticks + 1)
            .put("location", fix?.let { LocationReader.json(it, reader.precision() ?: "none", ::isoAt) } ?: JSONObject.NULL)
            .put("compass", compass?.let { Compass.json(it) } ?: JSONObject.NULL)
            .put("moved_m", Math.round(moved))
            .put("new_photos", JSONArray(photos.map { p -> JSONObject().put("id", p.id.toString()).put("taken_at", isoAt(p.takenAt))
                .put("fetch", "/v1/gallery/photo/${p.id}?size=agent") }))
            .put("playing", app.speaker.current?.takeIf { it.agentId == t.agentId }?.caption ?: JSONObject.NULL)
        app.interactions.event(t.agentId, "tour.tick", data)
        _state.value = _state.value?.takeIf { it.id == t.id }?.copy(lastTickAt = System.currentTimeMillis(), ticks = t.ticks + 1,
            distanceM = t.distanceM + moved, lastLat = fix?.latitude ?: t.lastLat, lastLon = fix?.longitude ?: t.lastLon,
            photos = t.photos + photos.size, heading = compass?.azimuthDeg ?: t.heading)
    }

    /** The agent's narration arrived (for the caption and history). */
    fun narrated(tourId: String, text: String?) {
        val t = _state.value?.takeIf { it.id == tourId } ?: return
        if (text.isNullOrBlank()) return
        _state.value = t.copy(caption = text, said = (t.said + Said(System.currentTimeMillis(), t.agent, text)).takeLast(100))
    }

    /** Rahul's question (TOUR, owner request): typed, or the transcript of what he said. */
    suspend fun ask(text: String, spoken: Boolean) {
        val t = _state.value ?: return
        val q = text.trim().take(1000).ifEmpty { return }
        val fix = reader.lastKnown()
        val compass = Compass.read(app)
        val latest = if (app.settings.isOn("gallery") && Gallery(app).accessMode() != null) Gallery(app).recent(1).firstOrNull() else null
        app.interactions.event(t.agentId, "tour.question", JSONObject().put("tour_id", t.id).put("question", q).put("spoken", spoken)
            .put("location", fix?.let { LocationReader.json(it, reader.precision() ?: "none", ::isoAt) } ?: JSONObject.NULL)
            .put("compass", compass?.let { Compass.json(it) } ?: JSONObject.NULL)
            .put("latest_photo", latest?.let { JSONObject().put("id", it.id.toString()).put("fetch", "/v1/gallery/photo/${it.id}?size=agent") } ?: JSONObject.NULL)
            .put("how_to_answer", "POST /v1/speaker/say {\"text\",\"tour_id\",\"urgent\":true}"))
        _state.value = t.copy(said = (t.said + Said(System.currentTimeMillis(), "You", q)).takeLast(100))
        app.log.system("audit", "agent", "You asked ${t.agent} a question on the tour")
    }

    fun setPaused(p: Boolean) { _state.value = _state.value?.copy(paused = p) }

    @Synchronized
    fun end(reason: String) {
        val t = _state.value ?: return
        job?.cancel(); job = null
        _state.value = null
        app.speaker.stop(t.agentId)
        app.interactions.event(t.agentId, "tour.ended", JSONObject().put("tour_id", t.id).put("reason", reason)
            .put("duration_s", (System.currentTimeMillis() - t.startedAt) / 1000).put("distance_m", Math.round(t.distanceM))
            .put("ticks", t.ticks).put("photos_sent", t.photos))
        app.log.system("audit", "agent", "City tour with ${t.agent} ended ($reason)")
    }
}

/** TOUR-03: the tour's agent reads or ends its tour. */
class TourModule(app: BridgeApp) : Capability(app) {
    override val id = "tour"
    override val titleRes = R.string.cap_tour
    override val descriptionRes = R.string.cap_tour_desc
    override val group = "apps"
    override val endpoints = listOf(Endpoints.TOUR_GET, Endpoints.TOUR_END)

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(Endpoints.TOUR_GET) {
            val t = app.tours.current()?.takeIf { it.agentId == agent!!.id }
            Reply.Json(JSONObject().put("tour", t?.json() ?: JSONObject.NULL))
        }
        endpoint(Endpoints.TOUR_END) {
            val t = app.tours.current()?.takeIf { it.agentId == agent!!.id } ?: throw ErrorCatalog.error("not_found", "You have no tour running.")
            app.tours.end("ended by ${agentName}")
            Reply.Json(JSONObject().put("ended", t.id))
        }
    }
}
