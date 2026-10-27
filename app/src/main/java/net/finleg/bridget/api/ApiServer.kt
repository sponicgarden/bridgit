package net.finleg.bridget.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import net.finleg.bridget.BridgeApp
import net.finleg.bridget.BridgeService
import net.finleg.bridget.core.Agent
import net.finleg.bridget.core.AuthResult
import net.finleg.bridget.core.Net
import net.finleg.bridget.log.LogEntry
import net.finleg.bridget.log.Redactor
import org.json.JSONObject
import java.util.UUID

/** What an endpoint hands back to the pipeline. */
sealed class Reply {
    class Json(val body: JSONObject, val status: Int = 200) : Reply()
    class Bytes(val bytes: ByteArray, val contentType: String, val headers: Map<String, String> = emptyMap()) : Reply()
    class Text(val text: String, val contentType: String) : Reply()
}

/** One authenticated request, as seen by an endpoint. */
class Req(val call: ApplicationCall, val spec: EndpointSpec, val agent: Agent?, val requestId: String,
          val source: String, val body: JSONObject, private var rawFile: java.io.File? = null) {
    /**
     * A non-JSON body on a large-body endpoint, streamed to a temp file (API-11). The caller takes ownership:
     * it must move or delete the file. Files nobody took are deleted when the request ends.
     */
    fun takeRawFile(): java.io.File? = rawFile.also { rawFile = null }
    fun hasRawFile(): Boolean = rawFile != null
    internal fun leftoverRawFile(): java.io.File? = rawFile
    /** What goes into the log entry's params (redacted before storing). */
    val params = JSONObject()
    var bytesOut = 0L
    var message: String? = null
    var data: JSONObject? = null
    fun query(name: String): String? = call.request.queryParameters[name]
    fun path(name: String): String? = call.parameters[name]
    /** Query value, or body value for POSTs. */
    fun arg(name: String): Any? = call.request.queryParameters[name] ?: body.opt(name)
    val agentName get() = agent?.name ?: "agent"
}

/**
 * The HTTP API (section 7). Bound only to the phone's Tailscale addresses
 * (ARC-04); every request is checked, rate-limited and logged once.
 * Ktor's own call logging is not installed (SEC-14a).
 */
class ApiServer(val service: BridgeService, private val hosts: List<String>, val port: Int) {
    val app = service.application as BridgeApp
    private val limiter = RateLimiter()
    val waits = WaitGate()
    private val idempotency = IdempotencyCache()
    private var engine: ApplicationEngine? = null
    /** Large uploads read at once (finding: concurrent 25 MB bodies). */
    private val uploadSlots = kotlinx.coroutines.sync.Semaphore(MAX_CONCURRENT_UPLOADS)
    private val uploads = java.io.File(app.cacheDir, "uploads").apply { mkdirs(); listFiles()?.forEach { it.delete() } }

    fun start() {
        val env = applicationEngineEnvironment {
            hosts.forEach { h -> connector { host = h; port = this@ApiServer.port } }
            module {
                routing {
                    route("/v1") { app.registry.modules.forEach { m -> with(m) { install(this@ApiServer) } } }
                    route("{...}") { handle { notFound(call) } }
                }
            }
        }
        engine = embeddedServer(CIO, env) { connectionIdleTimeoutSeconds = 90 }.start(wait = false)
    }

    fun stop() {
        engine?.stop(500, 2000)
        engine = null
    }

    /**
     * Registers [spec] on this route and runs [block] through the full pipeline. [admit] runs before the body is
     * read (after source, key and developer checks): endpoints with large bodies refuse switched-off or
     * rate-limited calls there, before any upload is accepted. It is skipped for idempotent replays.
     */
    fun Route.endpoint(spec: EndpointSpec, admit: (suspend (ApplicationCall, Agent?) -> Unit)? = null, block: suspend Req.() -> Reply) {
        val method = io.ktor.http.HttpMethod.parse(spec.method)
        route(spec.path, method) { handle { pipeline(call, spec, admit, block) } }
    }

    private suspend fun notFound(call: ApplicationCall) {
        val rid = newRequestId()
        val source = sourceOf(call)
        call.response.header("X-Request-Id", rid)
        val err = if (!Net.isTailnet(source)) ErrorCatalog.error("forbidden_source", "Only devices on Rahul's Tailscale network may call Bridget.")
        else ErrorCatalog.error("not_found", "No such endpoint. GET /v1/guide lists them all.")
        call.respondText(err.toJson(rid).toString(), ContentType.Application.Json, HttpStatusCode.fromValue(err.http))
        app.log.append(LogEntry(ts = System.currentTimeMillis(), channel = "audit", kind = "request", level = "warn",
            sourceIp = source, method = call.request.httpMethod.value, endpoint = Redactor.string(call.request.path(), 120),
            http = err.http, result = err.code, requestId = rid, message = "Unknown endpoint"))
    }

    private suspend fun pipeline(call: ApplicationCall, spec: EndpointSpec, admit: (suspend (ApplicationCall, Agent?) -> Unit)?,
                                 block: suspend Req.() -> Reply) {
        val started = System.currentTimeMillis()
        val rid = newRequestId()
        val source = sourceOf(call)
        call.response.header("X-Request-Id", rid)
        call.response.header("X-Bridget-API-Revision", ApiChanges.CURRENT.toString())
        val logParams = JSONObject()
        call.request.queryParameters.entries().forEach { (k, v) -> logParams.put(k, v.firstOrNull()) }
        call.parameters.names().filter { "{$it}" in spec.path }.forEach { logParams.put(it, call.parameters[it]) }
        var agent: Agent? = null
        var http = 200
        var result = "ok"
        var bytesIn = 0L
        var req: Req? = null
        var revokedReason: String? = null
        var idemKey: String? = null
        var rawFile: java.io.File? = null
        try {
            if (!Net.isTailnet(source)) throw ErrorCatalog.error("forbidden_source", "Only devices on Rahul's Tailscale network may call Bridget.")
            if (spec.auth) {
                val token = call.request.headers["Authorization"]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
                when (val r = app.agents.authenticate(token)) {
                    is AuthResult.Ok -> agent = r.agent
                    else -> {
                        // SEC-06: only requests without a valid key are blocked by the per-IP failure count.
                        // tryAcquire checks and records in one step, so concurrent bad keys can't slip past.
                        if (!limiter.tryAcquire("auth:$source", RateLimiter.AUTH_FAILS_PER_IP)) throw authBlockedError(source)
                        if (r is AuthResult.Revoked) {
                            revokedReason = r.reason
                            throw ErrorCatalog.error("unauthorized",
                                if (r.reason == "key_rotated") "${r.agentName}'s key was rotated. Ask Rahul for the new setup prompt."
                                else "${r.agentName} was removed from Bridget. Ask Rahul to add you again.",
                                mapOf("reason" to r.reason))
                        }
                        throw ErrorCatalog.error("unauthorized", "Missing or wrong key. Send Authorization: Bearer <your key>.")
                    }
                }
                app.agents.recordUse(agent!!.id, source)
                if (agent!!.knownApiRevision < ApiChanges.CURRENT)
                    call.response.header("X-Bridget-Whats-New", "/v1/changes?since=${agent!!.knownApiRevision}")
                if (spec.developerOnly && !agent!!.developer) throw ErrorCatalog.error("forbidden",
                    "Only developer agents can read bug reports. Ask Rahul to turn on Developer for ${agent!!.name}.", mapOf("reason" to "developer_only"))
            }
            // Pairing has no key, but wrong codes count as failed sign-ins (SEC-06).
            if (spec.path == "/pair" && limiter.count("auth:$source") >= RateLimiter.AUTH_FAILS_PER_IP) throw authBlockedError(source)
            // Before any body is read: the endpoint's own admission (switch, rate, queue). A request that will be
            // answered from the idempotency cache skips it, as it never runs the endpoint.
            if (admit != null) {
                val key = call.request.headers["Idempotency-Key"]?.trim()
                val replay = spec.idempotent && agent != null && key != null && IdempotencyCache.KEY_RE.matches(key) && idempotency.hasResult(agent!!.id, key)
                if (!replay) admit(call, agent)
            }
            val body = if (spec.method == "POST") {
                val ctype = call.request.headers["Content-Type"] ?: ""
                // Media endpoints take raw bytes (audio/*, image/*, ...), streamed to a size-limited temp file;
                // everything else is a JSON object, held to the normal 2 MB even on those endpoints.
                if (spec.maxBody > MAX_BODY && ctype.isNotEmpty() && !ctype.startsWith("application/json") && !ctype.startsWith("text/")) {
                    val f = readBodyToFile(call, spec.maxBody)
                    rawFile = f
                    bytesIn = f.length()
                    JSONObject()
                } else {
                    val bytes = readBody(call, minOf(spec.maxBody, MAX_BODY))
                    bytesIn = bytes.size.toLong()
                    parseBody(bytes)
                }
            } else JSONObject()
            body.keys().forEach { k ->
                val v = body.get(k)
                logParams.put(k, if (k in spec.privateFields) "[${(v as? org.json.JSONArray)?.length()?.let { "$it items" } ?: "${v.toString().length} chars"}]" else v)
            }
            val r = Req(call, spec, agent, rid, source, body, rawFile)
            req = r

            // API-13: one result per (agent, Idempotency-Key) for 24 h.
            idemKey = call.request.headers["Idempotency-Key"]?.trim()?.takeIf { spec.idempotent && agent != null }
            if (idemKey != null) {
                if (!IdempotencyCache.KEY_RE.matches(idemKey)) { idemKey = null
                    throw ErrorCatalog.error("invalid_parameter", "Idempotency-Key must be 8-128 letters, digits, - or _ (a UUID works).") }
                logParams.put("idempotency", "key")
                when (val c = idempotency.claim(agent!!.id, idemKey)) {
                    is IdempotencyCache.Claim.Full -> { idemKey = null; throw ErrorCatalog.error("rate_limited", "Too many Idempotency-Keys in the last 24 h. Retry later.", mapOf("retry_after_s" to 60)) }
                    is IdempotencyCache.Claim.Running -> { idemKey = null; throw ErrorCatalog.error("in_progress", "A request with this Idempotency-Key is still running. Retry with the same key shortly.") }
                    is IdempotencyCache.Claim.Expired -> { idemKey = null; throw ErrorCatalog.error("idempotency_expired", "This Idempotency-Key was already used; its media result is no longer kept.") }
                    is IdempotencyCache.Claim.Done -> {
                        idemKey = null
                        http = c.stored.status
                        logParams.put("replay", true)
                        c.stored.headers.forEach { (k, v) -> call.response.header(k, v) }
                        call.response.header("Idempotent-Replay", "true")
                        // API-03: the replay gets its own request_id (matching its header and log entry);
                        // the first request's id is kept as idempotent_replay_of.
                        var body = c.stored.body!!
                        if (c.stored.contentType.startsWith("application/json")) runCatching {
                            val o = JSONObject(String(body))
                            val first = o.optString("request_id")
                            body = o.put("request_id", rid).put("idempotent_replay_of", first).toString().toByteArray()
                            r.data = JSONObject().put("replay_of", first)
                        }
                        r.bytesOut = body.size.toLong()
                        call.respondBytes(body, ContentType.parse(c.stored.contentType), HttpStatusCode.fromValue(http))
                        r.message = "${agent!!.name}: replayed ${spec.method} ${spec.fullPath} (Idempotency-Key)"
                        return
                    }
                    IdempotencyCache.Claim.New -> {}
                }
            }

            // Capture limits are charged only for work that really runs (not for replays, SEC-07).
            if (spec.capture) {
                val a = agent!!
                if (!limiter.tryAcquire("cap-agent:${a.id}", RateLimiter.CAPTURES_PER_AGENT))
                    throw ErrorCatalog.error("rate_limited", "At most ${RateLimiter.CAPTURES_PER_AGENT} captures per minute per agent.",
                        mapOf("retry_after_s" to limiter.retryAfterS("cap-agent:${a.id}")))
                if (!limiter.tryAcquire("cap-ip:$source", RateLimiter.CAPTURES_PER_IP))
                    throw ErrorCatalog.error("rate_limited", "At most ${RateLimiter.CAPTURES_PER_IP} captures per minute from one address.",
                        mapOf("retry_after_s" to limiter.retryAfterS("cap-ip:$source")))
            }
            val reply = r.block()
            val (status, ctype, bytes, headers) = render(reply, rid)
            http = status
            headers.forEach { (k, v) -> call.response.header(k, v) }
            if (r.bytesOut == 0L) r.bytesOut = bytes.size.toLong()
            idemKey?.let { idempotency.finish(agent!!.id, it, status, ctype, bytes, headers, spec.media); idemKey = null }
            call.respondBytes(bytes, ContentType.parse(ctype), HttpStatusCode.fromValue(status))
        } catch (e: ApiError) {
            http = e.http; result = e.code
            val body = e.toJson(rid).toString().toByteArray()
            idemKey?.let { k -> agent?.let { idempotency.finish(it.id, k, e.http, "application/json", body, emptyMap(), false) } }
            call.respondBytes(body, ContentType.Application.Json, HttpStatusCode.fromValue(e.http))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                idemKey?.let { k -> agent?.let { idempotency.abandon(it.id, k) } }
                throw e
            }
            http = 500; result = "internal_error"
            idemKey?.let { k -> agent?.let { idempotency.abandon(it.id, k) } }
            app.log.system("diagnostic", "crash", "Error in ${spec.fullPath}: ${e.javaClass.simpleName} at ${e.stackTrace.firstOrNull()}", level = "error")
            val err = ErrorCatalog.error("internal_error", "Bridget hit a bug (${e.javaClass.simpleName}). It's in the diagnostic log.")
            runCatching { call.respondBytes(err.toJson(rid).toString().toByteArray(), ContentType.Application.Json, HttpStatusCode.InternalServerError) }
        } finally {
            // An upload the endpoint didn't take (error, replay, crash) is deleted now.
            (if (req != null) req?.leftoverRawFile() else rawFile)?.delete()
            val r = req
            r?.params?.keys()?.forEach { logParams.put(it, r.params.get(it)) }
            if (spec.path == "/pair") logParams.remove("code")
            val name = agent?.name
            val msg = r?.message ?: describe(name, spec, http, result)
            app.log.append(LogEntry(
                ts = started, channel = "audit", kind = if (spec.path == "/pair") "pairing" else "request",
                level = when { http >= 500 -> "error"; http >= 400 -> "warn"; else -> "info" },
                agentId = agent?.id, agent = name, sourceIp = source, method = spec.method, endpoint = spec.fullPath,
                params = Redactor.params(logParams), http = http, result = result, bytesIn = bytesIn,
                bytesOut = r?.bytesOut ?: 0, durationMs = System.currentTimeMillis() - started, requestId = rid,
                message = Redactor.string(msg, 300),
                data = (r?.data ?: JSONObject()).also { d -> revokedReason?.let { d.put("reason", it) } }.let { Redactor.params(it) }))
        }
    }

    private fun authBlockedError(source: String) = ErrorCatalog.error("rate_limited",
        "Too many failed sign-ins from this address. Wait a minute.", mapOf("retry_after_s" to limiter.retryAfterS("auth:$source")))

    /** A wrong pairing code counts as a failed sign-in (SEC-06). */
    fun recordAuthFailure(source: String) = limiter.record("auth:$source")

    /** Frees expired idempotency results and abandoned uploads; called from the service's periodic check. */
    fun maintenance() {
        idempotency.prune()
        val now = System.currentTimeMillis()
        uploads.listFiles()?.filter { now - it.lastModified() > 30 * 60_000L }?.forEach { it.delete() }
        runCatching { app.speaker.sweep() }
    }

    private data class Rendered(val status: Int, val contentType: String, val bytes: ByteArray, val headers: Map<String, String>)

    private fun render(reply: Reply, rid: String): Rendered = when (reply) {
        is Reply.Json -> Rendered(reply.status, "application/json", reply.body.put("request_id", rid).toString().toByteArray(), emptyMap())
        is Reply.Bytes -> Rendered(200, reply.contentType, reply.bytes, reply.headers)
        is Reply.Text -> Rendered(200, reply.contentType, reply.text.toByteArray(), emptyMap())
    }

    private fun describe(agent: String?, spec: EndpointSpec, http: Int, result: String): String {
        val who = agent ?: "Unknown caller"
        return if (http < 400) "$who: ${spec.method} ${spec.fullPath}" else "$who: ${spec.method} ${spec.fullPath} failed ($result)"
    }

    /** API-11: 2 MB request bodies outside file uploads. */
    private suspend fun readBody(call: ApplicationCall, max: Int): ByteArray {
        val limit = "Request bodies are limited to ${max / 1024 / 1024} MB."
        val declared = call.request.headers["Content-Length"]?.toLongOrNull()
        if (declared != null && declared > max) throw ErrorCatalog.error("payload_too_large", limit)
        val bytes = call.receiveChannel().readRemaining(max + 1L).readBytes()
        if (bytes.size > max) throw ErrorCatalog.error("payload_too_large", limit)
        return bytes
    }

    /**
     * API-11 for raw uploads: streams the body into a temp file, at most [max] bytes and [MAX_CONCURRENT_UPLOADS]
     * at a time; never holds the whole body in memory. The file is deleted on any failure.
     */
    private suspend fun readBodyToFile(call: ApplicationCall, max: Int): java.io.File {
        val limit = "Request bodies are limited to ${max / 1024 / 1024} MB."
        val declared = call.request.headers["Content-Length"]?.toLongOrNull()
        if (declared != null && declared > max) throw ErrorCatalog.error("payload_too_large", limit)
        kotlinx.coroutines.withTimeoutOrNull(10_000) { uploadSlots.acquire() }
            ?: throw ErrorCatalog.error("rate_limited", "Too many uploads at once. Retry in a few seconds.", mapOf("retry_after_s" to 5))
        val f = java.io.File(uploads, "${UUID.randomUUID()}.part")
        var ok = false
        try {
            val ch = call.receiveChannel()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            f.outputStream().use { out ->
                while (true) {
                    val n = ch.readAvailable(buf, 0, buf.size)
                    if (n < 0) break
                    total += n
                    if (total > max) throw ErrorCatalog.error("payload_too_large", limit)
                    out.write(buf, 0, n)
                }
            }
            ok = true
            return f
        } finally {
            uploadSlots.release()
            if (!ok) f.delete()
        }
    }

    private fun parseBody(bytes: ByteArray): JSONObject {
        val t = String(bytes).trim()
        if (t.isEmpty()) return JSONObject()
        return runCatching { JSONObject(t) }.getOrElse { throw ErrorCatalog.error("bad_request", "The body must be a JSON object.") }
    }

    private fun sourceOf(call: ApplicationCall): String = call.request.local.remoteAddress.removePrefix("/").substringBefore('%')

    private fun newRequestId() = UUID.randomUUID().toString().replace("-", "").take(12)

    companion object {
        const val MAX_BODY = 2 * 1024 * 1024
        const val MAX_PAYLOAD = 12 * 1024 * 1024
        const val MAX_RAW = 40 * 1024 * 1024
        const val MAX_CONCURRENT_UPLOADS = 2
    }
}
