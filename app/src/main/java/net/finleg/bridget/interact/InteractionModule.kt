package net.finleg.bridget.interact

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
import net.finleg.bridget.capability.Gallery
import net.finleg.bridget.capability.ImageUtil
import net.finleg.bridget.capability.isoAt
import org.json.JSONArray
import org.json.JSONObject

/**
 * 8.13 Interactions and section 9 events. Master switch "Interactions" (off by default, SEC-03).
 * Every read is the calling agent's own (ownership re-checked after any wait).
 */
class InteractionModule(app: BridgeApp) : Capability(app) {
    override val id = "interactions"
    override val titleRes = R.string.cap_interactions
    override val descriptionRes = R.string.cap_interactions_desc
    override val group = "talk"
    override val switchKey = "interactions"
    override val endpoints = listOf(Endpoints.INTERACTION_CREATE, Endpoints.INTERACTION_LIST, Endpoints.INTERACTION_GET,
        Endpoints.INTERACTION_CANCEL, Endpoints.EVENTS, Endpoints.CALLBACK_TEST)
    override fun blockedReason(): String? = if (!app.captureNotificationsOn()) "notifications_off" else null

    private fun fail(e: InteractionError): Nothing = when (e.code) {
        "not_found" -> throw ErrorCatalog.error("not_found", e.message ?: "Not found.")
        "too_many_open" -> throw ErrorCatalog.error("too_many_open", e.message ?: "Too many open.")
        else -> throw ErrorCatalog.error("invalid_parameter", e.message ?: "Bad interaction.")
    }

    private fun Req.waitArg(): Int = query("wait")?.let { it.toIntOrNull()?.takeIf { n -> n in 0..55 }
        ?: throw ErrorCatalog.error("invalid_parameter", "wait must be 0 to 55.") } ?: 0

    private fun json(i: Interaction) = i.toJson(::isoAt)

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(Endpoints.INTERACTION_CREATE) {
            if (!enabled) throw ErrorCatalog.error("capability_disabled", "Interactions are switched off on the phone. Ask Rahul to turn them on in Features.",
                mapOf("setting" to switchKey))
            if (!app.captureNotificationsOn()) throw ErrorCatalog.error("notifications_required",
                "Bridget's notifications are off, so Rahul wouldn't see your message. Ask him to turn them on.")
            val a = agent!!
            val d = try { InteractionRules.parse(body) } catch (e: InteractionError) { fail(e) }
            val jpeg: ByteArray? = when {
                d.imageGalleryId != null -> {
                    val g = Gallery(app)
                    if (g.accessMode() == null || !app.settings.isOn("gallery"))
                        throw ErrorCatalog.error("capability_disabled", "image.gallery_id needs the Gallery switch and photo permission.", mapOf("setting" to "gallery"))
                    val item = g.find(d.imageGalleryId) ?: throw ErrorCatalog.error("photo_not_found", "No photo with that id.")
                    val bytes = g.read(item.id) ?: throw ErrorCatalog.error("photo_not_found", "That photo could not be opened.")
                    ImageUtil.prepare(bytes, item.mime, Sizing.PRESETS.getValue("agent"), forceJpeg = true).bytes
                }
                d.imageBase64 != null -> {
                    val raw = runCatching { Base64.decode(d.imageBase64, Base64.DEFAULT) }.getOrNull()
                        ?: throw ErrorCatalog.error("invalid_parameter", "image.data_base64 is not base64.")
                    if (raw.size > InteractionRules.IMAGE_MAX_BYTES) throw ErrorCatalog.error("invalid_parameter", "image is limited to 2 MB.")
                    net.finleg.bridget.feedback.bytesToDownscaledJpeg(raw) ?: throw ErrorCatalog.error("invalid_parameter", "image is not a picture.")
                }
                else -> null
            }
            val i = try { app.interactions.create(a.id, a.name, d, jpeg) } catch (e: InteractionError) { fail(e) }
            params.put("controls", d.controls.size)
            message = "${a.name} sent ${if (d.kind == IKind.QUESTION) "a question" else "a notice"}: ${d.title.take(60)}"
            data = JSONObject().put("interaction_id", i.id).put("kind", d.kind.id)
            Reply.Json(JSONObject().put("interaction_id", i.id).put("status", i.status.id).put("expires_at", isoAt(i.expiresAt)), status = 201)
        }

        endpoint(Endpoints.INTERACTION_GET) {
            val a = agent!!
            val id = path("id")?.toLongOrNull() ?: throw ErrorCatalog.error("invalid_parameter", "id must be an interaction id.")
            var i = app.interactions.getFor(a.id, id) ?: throw ErrorCatalog.error("not_found", "No interaction $id of yours.")
            val wait = waitArg()
            val waitingFor = setOf(IStatus.PENDING, IStatus.SEEN)
            if (wait > 0 && i.status in waitingFor && (i.kind == IKind.QUESTION || i.status == IStatus.PENDING)) {
                if (!api.waits.enter(a.id)) throw ErrorCatalog.error("too_many_waits", "At most 4 open waits per agent.")
                try {
                    withTimeoutOrNull(wait * 1000L) {
                        var seen = app.interactions.changed.value
                        while (true) {
                            val now = app.interactions.getFor(a.id, id) ?: break
                            // A question waits for an answer or close; a notice for being seen or closed.
                            if (if (now.kind == IKind.QUESTION) !now.status.open else now.status != IStatus.PENDING) break
                            app.interactions.changed.first { it > seen }
                            seen = app.interactions.changed.value
                        }
                    }
                } finally { api.waits.exit(a.id) }
                i = app.interactions.getFor(a.id, id) ?: throw ErrorCatalog.error("not_found", "No interaction $id of yours.")
            }
            Reply.Json(json(i))
        }

        endpoint(Endpoints.INTERACTION_CANCEL) {
            val a = agent!!
            val id = path("id")?.toLongOrNull() ?: throw ErrorCatalog.error("invalid_parameter", "id must be an interaction id.")
            val i = try { app.interactions.cancel(a.id, id) } catch (e: InteractionError) { fail(e) }
            Reply.Json(json(i))
        }

        endpoint(Endpoints.INTERACTION_LIST) {
            val a = agent!!
            val status = query("status")?.let { IStatus.of(it) ?: throw ErrorCatalog.error("invalid_parameter", "Unknown status \"$it\".") }
            val limit = query("limit")?.let { it.toIntOrNull()?.takeIf { n -> n in 1..100 } ?: throw ErrorCatalog.error("invalid_parameter", "limit must be 1 to 100.") } ?: 50
            Reply.Json(JSONObject().put("interactions", JSONArray(app.interactions.listFor(a.id, status, limit).map { json(it) })))
        }

        // EVT-06: a signed test event to the caller's callback URL, sent now.
        endpoint(Endpoints.CALLBACK_TEST) { app.callbackDispatcher.test(this) }

        endpoint(Endpoints.EVENTS) {
            val a = agent!!
            val since = query("since")?.let { it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: throw ErrorCatalog.error("invalid_parameter", "since must be a cursor from next_cursor.") }
            val types = query("types")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
            val wait = waitArg()
            var seen = app.interactions.changed.value
            var list = app.interactions.eventsFor(a.id, since, types)
            if (list.isEmpty() && wait > 0 && since != null) {
                if (!api.waits.enter(a.id)) throw ErrorCatalog.error("too_many_waits", "At most 4 open waits per agent.")
                try {
                    withTimeoutOrNull(wait * 1000L) {
                        while (list.isEmpty()) {
                            app.interactions.changed.first { it > seen }
                            seen = app.interactions.changed.value
                            list = app.interactions.eventsFor(a.id, since, types)
                        }
                    }
                } finally { api.waits.exit(a.id) }
            }
            val next = list.lastOrNull()?.id ?: since ?: 0L
            params.put("returned", list.size)
            Reply.Json(JSONObject().put("events", JSONArray(list.map { it.toJson(::isoAt) })).put("next_cursor", next.toString()))
        }
    }
}
