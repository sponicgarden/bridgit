package net.finleg.bridget.feedback

import android.util.Base64
import io.ktor.server.routing.Route
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import net.finleg.bridget.BridgeApp
import net.finleg.bridget.R
import net.finleg.bridget.api.ApiServer
import net.finleg.bridget.api.Endpoints
import net.finleg.bridget.api.ErrorCatalog
import net.finleg.bridget.api.Reply
import net.finleg.bridget.api.Req
import net.finleg.bridget.api.Sizing
import net.finleg.bridget.capability.Capability
import net.finleg.bridget.capability.ImageUtil
import net.finleg.bridget.capability.isoAt
import net.finleg.bridget.log.LogQuery
import org.json.JSONArray
import org.json.JSONObject

/**
 * Section 11.5: the feedback API. Reading and moving reports needs the agent's
 * Developer switch (AGT-15), because reports carry screenshots of Rahul's phone.
 * Any agent may file a report (FBK-12). Always on: there is no master switch.
 */
class FeedbackModule(app: BridgeApp) : Capability(app) {
    override val id = "feedback"
    override val titleRes = R.string.cap_feedback
    override val descriptionRes = R.string.cap_feedback_desc
    override val group = "status"
    override val endpoints = listOf(Endpoints.FEEDBACK_LIST, Endpoints.FEEDBACK_GET, Endpoints.FEEDBACK_STATUS, Endpoints.FEEDBACK_POST)

    private fun Req.requireDeveloper() {
        val current = agent?.let { app.agents.get(it.id) }
        if (current?.developer != true) throw ErrorCatalog.error("forbidden",
            "Only developer agents can read bug reports. Ask Rahul to turn on Developer for $agentName.", mapOf("reason" to "developer_only"))
    }

    private fun mapError(e: FeedbackError): Nothing = when (e.code) {
        "not_found" -> throw ErrorCatalog.error("not_found", e.message ?: "No such report.")
        "rate_limited" -> throw ErrorCatalog.error("rate_limited", e.message ?: "Too many reports.", mapOf("retry_after_s" to 3600))
        "internal" -> throw ErrorCatalog.error("internal_error", e.message ?: "The report couldn't be saved.")
        else -> throw ErrorCatalog.error("invalid_parameter", e.message ?: "Bad report.")
    }

    private fun Req.reportId(): Long = path("id")?.toLongOrNull()
        ?: throw ErrorCatalog.error("invalid_parameter", "id must be a report number, e.g. 7.")

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(Endpoints.FEEDBACK_LIST) {
            requireDeveloper()
            val status = query("status")?.let { ReportStatus.of(it) ?: throw ErrorCatalog.error("invalid_parameter", "Unknown status \"$it\".") }
            val kind = query("kind")?.let { ReportKind.of(it) ?: throw ErrorCatalog.error("invalid_parameter", "kind must be bug or feature.") }
            val since = query("since")?.let { LogQuery.iso(it) ?: throw ErrorCatalog.error("invalid_parameter", "since must be an ISO time.") }
            val limit = query("limit")?.let { it.toIntOrNull()?.takeIf { n -> n in 1..100 } ?: throw ErrorCatalog.error("invalid_parameter", "limit must be 1 to 100.") } ?: 50
            val wait = query("wait")?.let { it.toIntOrNull()?.takeIf { n -> n in 0..55 } ?: throw ErrorCatalog.error("invalid_parameter", "wait must be 0 to 55.") } ?: 0
            if (wait > 0 && since == null) throw ErrorCatalog.error("invalid_parameter", "wait needs since (an ISO time).")
            // Read the change counter before querying, so a report filed in between is never missed.
            var seen = app.feedback.changed.value
            var list = app.feedback.list(status, kind, since, limit)
            if (list.isEmpty() && wait > 0) {
                if (!api.waits.enter(agent!!.id)) throw ErrorCatalog.error("too_many_waits", "At most 4 open waits per agent.")
                try {
                    withTimeoutOrNull(wait * 1000L) {
                        while (list.isEmpty()) {
                            app.feedback.changed.first { it > seen }
                            seen = app.feedback.changed.value
                            list = app.feedback.list(status, kind, since, limit)
                        }
                    }
                } finally { api.waits.exit(agent!!.id) }
                // The Developer switch may have been turned off (or the agent removed) during the wait.
                requireDeveloper()
            }
            params.put("returned", list.size)
            Reply.Json(JSONObject().put("reports", JSONArray(list.map { it.summaryJson(::isoAt) })))
        }

        endpoint(Endpoints.FEEDBACK_GET) {
            requireDeveloper()
            val id = reportId()
            var r = app.feedback.get(id) ?: throw ErrorCatalog.error("not_found", "No report $id.")
            // FBK-11a: the first developer read moves a new (or reopened) report to seen.
            if (r.status == ReportStatus.NEW || r.status == ReportStatus.REOPENED)
                r = runCatching { app.feedback.move(id, ReportStatus.SEEN, Actor.AUTO, agentName, null) }.getOrDefault(r)
            val spec = Sizing.resolve({ arg(it) }, "agent")
            var total = 0L
            val images = JSONArray()
            r.images.forEachIndexed { i, name ->
                val f = app.feedback.imageFile(name) ?: return@forEachIndexed
                val img = ImageUtil.prepare(f.readBytes(), "image/jpeg", spec)
                total += img.bytes.size
                images.put(JSONObject().put("index", i + 1).put("width_px", img.width).put("height_px", img.height)
                    .put("size_bytes", img.bytes.size).put("data_base64", Base64.encodeToString(img.bytes, Base64.NO_WRAP)))
            }
            if (total * 4 / 3 > ApiServer.MAX_PAYLOAD) throw ErrorCatalog.error("payload_too_large", "The images are over 12 MB. Ask with size=thumb.")
            bytesOut = total
            data = JSONObject().put("report", r.number)
            Reply.Json(r.summaryJson(::isoAt).put("description", r.description).put("context", r.context)
                .put("images", images).put("log", r.log)
                .put("history", JSONArray(r.history.map { it.toJson(::isoAt) }))
                .put("allowed_next", JSONArray(StatusMachine.nextForDeveloper(r.status).map { it.id })))
        }

        endpoint(Endpoints.FEEDBACK_STATUS) {
            requireDeveloper()
            val id = reportId()
            val to = ReportStatus.of(body.optString("status")) ?: throw ErrorCatalog.error("invalid_parameter",
                "status must be in_progress, fixed, wont_fix or duplicate.")
            val note = body.optString("note", "").ifBlank { null }
            val r = try { app.feedback.move(id, to, Actor.DEVELOPER, agentName, note) } catch (e: FeedbackError) { mapError(e) }
            message = "$agentName moved report ${r.number} to ${to.id}"
            data = JSONObject().put("report", r.number).put("status", to.id)
            Reply.Json(r.summaryJson(::isoAt))
        }

        endpoint(Endpoints.FEEDBACK_POST) {
            val a = agent!!
            val kind = ReportKind.of(body.optString("kind")) ?: throw ErrorCatalog.error("invalid_parameter", "kind must be bug or feature.")
            val description = body.optString("description", "")
            val imgs = body.optJSONArray("images")
            if (imgs != null && imgs.length() > 0 && app.agents.get(a.id)?.developer != true)
                throw ErrorCatalog.error("forbidden", "Only developer agents may attach images.", mapOf("reason" to "developer_only"))
            if (imgs != null && imgs.length() > FeedbackLimits.MAX_IMAGES) throw ErrorCatalog.error("invalid_parameter", "Up to 5 images.")
            val jpegs = (0 until (imgs?.length() ?: 0)).map { i ->
                val b64 = imgs!!.optJSONObject(i)?.optString("data_base64", "") ?: ""
                val raw = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                    ?: throw ErrorCatalog.error("invalid_parameter", "images[$i].data_base64 is not base64.")
                bytesToDownscaledJpeg(raw) ?: throw ErrorCatalog.error("invalid_parameter", "images[$i] is not an image.")
            }
            // The attached log is generated by Bridgit, never supplied by the agent (FBK-12).
            val r = try {
                app.feedback.create(kind, description, body.optBoolean("no_autofix", false), jpegs, FeedbackContext.recentLog(app),
                    FeedbackContext.build(app, null, null, "agent"), "agent", a.id, a.name)
            } catch (e: FeedbackError) { mapError(e) }
            params.put("description", "[${description.length} chars]")
            message = "${a.name} filed ${if (kind == ReportKind.BUG) "a bug report" else "a feature request"} ${r.number}"
            data = JSONObject().put("report", r.number)
            Reply.Json(JSONObject().put("id", r.id).put("number", r.number).put("status", r.status.id).put("no_autofix", r.noAutofix), status = 201)
        }
    }
}
