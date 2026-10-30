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
            Item("Interactions: send the owner a notice or a question with buttons, a text reply, a choice or links; read aloud if you ask", "/v1/interactions"),
            Item("Wait for their answer (wait=55), list or cancel your interactions", "/v1/interactions/{id}"),
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
            Item("Report your model and harness; the owner sees it on your agent card", "/v1/me"),
            Item("City tour brief in tour.started and GET /v1/tour: directions relative to them, nearest interesting places when nothing is left here", "/v1/tour"),
            Item("tour.photo: the owner took a photo for you on the tour; fetch it", "/v1/tour/photo/{n}"),
        )),
        Revision(7, "1.5.2", "2026-10-27", listOf(
            Item("HTML cards: kind=html draws your own card (offline, Bridget.respond(json) answers)", "/v1/interactions"),
            Item("Live text panel: kind=text, then stream text into it", "/v1/text/stream"),
            Item("Callbacks: the owner can set a callback URL for you; events are POSTed signed with HMAC-SHA256; send a test", "/v1/callback/test"),
            Item("MCP: use Bridgit as an MCP server (Streamable HTTP) with your key", "/v1/mcp"),
            Item("Speaker status: playing shows only your own sound; busy and paused added", "/v1/speaker"),
            Item("End tour: optional tour_id so you only end the tour you mean", "/v1/tour"),
            Item("Screenshot on request: what's on the owner's screen now, nothing saved (once they turn on Screenshots)", "/v1/screen/capture"),
            Item("Gallery with \"Select photos\" access: photo_not_found and /v1/gallery/recent say how the owner allows all", "/v1/gallery/recent"),
        )),
        Revision(8, "1.6.0", "2026-10-29", listOf(
            Item("Device context, sensors, flashlight, timed sound/display controls and find-phone ring", "/v1/phone/status"),
            Item("Private file transfers, owner-selected Downloads, Android sharing and clipboard approval", "/v1/files"),
            Item("On-device OCR and transcription; short video and accessible gallery videos", "/v1/ocr"),
            Item("Read-only Health Connect, app usage and opt-in notification text", "/v1/health/records"),
            Item("Owner-approved, per-agent MediaProjection screen-sharing grants", "/v1/screen/grant"),
            Item("Sensors", "/v1/sensors"), Item("Flashlight", "/v1/flashlight"),
            Item("Phone context", "/v1/phone/context"), Item("DND", "/v1/phone/dnd"), Item("Sound", "/v1/phone/sound"),
            Item("Brightness", "/v1/phone/brightness"), Item("Undo timed changes", "/v1/phone/undo"), Item("Ring", "/v1/phone/ring"),
            Item("Shares", "/v1/shares"), Item("Downloads", "/v1/downloads"), Item("Clipboard", "/v1/clipboard"),
            Item("Video recording", "/v1/camera/video"), Item("Gallery videos", "/v1/gallery/videos/recent"), Item("Gallery video bytes", "/v1/gallery/video/{id}"),
            Item("Notification reads", "/v1/notifications"), Item("Usage summaries", "/v1/usage"), Item("Shared-screen capture", "/v1/screen/share/capture"),
            Item("Per-agent capability grants, enforced for HTTP and MCP", "/v1/capabilities"),
        )),
        Revision(9, "1.6.0", "2026-10-30", listOf(
            Item("A demo script: offer the owner a hands-on tour of what you can do, tailored by a few questions", "/v1/demo"),
        )),
        Revision(10, "1.6.0", "2026-10-30", listOf(
            Item("Wake words: the owner maps words like \"hey bridget\" to you; what they say next arrives as a listen.heard event. Your wake words and whether the phone is listening", "/v1/listen"),
            Item("The audio after a wake word, when the owner chose to send it", "/v1/listen/clip/{id}"),
        )),
        Revision(11, "1.6.0", "2026-10-30", listOf(
            Item("Listen for any sounds: the owner can have the phone tell you when the room gets loud (sound.heard events), and how it's set", "/v1/sounds"),
            Item("The audio of a sound, when the owner lets sounds send audio", "/v1/sounds/clip/{id}"),
        )),
        Revision(12, "1.6.0", "2026-10-31", listOf(
            Item("A five-minute first-run demo to offer right after pairing: ${Demos.SKILL_URL}", null),
            Item("speaker/say and speaker/play reply status \"silenced\" when the phone is on silent, Do Not Disturb or in a call", "/v1/speaker/say"),
            Item("Flashlight on a device without one: capabilities shows blocked_reason no_hardware and the call fails with not_supported", "/v1/flashlight"),
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
    fun links(): JSONObject = JSONObject().put("guide", "/v1/guide").put("demo", "/v1/demo").put("openapi", "/v1/openapi.json")
        .put("capabilities", "/v1/capabilities").put("changes", "/v1/changes")

    /** health's "whats_new": null when the agent is up to date. */
    fun whatsNew(known: Int): Any = if (known >= CURRENT) JSONObject.NULL else JSONObject()
        .put("since", known).put("new_revisions", since(known).size)
        .put("url", "/v1/changes?since=$known")
        .put("summary", since(known).flatMap { it.items }.take(5).joinToString("; ") { it.what })
}
