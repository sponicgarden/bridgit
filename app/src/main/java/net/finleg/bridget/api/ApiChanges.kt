package net.finleg.bridget.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * API revisions (API-14, owner request 27 Oct 2026). The path stays /v1; each release that
 * adds to the API bumps [CURRENT] and adds an entry here. Every reply carries
 * `X-Bridget-API-Revision`; agents that haven't caught up also get `X-Bridget-Whats-New`.
 * Pure Kotlin, unit-tested against [Endpoints].
 */
object ApiChanges {
    data class Item(val what: String, val endpoint: String? = null)
    data class Revision(val revision: Int, val appVersion: String, val date: String, val items: List<Item>)

    val revisions = listOf(
        Revision(1, "1.3.0", "2026-10-27", listOf(
            Item("Health, capabilities (enabled / ready / blocked_reason), this guide and OpenAPI", "/v1/health"),
            Item("Camera photo with size presets and raw=1", "/v1/camera/snapshot"),
            Item("Microphone clip, 1-120 s", "/v1/mic/record"),
            Item("Gallery list", "/v1/gallery/recent"),
            Item("One gallery photo, any size, raw=1", "/v1/gallery/photo/{id}"),
            Item("Newest screenshot or photo; since + wait for the next one", "/v1/image/last"),
            Item("Log: query, follow live, stats, export, notes; /v1/audit alias", "/v1/log"),
            Item("Idempotency-Key on POSTs; pending (202) schema", null),
            Item("The bridget helper download", "/v1/tools/bridget"),
        )),
        Revision(2, "1.4.0", "2026-10-27", listOf(
            Item("File a bug report or feature request (any agent)", "/v1/feedback"),
            Item("Developer agents: list and read reports, move their status", "/v1/feedback/{id}/status"),
        )),
        Revision(3, "1.4.4", "2026-10-27", listOf(
            Item("Camera zoom: \"zoom\": 0.5 (ultra-wide) to the phone's maximum; range in /v1/capabilities", "/v1/camera/snapshot"),
            Item("API revisions: X-Bridget-API-Revision on every reply, X-Bridget-Whats-New until you've caught up, and this list", "/v1/changes"),
        )),
        Revision(4, "1.5.0", "2026-10-27", listOf(
            Item("Interactions: send Rahul a notice or a question with buttons, a text reply, a choice or links; read aloud if you ask", "/v1/interactions"),
            Item("Wait for his answer (wait=55), list or cancel your interactions", "/v1/interactions/{id}"),
            Item("Events: answers, closes and expiries for you, oldest first, with long-poll", "/v1/events"),
        )),
        Revision(5, "1.5.0", "2026-10-27", listOf(
            Item("Location with altitude, speed, heading and the compass", "/v1/location"),
            Item("Play audio or read text aloud on the phone; newer narration replaces what's queued", "/v1/speaker/play"),
            Item("Speak text with the phone's own voice", "/v1/speaker/say"),
            Item("What's playing; stop your audio", "/v1/speaker"),
            Item("City tour: your running tour (tour.started / tour.tick / tour.question / tour.ended events)", "/v1/tour"),
        )),
        Revision(6, "1.5.2", "2026-10-27", listOf(
            Item("Report your model and harness; Rahul sees it on your agent card", "/v1/me"),
            Item("City tour brief in tour.started and GET /v1/tour: directions relative to him, nearest interesting places when nothing is left here", "/v1/tour"),
            Item("tour.photo: Rahul took a photo for you on the tour; fetch it", "/v1/tour/photo/{n}"),
        )),
        Revision(7, "1.5.3", "2026-10-27", listOf(
            Item("HTML cards: kind=html draws your own card (offline, Bridget.respond(json) answers)", "/v1/interactions"),
            Item("Live text panel: kind=text, then stream text into it", "/v1/text/stream"),
            Item("Callbacks: Rahul can set a callback URL for you; events are POSTed signed with HMAC-SHA256; send a test", "/v1/callback/test"),
            Item("MCP: use Bridget as an MCP server (Streamable HTTP) with your key", "/v1/mcp"),
            Item("Speaker status: playing shows only your own sound; busy and paused added", "/v1/speaker"),
            Item("End tour: optional tour_id so you only end the tour you mean", "/v1/tour"),
            Item("Screenshot on request: what's on Rahul's screen now, nothing saved (once he turns on Screenshots)", "/v1/screen/capture"),
            Item("Gallery with \"Select photos\" access: photo_not_found and /v1/gallery/recent say how Rahul allows all", "/v1/gallery/recent"),
        )),
    )

    val CURRENT: Int = revisions.maxOf { it.revision }

    /** Revisions after [since] (0 = everything). */
    fun since(since: Int): List<Revision> = revisions.filter { it.revision > since }

    fun json(since: Int, appVersion: String): JSONObject = JSONObject()
        .put("api_revision", CURRENT).put("app_version", appVersion).put("since", since)
        .put("changes", JSONArray(since(since).map { r ->
            JSONObject().put("revision", r.revision).put("app_version", r.appVersion).put("date", r.date)
                .put("items", JSONArray(r.items.map { i -> JSONObject().put("what", i.what).put("endpoint", i.endpoint ?: JSONObject.NULL) }))
        }))
        .put("links", links())

    /** Where an agent learns the current API (API-09). */
    fun links(): JSONObject = JSONObject().put("guide", "/v1/guide").put("openapi", "/v1/openapi.json")
        .put("capabilities", "/v1/capabilities").put("changes", "/v1/changes")

    /** health's "whats_new": null when the agent is up to date. */
    fun whatsNew(known: Int): Any = if (known >= CURRENT) JSONObject.NULL else JSONObject()
        .put("since", known).put("new_revisions", since(known).size)
        .put("url", "/v1/changes?since=$known")
        .put("summary", since(known).flatMap { it.items }.take(5).joinToString("; ") { it.what })
}
