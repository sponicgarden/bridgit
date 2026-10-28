package net.finleg.bridget

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Base64
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.util.UUID

/**
 * The HTTP API from section 4 of the spec. Bound only to the Tailscale address;
 * every request is checked, rate-limited and audit-logged.
 */
class ApiServer(private val service: BridgeService, private val host: String, private val port: Int) {
    private val app = service.application as BridgeApp
    private val policy = app.policy
    private val audit = app.audit
    private val limiter = RateLimiter()
    private val sensorLock = Mutex()
    private val camera = CameraCapture(service, service)
    private val mic = MicCapture(service)
    private val gallery = Gallery(service)
    private var engine: ApplicationEngine? = null

    fun start() {
        engine = embeddedServer(CIO, host = host, port = port) {
            routing {
                route("/v1") {
                    post("/pair") { pair(call) }
                    get("/health") { handle(call, "health") { health() } }
                    get("/capabilities") { handle(call, "capabilities") { capabilities() } }
                    post("/camera/snapshot") { handle(call, "camera/snapshot", capture = true) { b -> snapshot(b) } }
                    post("/mic/record") { handle(call, "mic/record", capture = true) { b -> record(b) } }
                    get("/gallery/recent") { handle(call, "gallery/recent") { _ -> recent(call) } }
                    get("/gallery/photo/{id}") { handle(call, "gallery/photo") { _ -> photo(call) } }
                    get("/audit") { handle(call, "audit") { _ -> auditRead(call) } }
                    get("/image/last") { lastImage(call) }
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        engine?.stop(500, 2000)
        engine = null
    }

    // ---- request pipeline -------------------------------------------------

    private class Ctx(val requestId: String, val params: JSONObject = JSONObject(), var bytes: Long = 0) {
        var agent: String = "agent"
    }

    private suspend fun handle(
        call: ApplicationCall, endpoint: String, capture: Boolean = false,
        respond: (() -> Pair<Encoded, Map<String, String>>?)? = null,
        block: suspend Ctx.(JSONObject) -> JSONObject
    ) {
        val ctx = Ctx(newRequestId())
        val source = sourceOf(call)
        val entry = JSONObject().put("request_id", ctx.requestId).put("source", source)
            .put("method", call.request.httpMethod.value).put("endpoint", "/v1/$endpoint")
        call.request.queryParameters.entries().forEach { (k, v) -> ctx.params.put(k, v.firstOrNull()) }
        call.parameters["id"]?.let { ctx.params.put("id", it) }
        try {
            if (!isTailnet(source)) throw ApiError(403, "forbidden_source", "Only Tailscale peers may call this API")
            if (limiter.authBlocked(source)) throw ApiError(429, "rate_limited", "Too many failed sign-ins; wait a minute")
            val auth = call.request.headers["Authorization"]
            val token = auth?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
            val agent = policy.authenticate(token)
            if (agent == null) {
                limiter.recordAuthFailure(source)
                throw ApiError(401, "unauthorized", "Missing or invalid token")
            }
            ctx.agent = agent.name
            entry.put("agent", agent.name)
            policy.touch(agent.id)
            if (capture && !limiter.allowCapture(source))
                throw ApiError(429, "rate_limited", "At most 10 capture requests per minute")
            val body = if (call.request.httpMethod.value == "POST") parseBody(call) else JSONObject()
            body.keys().forEach { k -> ctx.params.put(k, body.get(k)) }
            val result = ctx.block(body)
            entry.put("http", 200).put("result", "ok").put("bytes", ctx.bytes)
            val rawOut = respond?.invoke()
            if (rawOut != null) {
                rawOut.second.forEach { (k, v) -> call.response.headers.append(k, v) }
                call.response.headers.append("X-Request-Id", ctx.requestId)
                call.respondBytes(rawOut.first.bytes, ContentType.Image.JPEG, HttpStatusCode.OK)
            } else {
                result.put("request_id", ctx.requestId)
                call.respondText(result.toString(), ContentType.Application.Json, HttpStatusCode.OK)
            }
        } catch (e: ApiError) {
            entry.put("http", e.http).put("result", e.code).put("bytes", 0)
            respondError(call, ctx.requestId, e)
        } catch (e: Exception) {
            val err = ApiError(500, "internal_error", e.message ?: e.javaClass.simpleName)
            entry.put("http", 500).put("result", err.code).put("detail", err.message)
            respondError(call, ctx.requestId, err)
        } finally {
            entry.put("params", ctx.params)
            audit.append(entry)
        }
    }

    private suspend fun respondError(call: ApplicationCall, requestId: String, e: ApiError) {
        val o = JSONObject().put("error", e.code).put("message", e.message).put("request_id", requestId)
        e.extra.forEach { (k, v) -> o.put(k, v) }
        call.respondText(o.toString(), ContentType.Application.Json, HttpStatusCode.fromValue(e.http))
    }

    private suspend fun pair(call: ApplicationCall) {
        val ctx = Ctx(newRequestId())
        val source = sourceOf(call)
        val entry = JSONObject().put("request_id", ctx.requestId).put("source", source)
            .put("method", "POST").put("endpoint", "/v1/pair")
        try {
            if (!isTailnet(source)) throw ApiError(403, "forbidden_source", "Only Tailscale peers may call this API")
            if (limiter.authBlocked(source)) throw ApiError(429, "rate_limited", "Too many failed attempts; wait a minute")
            if (!app.pairing.isOpen()) throw ApiError(403, "pairing_closed", "No pairing window is open on the phone")
            val code = parseBody(call).optString("code", "")
            val w = app.pairing.redeem(code)
            if (w == null) {
                limiter.recordAuthFailure(source)
                throw ApiError(401, "unauthorized", "Wrong pairing code")
            }
            entry.put("http", 200).put("result", "ok").put("agent", w.agentName)
            service.onPaired(w.agentName)
            call.respondText(JSONObject().put("token", w.token).put("agent", w.agentName)
                .put("base_url", "http://$host:$port/v1").put("request_id", ctx.requestId).toString(),
                ContentType.Application.Json)
        } catch (e: ApiError) {
            entry.put("http", e.http).put("result", e.code)
            respondError(call, ctx.requestId, e)
        } finally {
            audit.append(entry)
        }
    }

    // ---- endpoints ----------------------------------------------------------

    private fun Ctx.health(): JSONObject {
        val bat = service.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0) l * 100 / s else null
        }
        val plugged = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return JSONObject().put("status", "ok").put("app", "Bridgit").put("agent", agent)
            .put("app_version", BuildConfigInfo.versionName(service))
            .put("listener_uptime_seconds", (System.currentTimeMillis() - service.startedAt) / 1000)
            .put("battery_level", level ?: JSONObject.NULL)
            .put("charging", plugged)
            .put("bind_address", host).put("port", port)
    }

    private fun capabilities(): JSONObject {
        fun cap(c: Policy.Capability, perm: String, ready: Boolean) = JSONObject()
            .put("enabled", policy.isEnabled(c))
            .put("permission", if (service.granted(perm)) "granted" else "denied")
            .put("permission_name", perm)
            .put("service_ready", ready)
        val mode = gallery.accessMode()
        return JSONObject()
            .put("camera", cap(Policy.Capability.CAMERA, Manifest.permission.CAMERA, service.cameraReady))
            .put("mic", cap(Policy.Capability.MIC, Manifest.permission.RECORD_AUDIO, service.micReady))
            .put("gallery", JSONObject()
                .put("enabled", policy.isEnabled(Policy.Capability.GALLERY))
                .put("permission", if (mode != null) "granted" else "denied")
                .put("permission_name", Manifest.permission.READ_MEDIA_IMAGES)
                .put("access_mode", mode ?: "none"))
            .put("notifications", if (service.granted(Manifest.permission.POST_NOTIFICATIONS)) "granted" else "denied")
    }

    private suspend fun Ctx.snapshot(b: JSONObject): JSONObject {
        val which = b.optString("camera", "rear")
        if (which != "rear" && which != "front") throw ApiError(422, "invalid_parameter", "camera must be \"rear\" or \"front\"")
        val flash = if (b.has("flash")) b.opt("flash") as? Boolean
            ?: throw ApiError(422, "invalid_parameter", "flash must be true or false") else false
        val maxW = maxWidth(b.has("max_width_px"), b.opt("max_width_px"), 2048)
        require(Policy.Capability.CAMERA, Manifest.permission.CAMERA, service.cameraReady)
        val img = withSensor("$agent is taking a photo") { camera.snapshot(which == "rear", flash, maxW) }
        return mediaEnvelope(img, isoAt(System.currentTimeMillis()), "captured_at", null)
            .put("source", "camera").put("camera", which)
    }

    private suspend fun Ctx.record(b: JSONObject): JSONObject {
        val d = b.opt("duration_seconds")
        val secs = (d as? Int) ?: (d as? Long)?.toInt()
        if (secs == null || secs < 1 || secs > 120)
            throw ApiError(422, "invalid_parameter", "duration_seconds must be a whole number from 1 to 120")
        require(Policy.Capability.MIC, Manifest.permission.RECORD_AUDIO, service.micReady)
        val started = System.currentTimeMillis()
        val clip = withSensor("$agent is recording audio (${secs}s)") { mic.record(secs) }
        val b64 = Base64.encodeToString(clip.bytes, Base64.NO_WRAP)
        if (b64.length > MAX_PAYLOAD) throw ApiError(413, "payload_too_large", "Clip exceeds transfer cap")
        bytes = clip.bytes.size.toLong()
        return JSONObject().put("recorded_at", isoAt(started)).put("duration_seconds", clip.durationSeconds)
            .put("format", "m4a").put("sample_rate_hz", clip.sampleRate)
            .put("size_bytes", clip.bytes.size).put("data_base64", b64)
    }

    private fun Ctx.recent(call: ApplicationCall): JSONObject {
        val mode = requireGallery()
        val limit = intParam(call, "limit", 20, 1, 100)
        val arr = JSONArray()
        gallery.recent(limit).forEach {
            arr.put(JSONObject().put("id", it.id.toString()).put("taken_at", isoAt(it.takenAt))
                .put("width_px", it.width).put("height_px", it.height)
                .put("size_bytes", it.size).put("mime", it.mime).put("album", it.album ?: JSONObject.NULL))
        }
        return JSONObject().put("access_mode", mode).put("photos", arr)
    }

    private fun Ctx.photo(call: ApplicationCall): JSONObject {
        val mode = requireGallery()
        val idStr = call.parameters["id"] ?: ""
        val id = idStr.toLongOrNull() ?: throw ApiError(422, "invalid_parameter", "id must be a numeric MediaStore ID")
        val q = call.request.queryParameters["max_width_px"]
        val maxW = q?.let { s -> s.toIntOrNull()?.takeIf { it in 64..20000 }
            ?: throw ApiError(422, "invalid_parameter", "max_width_px must be 64 to 20000") }
        val item = gallery.find(id) ?: throw ApiError(404, "photo_not_found",
            "No photo with that ID" + if (mode == "selected") " in the selected set" else "")
        val raw = gallery.read(id) ?: throw ApiError(404, "photo_not_found", "Photo could not be opened")
        val img = ImageUtil.prepare(raw, item.mime, maxW)
        return mediaEnvelope(img, isoAt(item.takenAt), "taken_at", idStr)
            .put("source", "gallery").put("id", idStr).put("access_mode", mode)
    }

    /**
     * Fastest path to one image: the newest of a type, resized for an AI agent to read
     * (long edge 1568 px, JPEG q85). raw=1 returns the JPEG bytes with metadata in headers.
     */
    private suspend fun lastImage(call: ApplicationCall) {
        val raw = call.request.queryParameters["raw"].let { it == "1" || it == "true" }
        var rawResult: Pair<Encoded, Map<String, String>>? = null
        handle(call, "image/last", respond = { if (raw) rawResult else null }) { _ ->
            val mode = requireGallery()
            val type = call.request.queryParameters["type"] ?: "any"
            val kind = when (type) {
                "any" -> Gallery.Kind.ANY; "screenshot" -> Gallery.Kind.SCREENSHOT; "photo" -> Gallery.Kind.PHOTO
                else -> throw ApiError(422, "invalid_parameter", "type must be any, screenshot or photo")
            }
            val edge = intParam(call, "max_edge_px", AGENT_EDGE, 256, 8192)
            val quality = intParam(call, "quality", 85, 40, 100)
            val (item, path) = gallery.latest(kind) ?: throw ApiError(404, "photo_not_found",
                "No ${if (type == "any") "" else "$type "}images found" + if (mode == "selected") " in the selected set" else "")
            val bytesIn = gallery.read(item.id) ?: throw ApiError(404, "photo_not_found", "Image could not be opened")
            val img = ImageUtil.prepare(bytesIn, item.mime, null, edge, forceJpeg = true, quality = quality)
            val kindName = gallery.kindOf(path)
            if (raw) {
                bytes = img.bytes.size.toLong()
                rawResult = img to mapOf(
                    "X-Bridget-Id" to item.id.toString(), "X-Bridget-Type" to kindName,
                    "X-Bridget-Taken-At" to isoAt(item.takenAt), "X-Bridget-Added-At" to isoAt(item.addedAt),
                    "X-Bridget-Width" to img.width.toString(), "X-Bridget-Height" to img.height.toString(),
                    "X-Bridget-Album" to (item.album ?: ""))
                JSONObject()
            } else {
                mediaEnvelope(img, isoAt(item.takenAt), "taken_at", item.id.toString())
                    .put("source", "gallery").put("id", item.id.toString()).put("type", kindName)
                    .put("added_at", isoAt(item.addedAt)).put("album", item.album ?: JSONObject.NULL)
                    .put("original_width_px", item.width).put("original_height_px", item.height)
                    .put("access_mode", mode)
            }
        }
    }

    private fun Ctx.auditRead(call: ApplicationCall): JSONObject {
        val limit = intParam(call, "limit", 50, 1, AuditLog.MAX_ENTRIES)
        return JSONObject().put("entries", JSONArray(audit.recent(limit)))
    }

    // ---- helpers ------------------------------------------------------------

    private fun Ctx.mediaEnvelope(img: Encoded, at: String, atKey: String, photoId: String?): JSONObject {
        val b64 = Base64.encodeToString(img.bytes, Base64.NO_WRAP)
        if (b64.length > MAX_PAYLOAD) throw ApiError(413, "payload_too_large",
            "Result exceeds the 12 MB transfer cap; re-request with a smaller max_width_px",
            if (photoId != null) mapOf("photo_id" to photoId) else emptyMap())
        bytes = img.bytes.size.toLong()
        val fmt = when (img.mime) { "image/jpeg" -> "jpeg"; "image/png" -> "png"; "image/heic", "image/heif" -> "heic"
            else -> img.mime.substringAfter('/') }
        return JSONObject().put(atKey, at).put("width_px", img.width).put("height_px", img.height)
            .put("format", fmt).put("mime", img.mime).put("size_bytes", img.bytes.size).put("data_base64", b64)
    }

    private fun require(c: Policy.Capability, perm: String, ready: Boolean) {
        if (!policy.isEnabled(c)) throw ApiError(403, "capability_disabled", "${c.key} is switched off on the phone")
        if (!service.granted(perm)) throw ApiError(403, "permission_denied", "Grant $perm on the phone",
            mapOf("permission" to perm))
        if (!ready) throw ApiError(503, "service_start_blocked",
            "Android has not allowed ${c.key} access for the listener yet. Open BRIDGIT Device Manager once on the phone.")
    }

    private fun requireGallery(): String {
        if (!policy.isEnabled(Policy.Capability.GALLERY))
            throw ApiError(403, "capability_disabled", "gallery is switched off on the phone")
        return gallery.accessMode() ?: throw ApiError(403, "permission_denied",
            "Grant ${Manifest.permission.READ_MEDIA_IMAGES} on the phone",
            mapOf("permission" to Manifest.permission.READ_MEDIA_IMAGES))
    }

    private suspend fun <T> withSensor(note: String, block: suspend () -> T): T {
        if (!sensorLock.tryLock()) throw ApiError(409, "sensor_busy", "A capture is already running")
        try {
            service.showCapture(note)
            return block()
        } finally {
            service.showCapture(null)
            sensorLock.unlock()
        }
    }

    private fun maxWidth(present: Boolean, v: Any?, default: Int): Int? {
        if (!present) return default
        if (v == null || v == JSONObject.NULL) return null // explicit null = full resolution
        val n = (v as? Int) ?: (v as? Long)?.toInt()
        if (n == null || n < 64 || n > 20000) throw ApiError(422, "invalid_parameter", "max_width_px must be 64 to 20000, or null for full size")
        return n
    }

    private fun intParam(call: ApplicationCall, name: String, def: Int, min: Int, max: Int): Int {
        val s = call.request.queryParameters[name] ?: return def
        val n = s.toIntOrNull()
        if (n == null || n < min || n > max) throw ApiError(422, "invalid_parameter", "$name must be $min to $max")
        return n
    }

    private suspend fun parseBody(call: ApplicationCall): JSONObject {
        val t = call.receiveText().trim()
        if (t.isEmpty()) return JSONObject()
        return runCatching { JSONObject(t) }.getOrElse { throw ApiError(422, "invalid_parameter", "Body must be a JSON object") }
    }

    private fun sourceOf(call: ApplicationCall): String = call.request.local.remoteAddress

    private fun newRequestId() = UUID.randomUUID().toString().replace("-", "").take(12)

    companion object {
        const val MAX_PAYLOAD = 12 * 1024 * 1024
        /** Long edge for agent-readable images: what Claude-class vision models read at full detail. */
        const val AGENT_EDGE = 1568

        /** True for addresses in Tailscale's CGNAT range 100.64.0.0/10. */
        fun isTailnet(ip: String): Boolean {
            if (!ip.matches(Regex("^[0-9.]+$"))) return false
            val a = runCatching { InetAddress.getByName(ip) }.getOrNull() as? Inet4Address ?: return false
            val b = a.address
            return (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
        }
    }
}

/** Sliding one-minute windows per source address. */
class RateLimiter {
    private val authFails = HashMap<String, ArrayDeque<Long>>()
    private val captures = HashMap<String, ArrayDeque<Long>>()

    @Synchronized fun recordAuthFailure(src: String) { prune(authFails, src).addLast(now()) }
    @Synchronized fun authBlocked(src: String) = prune(authFails, src).size >= 5
    @Synchronized fun allowCapture(src: String): Boolean {
        val q = prune(captures, src)
        if (q.size >= 10) return false
        q.addLast(now()); return true
    }

    private fun prune(m: HashMap<String, ArrayDeque<Long>>, src: String): ArrayDeque<Long> {
        val q = m.getOrPut(src) { ArrayDeque() }
        val cutoff = now() - 60_000
        while (q.isNotEmpty() && q.first() < cutoff) q.removeFirst()
        return q
    }
    private fun now() = System.currentTimeMillis()
}

object BuildConfigInfo {
    fun versionName(ctx: android.content.Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
}
