package net.finleg.bridget.api

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import net.finleg.bridget.capability.appVersion
import org.json.JSONArray
import org.json.JSONObject

/**
 * API-15: `POST /v1/mcp`, MCP over Streamable HTTP with JSON replies (no SSE, stateless: no
 * Mcp-Session-Id). The agent's normal key authenticates the POST through the normal pipeline; each
 * tools/call then runs the endpoint's own handler in-process through [ApiServer.dispatch], which applies
 * the per-endpoint checks and writes the tool's own log entry. No loopback listener is opened.
 *
 * tools/list: every endpoint except MCP, pairing, the helper and openapi.json; developer-only ones only
 * for developer agents. Endpoints whose switch is off are listed with a "switched off" note (the list is
 * advertised as not changing, so clients that cache it still see a tool once Rahul turns it on).
 */
class McpDispatch(private val api: ApiServer) {

    suspend fun handle(r: Req): Reply {
        // A browser page must never drive Bridget's tools (DNS rebinding and the like); MCP clients send no Origin.
        r.header("Origin")?.takeIf { it.isNotBlank() }?.let {
            throw ErrorCatalog.error("forbidden", "Browser pages may not call /v1/mcp.", mapOf("reason" to "origin"))
        }
        val agent = r.agent!!
        return when (val m = McpProtocol.parse(r.body)) {
            is McpProtocol.Message.Invalid -> { r.params.put("rpc", "invalid"); rpc(McpProtocol.error(m.id, m.code, m.message)) }
            is McpProtocol.Message.Notification -> {
                r.params.put("rpc", m.method.take(60))
                r.message = "${agent.name}: MCP ${m.method.take(60)}"
                Reply.Text("", "application/json", 202)
            }
            is McpProtocol.Message.Request -> {
                r.params.put("rpc", m.method.take(60))
                r.message = "${agent.name}: MCP ${m.method.take(60)}"
                rpc(request(r, m))
            }
        }
    }

    private suspend fun request(r: Req, m: McpProtocol.Message.Request): JSONObject {
        val agent = api.app.agents.get(r.agent!!.id) ?: r.agent
        return when (m.method) {
            "initialize" -> McpProtocol.result(m.id, McpProtocol.initializeResult(m.params.optString("protocolVersion").ifEmpty { null },
                api.app.appVersion(), agent.name))
            "ping" -> McpProtocol.result(m.id, JSONObject())
            "tools/list" -> {
                val tools = JSONArray()
                visible(agent.developer).forEach { e ->
                    val off = api.app.registry.byId(e.capability)?.enabled == false
                    tools.put(McpTools.tool(e, off))
                }
                McpProtocol.result(m.id, JSONObject().put("tools", tools))
            }
            "tools/call" -> {
                val name = m.params.optString("name")
                val spec = McpTools.byName(name)?.takeIf { it in visible(agent.developer) }
                    ?: return McpProtocol.error(m.id, McpProtocol.INVALID_PARAMS, "Unknown tool \"${name.take(60)}\". Call tools/list.")
                r.params.put("tool", name)
                r.message = "${agent.name}: MCP tools/call $name"
                val args = when (val a = m.params.opt("arguments")) {
                    null, JSONObject.NULL -> JSONObject()
                    is JSONObject -> a
                    else -> return McpProtocol.error(m.id, McpProtocol.INVALID_PARAMS, "arguments must be an object.")
                }
                val routed = try { McpTools.route(spec, args) } catch (e: McpTools.ArgError) {
                    val err = ErrorCatalog.error("invalid_parameter", e.message ?: "Bad arguments.")
                    return McpProtocol.result(m.id, McpTools.result(err.http, "application/json", err.toJson(r.requestId).toString().toByteArray()))
                }
                val out = api.dispatch(spec, agent, r.source, routed.queries, routed.pathParams, routed.body, routed.idempotencyKey)
                McpProtocol.result(m.id, McpTools.result(out.status, out.contentType, out.bytes, out.headers))
            }
            else -> McpProtocol.error(m.id, McpProtocol.METHOD_NOT_FOUND, "Method not found: ${m.method.take(60)}")
        }
    }

    private fun visible(developer: Boolean) = McpTools.specs.filter { developer || !it.developerOnly }

    /** The JSON-RPC envelope as is: Reply.Json would add request_id to it, which strict clients reject. */
    private fun rpc(o: JSONObject) = Reply.Text(o.toString(), "application/json")

    /** GET (an SSE stream) and DELETE (end a session) aren't offered: 405, which MCP clients expect. */
    fun Route.installExtras() {
        listOf(HttpMethod.Get, HttpMethod.Delete).forEach { m ->
            route(Endpoints.MCP.path, m) { handle { call.response.header("Allow", "POST"); call.respond(HttpStatusCode.MethodNotAllowed) } }
        }
    }
}
