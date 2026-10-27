package net.finleg.bridget.capability

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Base64
import io.ktor.server.routing.Route
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import net.finleg.bridget.BridgeApp
import net.finleg.bridget.R
import net.finleg.bridget.api.Address
import net.finleg.bridget.api.ApiServer
import net.finleg.bridget.api.Endpoints
import net.finleg.bridget.api.ErrorCatalog
import net.finleg.bridget.api.Guide
import net.finleg.bridget.api.OpenApi
import net.finleg.bridget.api.Reply
import net.finleg.bridget.api.Req
import net.finleg.bridget.api.Sizing
import net.finleg.bridget.log.LogEntry
import net.finleg.bridget.log.LogQuery
import net.finleg.bridget.log.Percentile
import net.finleg.bridget.log.QueryError
import net.finleg.bridget.log.Redactor
import org.json.JSONArray
import org.json.JSONObject

/** The phone's address as agents should use it. */
fun BridgeApp.address(): Address {
    val s = listener.value
    return Address(s.magicDns, s.boundTo.firstOrNull { !it.contains(':') } ?: s.boundTo.firstOrNull(),
        if (s.port > 0) s.port else settings.port)
}

fun BridgeApp.appVersion(): String = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"

/** Base64 body with the 12 MB cap (API-11). */
private fun Req.envelope(img: Encoded, at: String, atKey: String, photoId: String?): JSONObject {
    val b64 = Base64.encodeToString(img.bytes, Base64.NO_WRAP)
    if (b64.length > ApiServer.MAX_PAYLOAD) throw ErrorCatalog.error("payload_too_large",
        "The result is over 12 MB as base64. Ask for a smaller size, or add raw=1.",
        if (photoId != null) mapOf("photo_id" to photoId) else emptyMap())
    bytesOut = img.bytes.size.toLong()
    val fmt = when (img.mime) { "image/jpeg" -> "jpeg"; "image/png" -> "png"; "image/heic", "image/heif" -> "heic"; else -> img.mime.substringAfter('/') }
    return JSONObject().put(atKey, at).put("width_px", img.width).put("height_px", img.height)
        .put("format", fmt).put("mime", img.mime).put("size_bytes", img.bytes.size).put("data_base64", b64)
}

private fun Req.wantsRaw(): Boolean = arg("raw").let { it == "1" || it == "true" || it == 1 || it == true }

private fun Req.rawReply(img: Encoded, headers: Map<String, String>): Reply {
    if (img.bytes.size > ApiServer.MAX_RAW) throw ErrorCatalog.error("payload_too_large", "The image is over 40 MB. Ask for a smaller size.")
    bytesOut = img.bytes.size.toLong()
    return Reply.Bytes(img.bytes, img.mime, headers + mapOf("X-Bridget-Width" to img.width.toString(), "X-Bridget-Height" to img.height.toString()))
}

// ---------------------------------------------------------------------------------------------

/** 8.1: health, capabilities, guide, openapi.json, the helper download and pairing by code. Always on. */
class StatusModule(app: BridgeApp) : Capability(app) {
    override val id = "status"
    override val titleRes = R.string.cap_status
    override val descriptionRes = R.string.cap_status_desc
    override val group = "status"
    override val endpoints = listOf(Endpoints.HEALTH, Endpoints.CAPABILITIES, Endpoints.GUIDE, Endpoints.OPENAPI, Endpoints.HELPER, Endpoints.PAIR)

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(Endpoints.HEALTH) {
            val bat = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = bat?.let { val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100); if (l >= 0) l * 100 / s else null }
            val s = app.listener.value
            Reply.Json(JSONObject().put("status", "ok").put("app", "Bridget").put("app_version", app.appVersion())
                .put("agent", agentName).put("agent_id", agent?.id)
                .put("listener_uptime_seconds", (System.currentTimeMillis() - s.startedAt) / 1000)
                .put("battery_level", level ?: JSONObject.NULL)
                .put("charging", (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
                .put("bind_address", s.boundTo.firstOrNull() ?: JSONObject.NULL).put("port", api.port)
                .put("base_url", app.address().baseUrl))
        }
        endpoint(Endpoints.CAPABILITIES) {
            val out = JSONObject()
            app.registry.modules.filter { it.switchKey != null }.forEach { m ->
                val st = m.status()
                out.put(m.id, JSONObject().put("title", st.title).put("enabled", st.enabled).put("ready", st.ready)
                    .put("blocked_reason", st.blockedReason ?: JSONObject.NULL).put("details", st.details))
            }
            Reply.Json(JSONObject().put("capabilities", out).put("notifications", app.captureNotificationsOn())
                .put("listener", JSONObject().put("camera_ready", app.listener.value.cameraReady).put("mic_ready", app.listener.value.micReady)))
        }
        endpoint(Endpoints.GUIDE) {
            Reply.Text(Guide.build(agentName, app.appVersion(), app.address(), app.registry.caps(), app.settings.logVisibility,
                agent?.developer == true), "text/markdown; charset=utf-8")
        }
        endpoint(Endpoints.OPENAPI) { Reply.Json(OpenApi.build(app.appVersion(), app.address())) }
        endpoint(Endpoints.HELPER) {
            val src = app.assets.open("bridget-helper.py").use { it.readBytes() }
            Reply.Bytes(src, "text/x-python; charset=utf-8", mapOf("Content-Disposition" to "attachment; filename=\"bridget\""))
        }
        endpoint(Endpoints.PAIR) {
            if (!app.pairing.isOpen()) throw ErrorCatalog.error("pairing_closed", "No pairing window is open. Ask Rahul to open one (Add agent, then Pair with code).")
            val w = app.pairing.redeem(body.optString("code", ""))
                ?: throw ErrorCatalog.error("unauthorized", "Wrong pairing code.")
            message = "${w.agentName} fetched its key with a pairing code"
            data = JSONObject().put("agent_id", w.agentId)
            Reply.Json(JSONObject().put("key", w.key).put("agent", w.agentName).put("base_url", app.address().baseUrl))
        }
    }
}

// ---------------------------------------------------------------------------------------------

/** Readiness shared by the capture modules: permission, notifications (PRD-05), service type (ARC-03). */
private fun Capability.captureBlocked(app: BridgeApp, permission: String, ready: Boolean): String? = when {
    !app.granted(permission) -> "permission:" + permission.substringAfterLast('.')
    !app.captureNotificationsOn() -> "notifications_off"
    !app.listener.value.running -> "listener_off"
    !ready -> "open_app_once"
    else -> null
}

private suspend fun <T> ApiServer.withSensor(lock: Mutex, note: String, block: suspend () -> T): T {
    if (!lock.tryLock()) throw ErrorCatalog.error("sensor_busy", "A capture is already running. Retry in a few seconds.")
    try {
        service.showCapture(note)
        return block()
    } finally {
        service.showCapture(null)
        lock.unlock()
    }
}

/** 8.2 Camera photo. */
class CameraModule(app: BridgeApp, private val registry: Registry) : Capability(app) {
    override val id = "camera"
    override val titleRes = R.string.cap_camera
    override val descriptionRes = R.string.cap_camera_desc
    override val group = "capture"
    override val switchKey = "camera"
    override val permissions = listOf(Manifest.permission.CAMERA)
    override val endpoints = listOf(Endpoints.SNAPSHOT)
    override fun blockedReason() = captureBlocked(app, Manifest.permission.CAMERA, app.listener.value.cameraReady)

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(Endpoints.SNAPSHOT) {
            val which = body.optString("camera", "rear")
            if (which != "rear" && which != "front") throw ErrorCatalog.error("invalid_parameter", "camera must be \"rear\" or \"front\".")
            val flash = if (body.has("flash")) body.opt("flash") as? Boolean
                ?: throw ErrorCatalog.error("invalid_parameter", "flash must be true or false.") else false
            val spec = snapshotSpec(this)
            requireUsable()
            val img = api.withSensor(registry.cameraLock, app.getString(R.string.notif_taking_photo, agentName)) {
                api.service.camera.snapshot(which == "rear", flash, spec)
            }
            data = JSONObject().put("camera", which).put("width_px", img.width).put("height_px", img.height)
            val at = isoAt(System.currentTimeMillis())
            if (wantsRaw()) rawReply(img, mapOf("X-Bridget-Captured-At" to at, "X-Bridget-Camera" to which))
            else Reply.Json(envelope(img, at, "captured_at", null).put("source", "camera").put("camera", which))
        }
    }

    /** CAP-04: default 2048 px wide at q90 (1.2 behaviour); null max_width_px = full size; API-10 presets otherwise. */
    private fun snapshotSpec(r: Req): Sizing.Spec {
        val b = r.body
        if (b.has("max_width_px") && b.isNull("max_width_px")) return Sizing.PRESETS.getValue("original")
        val explicit = listOf("size", "max_edge_px", "max_width_px", "quality").any { b.has(it) || r.query(it) != null }
        if (!explicit) return Sizing.Spec(null, 2048, 90)
        return Sizing.resolve({ r.arg(it) }, "agent")
    }
}

/** 8.4 Microphone clip. */
class MicModule(app: BridgeApp, private val registry: Registry) : Capability(app) {
    override val id = "mic"
    override val titleRes = R.string.cap_mic
    override val descriptionRes = R.string.cap_mic_desc
    override val group = "capture"
    override val switchKey = "mic"
    override val permissions = listOf(Manifest.permission.RECORD_AUDIO)
    override val endpoints = listOf(Endpoints.MIC)
    override fun blockedReason() = captureBlocked(app, Manifest.permission.RECORD_AUDIO, app.listener.value.micReady)
    override fun details() = JSONObject().put("max_seconds", 120)

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(Endpoints.MIC) {
            val d = body.opt("duration_seconds")
            val secs = (d as? Int) ?: (d as? Long)?.toInt()
            if (secs == null || secs < 1 || secs > 120) throw ErrorCatalog.error("invalid_parameter", "duration_seconds must be a whole number from 1 to 120.")
            requireUsable()
            val started = System.currentTimeMillis()
            val clip = api.withSensor(registry.micLock, app.getString(R.string.notif_recording, agentName, secs)) { api.service.mic.record(secs) }
            val b64 = Base64.encodeToString(clip.bytes, Base64.NO_WRAP)
            if (b64.length > ApiServer.MAX_PAYLOAD) throw ErrorCatalog.error("payload_too_large", "The clip is over 12 MB as base64.")
            bytesOut = clip.bytes.size.toLong()
            data = JSONObject().put("duration_seconds", secs)
            Reply.Json(JSONObject().put("recorded_at", isoAt(started)).put("duration_seconds", clip.durationSeconds)
                .put("format", "m4a").put("sample_rate_hz", clip.sampleRate).put("size_bytes", clip.bytes.size).put("data_base64", b64))
        }
    }
}

/** 8.5 Gallery and 8.6 Last image. */
class GalleryModule(app: BridgeApp) : Capability(app) {
    private val gallery = Gallery(app)
    override val id = "gallery"
    override val titleRes = R.string.cap_gallery
    override val descriptionRes = R.string.cap_gallery_desc
    override val group = "photos"
    override val switchKey = "gallery"
    override val permissions = listOf(Manifest.permission.READ_MEDIA_IMAGES)
    override val endpoints = listOf(Endpoints.GALLERY_RECENT, Endpoints.GALLERY_PHOTO, Endpoints.IMAGE_LAST)
    override fun blockedReason() = if (gallery.accessMode() == null) "permission:READ_MEDIA_IMAGES" else null
    override fun details() = JSONObject().put("access_mode", gallery.accessMode() ?: "none")

    private fun mode(): String { requireUsable(); return gallery.accessMode()!! }

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(Endpoints.GALLERY_RECENT) {
            val m = mode()
            val limit = intArg(this, "limit", 20, 1, 100)
            val arr = JSONArray()
            gallery.recent(limit).forEach {
                arr.put(JSONObject().put("id", it.id.toString()).put("taken_at", isoAt(it.takenAt))
                    .put("width_px", it.width).put("height_px", it.height)
                    .put("size_bytes", it.size).put("mime", it.mime).put("album", it.album ?: JSONObject.NULL))
            }
            Reply.Json(JSONObject().put("access_mode", m).put("photos", arr))
        }
        endpoint(Endpoints.GALLERY_PHOTO) {
            val m = mode()
            val idStr = path("id") ?: ""
            val id = idStr.toLongOrNull() ?: throw ErrorCatalog.error("invalid_parameter", "id must be a gallery id from /v1/gallery/recent.")
            val spec = legacyOrPreset(this)
            val item = gallery.find(id) ?: throw ErrorCatalog.error("photo_not_found", "No photo with that id" + if (m == "selected") " in the selected set." else ".")
            val raw = gallery.read(id) ?: throw ErrorCatalog.error("photo_not_found", "That photo could not be opened.")
            val img = ImageUtil.prepare(raw, item.mime, spec)
            if (wantsRaw()) rawReply(img, mapOf("X-Bridget-Id" to idStr, "X-Bridget-Taken-At" to isoAt(item.takenAt)))
            else Reply.Json(envelope(img, isoAt(item.takenAt), "taken_at", idStr).put("source", "gallery").put("id", idStr).put("access_mode", m))
        }
        endpoint(Endpoints.IMAGE_LAST) {
            val m = mode()
            val type = query("type") ?: "any"
            val kind = when (type) {
                "any" -> Gallery.Kind.ANY; "screenshot" -> Gallery.Kind.SCREENSHOT; "photo" -> Gallery.Kind.PHOTO
                else -> throw ErrorCatalog.error("invalid_parameter", "type must be any, screenshot or photo.")
            }
            val spec = Sizing.resolve({ arg(it) }, "agent")
            val since = query("since")?.let { s ->
                s.toLongOrNull()?.let { Since(id = it) } ?: LogQuery.iso(s)?.let { Since(ts = it) }
                    ?: throw ErrorCatalog.error("invalid_parameter", "since must be a gallery id or an ISO time.")
            }
            val wait = intArg(this, "wait", 0, 0, 55)
            if (wait > 0 && since == null) throw ErrorCatalog.error("invalid_parameter", "wait needs since (an ISO time or gallery id).")
            var found = gallery.latest(kind)?.takeIf { since == null || since.isOlderThan(it.first) }
            if (found == null && wait > 0) {
                if (!api.waits.enter(agent!!.id)) throw ErrorCatalog.error("too_many_waits", "At most 4 open waits per agent.")
                try {
                    val until = System.currentTimeMillis() + wait * 1000L
                    while (found == null && System.currentTimeMillis() < until) {
                        delay(1000)
                        found = gallery.latest(kind)?.takeIf { since!!.isOlderThan(it.first) }
                    }
                } finally { api.waits.exit(agent!!.id) }
                if (found == null) return@endpoint Reply.Json(JSONObject().put("new", false).put("type", type)
                    .put("message", "No new $type image in $wait s. Ask again with the same since."))
            }
            val (item, path) = found ?: throw ErrorCatalog.error("photo_not_found",
                "No ${if (type == "any") "" else "$type "}images" + (if (since != null) " newer than since" else "") + (if (m == "selected") " in the selected set." else "."))
            val bytesIn = gallery.read(item.id) ?: throw ErrorCatalog.error("photo_not_found", "That image could not be opened.")
            val img = ImageUtil.prepare(bytesIn, item.mime, spec, forceJpeg = !spec.original)
            val kindName = gallery.kindOf(path)
            data = JSONObject().put("type", type).put("id", item.id.toString())
            if (wantsRaw()) rawReply(img, mapOf(
                "X-Bridget-Id" to item.id.toString(), "X-Bridget-Type" to kindName,
                "X-Bridget-Taken-At" to isoAt(item.takenAt), "X-Bridget-Added-At" to isoAt(item.addedAt),
                "X-Bridget-Album" to (item.album ?: "")))
            else Reply.Json(envelope(img, isoAt(item.takenAt), "taken_at", item.id.toString())
                .put("source", "gallery").put("id", item.id.toString()).put("type", kindName)
                .put("added_at", isoAt(item.addedAt)).put("album", item.album ?: JSONObject.NULL)
                .put("original_width_px", item.width).put("original_height_px", item.height).put("access_mode", m))
        }
    }

    private class Since(val id: Long? = null, val ts: Long? = null) {
        fun isOlderThan(item: Gallery.Item) = if (id != null) item.id > id else item.addedAt > ts!!
    }

    /** Gallery photos keep 1.2's "unchanged unless asked" default; presets apply when given. */
    private fun legacyOrPreset(r: Req): Sizing.Spec {
        val explicit = listOf("size", "max_edge_px", "max_width_px", "quality").any { r.query(it) != null }
        return if (explicit) Sizing.resolve({ r.arg(it) }, "agent") else Sizing.PRESETS.getValue("original")
    }
}

private fun intArg(r: Req, name: String, def: Int, min: Int, max: Int): Int {
    val s = r.query(name) ?: return def
    val n = s.toIntOrNull()
    if (n == null || n < min || n > max) throw ErrorCatalog.error("invalid_parameter", "$name must be $min to $max.")
    return n
}

// ---------------------------------------------------------------------------------------------

/** Section 10: the log API. Always on; what an agent may read follows the visibility setting. */
class LogModule(app: BridgeApp) : Capability(app) {
    override val id = "log"
    override val titleRes = R.string.cap_log
    override val descriptionRes = R.string.cap_log_desc
    override val group = "status"
    override val endpoints = listOf(Endpoints.LOG, Endpoints.LOG_ENTRY, Endpoints.LOG_STATS, Endpoints.LOG_EXPORT, Endpoints.LOG_NOTE, Endpoints.AUDIT)

    private val noteLimiter = net.finleg.bridget.api.RateLimiter()

    /** Agent ids this caller may read, or null for everything (developer agents always see all). */
    private fun allowed(r: Req): List<String>? =
        if (r.agent?.developer == true || app.settings.logVisibility == "all") null else listOf(r.agent!!.id)

    private fun parse(r: Req, extra: Map<String, String> = emptyMap()): LogQuery {
        val p = r.call.request.queryParameters.entries().associate { it.key to (it.value.firstOrNull() ?: "") }.toMutableMap()
        p.putAll(extra)
        p["agent"]?.let { a -> p["agent"] = a.split(",").joinToString(",") { if (it.trim() == "me") r.agent!!.id else it.trim() } }
        val q = try {
            LogQuery.parse(p - "wait" - "format", { n -> app.agents.get(n)?.id ?: app.agents.byName(n)?.id })
        } catch (e: QueryError) { throw ErrorCatalog.error("invalid_parameter", e.message ?: "Bad log query.") }
        val mine = allowed(r) ?: return q
        return q.copy(agentIds = q.agentIds?.filter { it in mine } ?: mine)
    }

    private fun json(e: LogEntry): JSONObject =
        e.toJson(::isoAt, e.agentId?.let { app.agents.get(it)?.name } ?: e.agent)

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(Endpoints.LOG) {
            val q = parse(this)
            val wait = intArg(this, "wait", 0, 0, 55)
            val entries = if (wait > 0) {
                if (!q.ascending || q.sinceId == null) throw ErrorCatalog.error("invalid_parameter", "Tail mode needs order=asc and since=<log id>.")
                if (!api.waits.enter(agent!!.id)) throw ErrorCatalog.error("too_many_waits", "At most 4 open waits per agent.")
                try { app.log.waitFor(q, q.sinceId, wait * 1000L) } finally { api.waits.exit(agent!!.id) }
            } else app.log.query(q)
            params.put("returned", entries.size)
            Reply.Json(JSONObject().put("entries", JSONArray(entries.map { json(it) }))
                .put("next_cursor", if (entries.size == q.limit) LogQuery.encodeCursor(entries.last().id) else JSONObject.NULL)
                .put("latest_id", app.log.latestId.value))
        }
        endpoint(Endpoints.LOG_STATS) {
            val q = parse(this, buildMap { if (query("since") == null) put("since", isoAt(System.currentTimeMillis() - 86_400_000L)) })
                .copy(channels = listOf("audit"), kinds = listOf("request"), limit = 20_000)
            val rows = app.log.query(q.copy(limit = LogQuery.MAX_LIMIT)).let { first ->
                if (first.size < LogQuery.MAX_LIMIT) first else {
                    val all = ArrayList<LogEntry>(); app.log.forEach(q) { all += it }; all
                }
            }
            fun group(key: (LogEntry) -> String) = JSONArray().apply {
                rows.groupBy(key).forEach { (k, list) ->
                    val d = list.mapNotNull { it.durationMs }.sorted()
                    put(JSONObject().put("key", k).put("count", list.size).put("errors", list.count { it.isError })
                        .put("p50_ms", Percentile.of(d, 50.0) ?: JSONObject.NULL).put("p95_ms", Percentile.of(d, 95.0) ?: JSONObject.NULL)
                        .put("bytes_out", list.sumOf { it.bytesOut ?: 0 }))
                }
            }
            Reply.Json(JSONObject().put("by_agent", group { e -> e.agentId?.let { app.agents.get(it)?.name } ?: e.agent ?: "(no key)" })
                .put("by_endpoint", group { "${it.method} ${it.endpoint}" }).put("entries", rows.size))
        }
        endpoint(Endpoints.LOG_EXPORT) {
            val format = query("format") ?: "jsonl"
            if (format != "jsonl" && format != "csv") throw ErrorCatalog.error("invalid_parameter", "format must be jsonl or csv.")
            val q = parse(this)
            val sb = StringBuilder()
            if (format == "csv") sb.append(CSV_HEADER).append('\n')
            var n = 0
            app.log.forEach(q.copy(limit = LogQuery.MAX_LIMIT)) { e ->
                n++
                if (format == "jsonl") sb.append(json(e).toString()).append('\n') else sb.append(csv(json(e))).append('\n')
                if (sb.length > 50 * 1024 * 1024) throw ErrorCatalog.error("payload_too_large", "Export over 50 MB. Narrow the filters.")
            }
            params.put("returned", n)
            Reply.Bytes(sb.toString().toByteArray(), if (format == "csv") "text/csv; charset=utf-8" else "application/x-ndjson")
        }
        endpoint(Endpoints.LOG_NOTE) {
            val a = agent!!
            val msg = body.optString("message", "")
            if (msg.isBlank()) throw ErrorCatalog.error("invalid_parameter", "message is required.")
            if (msg.toByteArray().size > 1024) throw ErrorCatalog.error("invalid_parameter", "message is limited to 1 KB.")
            val level = body.optString("level", "info")
            if (level !in LogEntry.LEVELS) throw ErrorCatalog.error("invalid_parameter", "level must be debug, info, warn or error.")
            val d = body.optJSONObject("data") ?: JSONObject()
            if (d.toString().toByteArray().size > 4096) throw ErrorCatalog.error("invalid_parameter", "data is limited to 4 KB.")
            if (!noteLimiter.tryAcquire(a.id, net.finleg.bridget.api.RateLimiter.NOTES_PER_AGENT))
                throw ErrorCatalog.error("rate_limited", "At most 60 notes per minute.", mapOf("retry_after_s" to noteLimiter.retryAfterS(a.id)))
            app.log.append(LogEntry(ts = System.currentTimeMillis(), channel = "agent", kind = "note", level = level,
                agentId = a.id, agent = a.name, sourceIp = source, requestId = requestId,
                message = Redactor.string(msg, 1024), data = Redactor.data(d)))
            params.put("message", "[${msg.length} chars]")
            message = "${a.name} wrote a note"
            Reply.Json(JSONObject().put("ok", true).put("channel", "agent"), status = 201)
        }
        endpoint(Endpoints.AUDIT) {
            val lim = intArg(this, "limit", 50, 1, LogQuery.MAX_LIMIT)
            val q = parse(this, mapOf("channel" to "audit", "limit" to lim.toString()))
            Reply.Json(JSONObject().put("entries", JSONArray(app.log.query(q).map { json(it) }))
                .put("deprecated", "Use GET /v1/log?channel=audit. /v1/audit is removed in v3."))
        }
        endpoint(Endpoints.LOG_ENTRY) {
            val id = path("id")?.toLongOrNull() ?: throw ErrorCatalog.error("invalid_parameter", "id must be a log entry id.")
            val e = app.log.get(id)
            val mine = allowed(this)
            if (e == null || (mine != null && e.agentId !in mine)) throw ErrorCatalog.error("not_found", "No log entry $id that you can read.")
            Reply.Json(json(e))
        }
    }

    companion object {
        val CSV_COLS = listOf("id", "ts", "channel", "level", "kind", "agent", "agent_id", "source_ip", "method", "endpoint",
            "http", "result", "bytes_in", "bytes_out", "duration_ms", "request_id", "message")
        val CSV_HEADER = CSV_COLS.joinToString(",")
        fun csv(o: JSONObject) = CSV_COLS.joinToString(",") { c ->
            val v = o.opt(c)
            val s = if (v == null || v == JSONObject.NULL) "" else v.toString()
            if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        }
    }
}

