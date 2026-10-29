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
import kotlinx.coroutines.*

/** What an endpoint hands back to the pipeline. */
sealed class Reply {
    class Json(val body: JSONObject, val status: Int = 200) : Reply()
    class Bytes(val bytes: ByteArray, val contentType: String, val headers: Map<String, String> = emptyMap()) : Reply()
    class Text(val text: String, val contentType: String, val status: Int = 200) : Reply()
}

/**
 * One authenticated request, as seen by an endpoint. It carries its own query, path params and
 * headers, so the same handler runs for an HTTP call or in-process for an MCP tool call (API-15).
 */
class Req(val spec: EndpointSpec, val agent: Agent?, val requestId: String, val source: String,
          val body: JSONObject, private var rawFile: java.io.File?, val queries: Map<String, String>,
          private val pathParams: Map<String, String>, private val headers: (String) -> String?) {
    constructor(call: ApplicationCall, spec: EndpointSpec, agent: Agent?, requestId: String, source: String,
                body: JSONObject, rawFile: java.io.File? = null) : this(spec, agent, requestId, source, body, rawFile,
        call.request.queryParameters.entries().associate { it.key to (it.value.firstOrNull() ?: "") },
        call.parameters.names().associateWith { call.parameters[it] ?: "" }, { call.request.headers[it] })

    /**
     * A non-JSON body on a large-body endpoint, streamed to a temp file (API-11). The caller takes ownership:
     * it must move or delete the file. Files nobody took are deleted when the request ends.
     */
    fun takeRawFile(): java.io.File? = rawFile.also { rawFile = null }
    fun hasRawFile(): Boolean = rawFile != null
    internal fun leftoverRawFile(): java.io.File? = rawFile
    /** What goes into the log entry's params (redacted before storing). */
    var viaMcp = false
    val params = JSONObject()
    var bytesOut = 0L
    var message: String? = null
    var data: JSONObject? = null
    fun query(name: String): String? = queries[name]
    fun path(name: String): String? = pathParams[name]
    fun header(name: String): String? = headers(name)
    /** Query value, or body value for POSTs. */
    fun arg(name: String): Any? = queries[name] ?: body.opt(name)
    val agentName get() = agent?.name ?: "agent"
}

/**
 * The HTTP API (section 7). Bound only to the phone's Tailscale addresses
 * (ARC-04); every request is checked, rate-limited and logged once.
 * Ktor's own call logging is not installed (SEC-14a).
 */
class RequestState {
    val limiter = RateLimiter()
    val waits = WaitGate()
    val idempotency = IdempotencyCache()
    val uploadSlots = kotlinx.coroutines.sync.Semaphore(2)
}

class ApiServer(val service: BridgeService, private val hosts: List<String>, val port: Int) {
    val app = service.application as BridgeApp
    private val limiter = app.requests.limiter
    val waits = app.requests.waits
    private val idempotency = app.requests.idempotency
    private var engine: ApplicationEngine? = null
    /** Large uploads read at once (finding: concurrent 25 MB bodies). */
    private val uploadSlots = app.requests.uploadSlots
    private val uploads = java.io.File(app.cacheDir, "uploads").apply { mkdirs() }

    /** Register real handlers without opening any listener or trusting loopback. */
    fun prepareRelay() {
        val route = io.ktor.server.routing.Route(null, io.ktor.server.routing.RootRouteSelector(""))
        app.registry.modules.forEach { m -> with(m) { with(route) { install(this@ApiServer) } } }
    }

    fun start() {
        val env = applicationEngineEnvironment {
            hosts.forEach { h -> connector { host = h; port = this@ApiServer.port } }
            module {
                routing {
                    net.finleg.bridget.platform.PlatformApi(app).install(this)
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
     * The handler is also recorded for in-process MCP tool calls (API-15).
     */
    fun Route.endpoint(spec: EndpointSpec, admit: (suspend (Map<String, String>, Agent?) -> Unit)? = null, block: suspend Req.() -> Reply) {
        handlers[handlerKey(spec)] = block
        admit?.let { admits[handlerKey(spec)] = it }
        val method = io.ktor.http.HttpMethod.parse(spec.method)
        route(spec.path, method) { handle { pipeline(call, spec, admit, block) } }
    }

    private suspend fun notFound(call: ApplicationCall) {
        val rid = newRequestId()
        val source = sourceOf(call)
        call.response.header("X-Request-Id", rid)
        val err = if (!Net.isTailnet(source)) ErrorCatalog.error("forbidden_source", "Only devices on Rahul's Tailscale network may call Bridgit.")
        else ErrorCatalog.error("not_found", "No such endpoint. GET /v1/guide lists them all.")
        call.respondText(err.toJson(rid).toString(), ContentType.Application.Json, HttpStatusCode.fromValue(err.http))
        app.log.append(LogEntry(ts = System.currentTimeMillis(), channel = "audit", kind = "request", level = "warn",
            sourceIp = source, method = call.request.httpMethod.value, endpoint = Redactor.string(call.request.path(), 120),
            http = err.http, result = err.code, requestId = rid, message = "Unknown endpoint"))
    }

    private suspend fun pipeline(call: ApplicationCall, spec: EndpointSpec, admit: (suspend (Map<String, String>, Agent?) -> Unit)?,
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
            if (!Net.isTailnet(source)) throw ErrorCatalog.error("forbidden_source", "Only devices on Rahul's Tailscale network may call Bridgit.")
            if (spec.auth) {
                val token = call.request.headers["Authorization"]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
                when (val r = app.agents.authenticate(token)) {
                    is AuthResult.Ok -> {
                        if(r.agent.connection == net.finleg.bridget.core.Connection.RELAY) throw ErrorCatalog.error("unauthorized", "Relay credentials require the relay transport.")
                        agent = r.agent
                    }
                    else -> {
                        // SEC-06: only requests without a valid key are blocked by the per-IP failure count.
                        // tryAcquire checks and records in one step, so concurrent bad keys can't slip past.
                        if (!limiter.tryAcquire("auth:$source", RateLimiter.AUTH_FAILS_PER_IP)) throw authBlockedError(source)
                        if (r is AuthResult.Revoked) {
                            revokedReason = r.reason
                            throw ErrorCatalog.error("unauthorized",
                                if (r.reason == "key_rotated") "${r.agentName}'s key was rotated. Ask Rahul for the new setup prompt."
                                else "${r.agentName} was removed from Bridgit. Ask Rahul to add you again.",
                                mapOf("reason" to r.reason))
                        }
                        throw ErrorCatalog.error("unauthorized", "Missing or wrong key. Send Authorization: Bearer <your key>.")
                    }
                }
                requireGrant(agent!!, spec)
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
                if (!replay) admit(call.request.queryParameters.entries().associate { it.key to (it.value.firstOrNull() ?: "") }, agent)
            }
            val body = if (spec.method == "POST") {
                val ctype = call.request.headers["Content-Type"] ?: ""
                // Media endpoints take raw bytes (audio/*, image/*, ...), streamed to a size-limited temp file;
                // everything else is a JSON object, held to the normal 2 MB even on those endpoints.
                if (spec.maxBody > MAX_BODY && ctype.isNotEmpty() && !ctype.startsWith("application/json") && (spec.capability == "files" || !ctype.startsWith("text/"))) {
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
                        requireReplayUsable(spec)
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
            chargeCapture(spec, agent, source)
            val reply = runGuarded(r, block)
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
                http = 499; result = "cancelled"
                idemKey?.let { k -> agent?.let { idempotency.abandon(it.id, k) } }
                throw e
            }
            http = 500; result = "internal_error"
            idemKey?.let { k -> agent?.let { idempotency.abandon(it.id, k) } }
            app.log.system("diagnostic", "crash", "Error in ${spec.fullPath}: ${e.javaClass.simpleName} at ${e.stackTrace.firstOrNull()}", level = "error")
            val err = ErrorCatalog.error("internal_error", "Bridgit hit a bug (${e.javaClass.simpleName}). It's in the diagnostic log.")
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

    /** SEC-07 capture limits, per agent and per source address. */
    private fun chargeCapture(spec: EndpointSpec, agent: Agent?, source: String) {
        if (!spec.capture) return
        val a = agent!!
        if (!limiter.tryAcquire("cap-agent:${a.id}", RateLimiter.CAPTURES_PER_AGENT))
            throw ErrorCatalog.error("rate_limited", "At most ${RateLimiter.CAPTURES_PER_AGENT} captures per minute per agent.",
                mapOf("retry_after_s" to limiter.retryAfterS("cap-agent:${a.id}")))
        if (!limiter.tryAcquire("cap-ip:$source", RateLimiter.CAPTURES_PER_IP))
            throw ErrorCatalog.error("rate_limited", "At most ${RateLimiter.CAPTURES_PER_IP} captures per minute from one address.",
                mapOf("retry_after_s" to limiter.retryAfterS("cap-ip:$source")))
    }

    // ---- In-process dispatch for MCP tool calls (API-15) -------------------------------------

    /** Every registered handler, filled while routing is installed, so MCP can run an endpoint without HTTP. */
    private val handlers = java.util.concurrent.ConcurrentHashMap<String, suspend Req.() -> Reply>()
    /** Admission checks (switch, rate, queue) of large-body endpoints; MCP runs them too, it has no upload stage. */
    private val admits = java.util.concurrent.ConcurrentHashMap<String, suspend (Map<String, String>, Agent?) -> Unit>()
    private suspend fun runGuarded(req: Req, block: suspend Req.() -> Reply): Reply = coroutineScope {
        val a = req.agent ?: return@coroutineScope req.block()
        val work = async { req.block() }
        val watcher = launch {
            while (work.isActive) {
                delay(200)
                val live = app.agents.get(a.id)
                val switchedOff = req.spec.capture && app.registry.byId(req.spec.capability)?.enabled == false
                if (live == null || live.keyHash != a.keyHash || !live.allows(req.spec.capability) || switchedOff) {
                    work.cancel(); break
                }
            }
        }
        try {
            val reply = work.await()
            requireGrant(a, req.spec)
            reply
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (e is TimeoutCancellationException) throw ErrorCatalog.error("capture_timeout", "The operation did not finish before its deadline.")
            requireGrant(a, req.spec)
            if (req.spec.capture && app.registry.byId(req.spec.capability)?.enabled == false)
                throw ErrorCatalog.error("capability_disabled", "Capability was switched off during this capture.")
            throw ErrorCatalog.error("conflict", "Authorization changed while this request was running. Start a new request if it is still wanted.")
        } finally { watcher.cancel() }
    }

    private fun requireReplayUsable(spec: EndpointSpec) {
        if (app.registry.byId(spec.capability)?.enabled == false)
            throw ErrorCatalog.error("capability_disabled", "The capability is off; cached responses are unavailable.")
    }

    private fun requireGrant(agent: Agent, spec: EndpointSpec) {
        val live = app.agents.get(agent.id) ?: throw ErrorCatalog.error("unauthorized", "Agent removed.")
        if (live.keyHash != agent.keyHash) throw ErrorCatalog.error("unauthorized", "Agent key was rotated during the request.")
        if (!live.allows(spec.capability)) throw ErrorCatalog.error("agent_not_allowed", "This agent does not have ${spec.capability} access. Ask the owner to update its permissions on the phone.")
    }

    private fun handlerKey(spec: EndpointSpec) = "${spec.method} ${spec.path}"

    /** The MCP endpoint's JSON-RPC side; StatusModule routes POST /v1/mcp to it. */
    val mcp = McpDispatch(this)

    /** What an in-process call produced: the rendered reply (an error JSON when [error] is set). */
    class Outcome(val status: Int, val contentType: String, val bytes: ByteArray, val headers: Map<String, String>, val error: ApiError? = null)

    /**
     * Runs [spec]'s handler in-process for an MCP tool call, with the checks the HTTP pipeline applies
     * once the key is accepted: developerOnly, Idempotency-Key, capture limits (the handler checks its own
     * switch and rate limits as usual). Writes its own log entry for the tool's real path, marked via=mcp.
     * The caller (POST /v1/mcp) has already passed the source and key checks for [agent].
     */
    suspend fun dispatch(spec: EndpointSpec, agent: Agent, source: String, queries: Map<String, String>,
                         pathParams: Map<String, String>, body: JSONObject, idempotencyKey: String?, via: String = "mcp"): Outcome {
        val started = System.currentTimeMillis()
        val rid = newRequestId()
        val logParams = JSONObject()
        queries.forEach { (k, v) -> logParams.put(k, v) }
        pathParams.forEach { (k, v) -> logParams.put(k, v) }
        body.keys().forEach { k ->
            val v = body.get(k)
            logParams.put(k, if (k in spec.privateFields) "[${(v as? org.json.JSONArray)?.length()?.let { "$it items" } ?: "${v.toString().length} chars"}]" else v)
        }
        logParams.put("via", via)
        val r = Req(spec, agent, rid, source, body, null, queries, pathParams) { name ->
            if (name.equals("Content-Type", ignoreCase = true)) "application/json" else null
        }
        r.viaMcp = true
        var http = 200
        var result = "ok"
        var idemKey: String? = null
        try {
            requireGrant(agent, spec)
            val block = handlers[handlerKey(spec)] ?: throw ErrorCatalog.error("not_found", "No such endpoint.")
            if (spec.developerOnly && !agent.developer) throw ErrorCatalog.error("forbidden",
                "Only developer agents can read bug reports. Ask Rahul to turn on Developer for ${agent.name}.", mapOf("reason" to "developer_only"))
            idemKey = idempotencyKey?.trim()?.takeIf { spec.idempotent }
            if (idemKey != null) {
                if (!IdempotencyCache.KEY_RE.matches(idemKey)) { idemKey = null
                    throw ErrorCatalog.error("invalid_parameter", "idempotency_key must be 8-128 letters, digits, - or _ (a UUID works).") }
                logParams.put("idempotency", "key")
                when (val c = idempotency.claim(agent.id, idemKey)) {
                    is IdempotencyCache.Claim.Full -> { idemKey = null; throw ErrorCatalog.error("rate_limited", "Too many Idempotency-Keys in the last 24 h. Retry later.", mapOf("retry_after_s" to 60)) }
                    is IdempotencyCache.Claim.Running -> { idemKey = null; throw ErrorCatalog.error("in_progress", "A request with this idempotency_key is still running. Retry with the same key shortly.") }
                    is IdempotencyCache.Claim.Expired -> { idemKey = null; throw ErrorCatalog.error("idempotency_expired", "This idempotency_key was already used; its media result is no longer kept.") }
                    is IdempotencyCache.Claim.Done -> {
                        idemKey = null
                        requireReplayUsable(spec)
                        http = c.stored.status
                        logParams.put("replay", true)
                        var bytes = c.stored.body!!
                        if (c.stored.contentType.startsWith("application/json")) runCatching {
                            val o = JSONObject(String(bytes))
                            val first = o.optString("request_id")
                            bytes = o.put("request_id", rid).put("idempotent_replay_of", first).toString().toByteArray()
                            r.data = JSONObject().put("replay_of", first)
                        }
                        r.bytesOut = bytes.size.toLong()
                        r.message = "${agent.name}: replayed ${spec.method} ${spec.fullPath} (idempotency_key, via MCP)"
                        return Outcome(http, c.stored.contentType, bytes, c.stored.headers + ("Idempotent-Replay" to "true"))
                    }
                    IdempotencyCache.Claim.New -> {}
                }
            }
            admits[handlerKey(spec)]?.invoke(queries, agent)
            chargeCapture(spec, agent, source)
            val (status, ctype, bytes, headers) = render(runGuarded(r, block), rid)
            http = status
            if (r.bytesOut == 0L) r.bytesOut = bytes.size.toLong()
            idemKey?.let { idempotency.finish(agent.id, it, status, ctype, bytes, headers, spec.media); idemKey = null }
            return Outcome(status, ctype, bytes, headers)
        } catch (e: ApiError) {
            http = e.http; result = e.code
            val bytes = e.toJson(rid).toString().toByteArray()
            idemKey?.let { idempotency.finish(agent.id, it, e.http, "application/json", bytes, emptyMap(), false) }
            return Outcome(e.http, "application/json", bytes, emptyMap(), e)
        } catch (e: Exception) {
            idemKey?.let { idempotency.abandon(agent.id, it) }
            if (e is kotlinx.coroutines.CancellationException) { http = 499; result = "cancelled"; throw e }
            http = 500; result = "internal_error"
            app.log.system("diagnostic", "crash", "Error in ${spec.fullPath} (via MCP): ${e.javaClass.simpleName} at ${e.stackTrace.firstOrNull()}", level = "error")
            val err = ErrorCatalog.error("internal_error", "Bridgit hit a bug (${e.javaClass.simpleName}). It's in the diagnostic log.")
            return Outcome(500, "application/json", err.toJson(rid).toString().toByteArray(), emptyMap(), err)
        } finally {
            r.params.keys().forEach { logParams.put(it, r.params.get(it)) }
            val msg = r.message?.let { if ("MCP" in it) it else "$it (via MCP)" } ?: (describe(agent.name, spec, http, result) + " (via MCP)")
            app.log.append(LogEntry(
                ts = started, channel = "audit", kind = "request",
                level = when { http >= 500 -> "error"; http >= 400 -> "warn"; else -> "info" },
                agentId = agent.id, agent = agent.name, sourceIp = source, method = spec.method, endpoint = spec.fullPath,
                params = Redactor.params(logParams), http = http, result = result, bytesIn = body.toString().length.toLong(),
                bytesOut = r.bytesOut, durationMs = System.currentTimeMillis() - started, requestId = rid,
                message = Redactor.string(msg, 300),
                data = (r.data ?: JSONObject()).put("via", via).let { Redactor.params(it) }))
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
        is Reply.Text -> Rendered(reply.status, reply.contentType, reply.text.toByteArray(), emptyMap())
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
