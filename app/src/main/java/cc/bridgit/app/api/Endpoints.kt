package cc.bridgit.app.api

/** One query or body parameter, for the guide and openapi.json. */
data class Param(val name: String, val type: String, val description: String, val required: Boolean = false,
                 val enum: List<String>? = null)

/**
 * Describes one endpoint. The router, `/v1/guide` and `/v1/openapi.json` are all
 * generated from these, so they can't drift apart (API-09).
 */
data class EndpointSpec(
    val method: String,
    val path: String,                 // without /v1, e.g. "/camera/snapshot"; {id} for path params
    val capability: String,           // module id
    val summary: String,
    val auth: Boolean = true,
    val capture: Boolean = false,     // counts toward SEC-07 capture limits
    val idempotent: Boolean = false,  // accepts Idempotency-Key (API-13)
    val media: Boolean = false,       // returns an image or audio; accepts raw=1 where noted
    val query: List<Param> = emptyList(),
    val body: List<Param> = emptyList(),
    val example: String,
    val returns: String,
    val errors: List<String> = emptyList(),
    /** AGT-15: only agents with the Developer switch; checked before anything else, including replays. */
    val developerOnly: Boolean = false,
    /** Body fields never copied into the log, only their length (report text, notes, images). */
    val privateFields: Set<String> = emptySet(),
    /** Largest request body (API-11): 2 MB unless the endpoint takes media. Non-JSON bodies on such endpoints are streamed to a temp file (Req.takeRawFile); JSON stays at 2 MB. */
    val maxBody: Int = 2 * 1024 * 1024,
) {
    val fullPath get() = "/v1$path"
}

object Endpoints {
    private val sizing = listOf(
        Param("size", "string", "Preset: agent (1568 px, q85), thumb (512, q75), large (2560, q90), original", enum = listOf("agent", "thumb", "large", "original")),
        Param("max_edge_px", "integer", "Long edge limit, 64-20000; overrides the preset"),
        Param("max_width_px", "integer", "Width limit, 64-20000"),
        Param("quality", "integer", "JPEG quality 40-100"),
        Param("raw", "integer", "1 = return the image bytes, details in X-Bridget-* headers"),
    )

    val HEALTH = EndpointSpec("GET", "/health", "status", "Is Bridgit up, and which agent you are",
        example = "GET /v1/health", returns = "status, app, app_version, agent, listener_uptime_seconds, battery_level, charging, bind_address, port")
    val ME = EndpointSpec("POST", "/me", "status", "Tell the owner which model you are and what runs you; shown on your agent card (AGT-16)",
        body = listOf(Param("model", "string", "Your model, as exact as you know it, e.g. claude-opus-5-5 or gpt-6.1 (1-60 chars)", required = true),
            Param("harness", "string", "The program running you, e.g. Claude Code, Codex CLI, a Python script (1-40 chars)")),
        example = "POST /v1/me {\"model\":\"claude-opus-5-5\",\"harness\":\"Claude Code\"}", returns = "agent, model, harness",
        errors = listOf("invalid_parameter"))
    val CAPABILITIES = EndpointSpec("GET", "/capabilities", "status", "What's switched on and ready, and why not",
        example = "GET /v1/capabilities", returns = "per capability: enabled, ready, blocked_reason, details; callback {set, host}")
    val GUIDE = EndpointSpec("GET", "/guide", "status", "This guide, generated live (Markdown)",
        example = "GET /v1/guide", returns = "text/markdown")
    val DEMO = EndpointSpec("GET", "/demo", "status", "A script for showing the owner what Bridgit can do: offer it, ask a few questions, run the demos that fit (Markdown)",
        example = "GET /v1/demo", returns = "text/markdown")
    val OPENAPI = EndpointSpec("GET", "/openapi.json", "status", "OpenAPI 3.1 description of every endpoint",
        example = "GET /v1/openapi.json", returns = "application/json")
    val CHANGES = EndpointSpec("GET", "/changes", "status", "What's new in the API since a revision you know (API-14)",
        query = listOf(Param("since", "integer", "The API revision you last read about; 0 or absent = everything")),
        example = "GET /v1/changes?since=2", returns = "api_revision, app_version, changes[revision, app_version, date, items[what, endpoint]], links")
    val HELPER = EndpointSpec("GET", "/tools/bridget", "status", "The `bridget` command-line helper (Python, no secrets inside)",
        auth = false, example = "GET /v1/tools/bridget", returns = "text/x-python")
    val PAIR = EndpointSpec("POST", "/pair", "status", "Fetch your key with a 6-digit code shown on the phone (alternative to the setup prompt)",
        auth = false, body = listOf(Param("code", "string", "6-digit code from the phone", required = true)),
        example = "POST /v1/pair {\"code\":\"482913\"}", returns = "key, agent, base_url (once per pairing window)",
        errors = listOf("pairing_closed", "unauthorized", "rate_limited"))

    val SNAPSHOT = EndpointSpec("POST", "/camera/snapshot", "camera", "Take a photo now",
        capture = true, idempotent = true, media = true,
        body = listOf(
            Param("camera", "string", "rear (default) or front", enum = listOf("rear", "front")),
            Param("flash", "boolean", "Fire the flash (default false)"),
            Param("zoom", "number", "Zoom ratio, e.g. 0.5 (ultra-wide), 1, 2, 5 (telephoto); the range is in /v1/capabilities after the first photo"),
            Param("max_width_px", "integer", "Width limit, 64-20000, or null for full size (default 2048 unless size is given)"),
        ) + sizing.filter { it.name != "max_width_px" },
        example = "POST /v1/camera/snapshot {\"camera\":\"rear\",\"zoom\":2}",
        returns = "captured_at, width_px, height_px, format, size_bytes, data_base64 (or JPEG bytes with raw=1)",
        errors = listOf("capability_disabled", "permission_denied", "notifications_required", "device_locked", "sensor_busy", "capture_timeout", "service_start_blocked", "payload_too_large"))
    val MIC = EndpointSpec("POST", "/mic/record", "mic", "Record an audio clip",
        capture = true, idempotent = true, media = true,
        body = listOf(Param("transcribe", "boolean", "Use only Android on-device recognition; no cloud fallback"), Param("return_audio", "boolean", "true by default; false requires transcribe=true"), Param("language", "string", "Optional offline language, e.g. en-US"), Param("duration_seconds", "integer", "Whole seconds, 1-120", required = true)),
        example = "POST /v1/mic/record {\"duration_seconds\":5}",
        returns = "recorded_at, duration_seconds, format (m4a), sample_rate_hz, size_bytes, data_base64",
        errors = listOf("capability_disabled", "permission_denied", "notifications_required", "sensor_busy", "capture_timeout", "service_start_blocked"))
    val GALLERY_RECENT = EndpointSpec("GET", "/gallery/recent", "gallery", "Newest gallery images (metadata only)",
        query = listOf(Param("type", "string", "any, photo or screenshot"), Param("album", "string", "Exact album name"), Param("since", "string", "ISO-8601 UTC added-at lower bound"), Param("until", "string", "ISO-8601 UTC upper bound"), Param("limit", "integer", "1-100, default 20")),
        example = "GET /v1/gallery/recent?limit=10", returns = "access_mode, photos[id, taken_at, width_px, height_px, size_bytes, mime, album]",
        errors = listOf("capability_disabled", "permission_denied"))
    val GALLERY_PHOTO = EndpointSpec("GET", "/gallery/photo/{id}", "gallery", "One gallery image, sized for you",
        media = true, query = sizing,
        example = "GET /v1/gallery/photo/87567?size=agent", returns = "taken_at, width_px, height_px, format, size_bytes, data_base64",
        errors = listOf("capability_disabled", "permission_denied", "photo_not_found", "payload_too_large"))
    val IMAGE_LAST = EndpointSpec("GET", "/image/last", "gallery", "The newest image of a type; with since and wait, waits for the next one",
        media = true,
        query = listOf(
            Param("type", "string", "any (default), screenshot or photo", enum = listOf("any", "screenshot", "photo")),
            Param("since", "string", "ISO time or gallery id: only an image added after it"),
            Param("wait", "integer", "With since: hold up to this many seconds (max 55) for a new image"),
        ) + sizing,
        example = "GET /v1/image/last?type=screenshot", returns = "id, type, taken_at, added_at, album, width_px, height_px, size_bytes, data_base64",
        errors = listOf("capability_disabled", "permission_denied", "photo_not_found", "too_many_waits"))

    private val logFilters = listOf(
        Param("channel", "string", "Comma list of audit, diagnostic, agent"),
        Param("agent", "string", "Agent name or id; `me` for you; `bridget` for Bridgit's own entries"),
        Param("kind", "string", "request, setting, agent, pairing, service, note, …"),
        Param("endpoint", "string", "Endpoint prefix, e.g. /v1/camera"),
        Param("result", "string", "ok, error, or an error code"),
        Param("level", "string", "Minimum level: debug, info, warn, error"),
        Param("since", "string", "Log id or ISO time (exclusive id)"),
        Param("until", "string", "Log id or ISO time"),
        Param("q", "string", "Text in message or endpoint"),
    )
    val LOG = EndpointSpec("GET", "/log", "log", "Read the log; with order=asc&since=<id>&wait=55 it follows new entries",
        query = logFilters + listOf(
            Param("limit", "integer", "1-500, default 100"),
            Param("order", "string", "desc (default) or asc", enum = listOf("desc", "asc")),
            Param("cursor", "string", "next_cursor from the previous page"),
            Param("wait", "integer", "Tail mode: hold up to this many seconds (max 55) for a new entry"),
        ),
        example = "GET /v1/log?agent=me&limit=20", returns = "entries[], next_cursor, latest_id",
        errors = listOf("invalid_parameter", "too_many_waits"))
    val LOG_ENTRY = EndpointSpec("GET", "/log/{id}", "log", "One log entry",
        example = "GET /v1/log/48213", returns = "the entry", errors = listOf("not_found"))
    val LOG_STATS = EndpointSpec("GET", "/log/stats", "log", "Counts, errors and p50/p95 time per agent and endpoint",
        query = listOf(Param("since", "string", "Log id or ISO time; default the last 24 h")),
        example = "GET /v1/log/stats?since=2026-10-27T00:00:00%2B01:00", returns = "by_agent[], by_endpoint[]")
    val LOG_EXPORT = EndpointSpec("GET", "/log/export", "log", "Every matching entry as JSONL or CSV",
        query = listOf(Param("format", "string", "jsonl (default) or csv", enum = listOf("jsonl", "csv"))) + logFilters,
        example = "GET /v1/log/export?format=csv&agent=me", returns = "application/x-ndjson or text/csv")
    val LOG_NOTE = EndpointSpec("POST", "/log", "log", "Write a note to the agent channel",
        idempotent = true,
        body = listOf(
            Param("message", "string", "Up to 1 KB", required = true),
            Param("level", "string", "debug, info (default), warn, error"),
            Param("data", "object", "Up to 4 KB of JSON"),
        ),
        example = "POST /v1/log {\"message\":\"checked fridge photo, milk low\"}", returns = "id",
        errors = listOf("invalid_parameter", "rate_limited"))
    val AUDIT = EndpointSpec("GET", "/audit", "log", "Deprecated alias of /v1/log?channel=audit (removed in v3)",
        query = listOf(Param("limit", "integer", "1-500, default 50")),
        example = "GET /v1/audit?limit=50", returns = "entries[] (same shape as /v1/log)")

    val FEEDBACK_LIST = EndpointSpec("GET", "/feedback", "feedback", "Bug reports and feature requests filed on the phone (developer agents only)",
        developerOnly = true,
        query = listOf(
            Param("status", "string", "new, seen, in_progress, fixed, wont_fix, duplicate, confirmed, reopened"),
            Param("kind", "string", "bug or feature", enum = listOf("bug", "feature")),
            Param("since", "string", "ISO time: only reports updated after it"),
            Param("wait", "integer", "With since: hold up to this many seconds (max 55) for a new or changed report"),
            Param("limit", "integer", "1-100, default 50"),
        ),
        example = "GET /v1/feedback?status=new", returns = "reports[id, number, kind, status, first_line, created_at, updated_at, no_autofix, image_count, filed_by]",
        errors = listOf("forbidden", "invalid_parameter", "too_many_waits"))
    val FEEDBACK_GET = EndpointSpec("GET", "/feedback/{id}", "feedback", "One report with its text, images, attached log and history (developer agents only; marks a new report seen)",
        developerOnly = true,
        media = true, query = sizing.filter { it.name != "raw" },
        example = "GET /v1/feedback/7", returns = "report: description, no_autofix, context, images[data_base64], log[], history[]",
        errors = listOf("forbidden", "not_found"))
    val FEEDBACK_STATUS = EndpointSpec("POST", "/feedback/{id}/status", "feedback", "Move a report: in_progress, fixed, wont_fix or duplicate (developer agents only; FBK-11a)",
        idempotent = true, developerOnly = true, privateFields = setOf("note"),
        body = listOf(Param("status", "string", "in_progress, fixed, wont_fix or duplicate", required = true),
            Param("note", "string", "Short note shown to the owner, e.g. \"Fix in PR #412\" (500 chars)")),
        example = "POST /v1/feedback/7/status {\"status\":\"in_progress\",\"note\":\"Looking at it\"}", returns = "the report summary",
        errors = listOf("forbidden", "not_found", "invalid_parameter"))
    val FEEDBACK_POST = EndpointSpec("POST", "/feedback", "feedback", "File a bug report or feature request yourself (any agent; images from developer agents only)",
        idempotent = true, privateFields = setOf("description", "images"),
        body = listOf(Param("kind", "string", "bug or feature", required = true, enum = listOf("bug", "feature")),
            Param("description", "string", "What happened, up to 10,000 characters", required = true),
            Param("no_autofix", "boolean", "true = leave it for a person"),
            Param("images", "array", "Developer agents only: up to 5 {\"data_base64\"} images")),
        example = "POST /v1/feedback {\"kind\":\"bug\",\"description\":\"image/last returned 500 twice\"}", returns = "id, number, status",
        errors = listOf("forbidden", "invalid_parameter", "rate_limited"))

    val INTERACTION_CREATE = EndpointSpec("POST", "/interactions", "interactions", "Send the owner a notice, a question (buttons, text reply, choice, links), an HTML card or a live text panel",
        idempotent = true, privateFields = setOf("text", "image", "html"),
        body = listOf(
            Param("kind", "string", "notice (one-way), question, html (a card you draw; answer comes from Bridget.respond) or text (a live text panel you stream into with /v1/text/stream)",
                required = true, enum = listOf("notice", "question", "html", "text")),
            Param("title", "string", "Up to 80 characters", required = true),
            Param("text", "string", "Up to 2,000 characters"),
            Param("image", "object", "{\"gallery_id\"} or {\"data_base64\"}, up to 2 MB"),
            Param("html", "string", "kind=html: the card's HTML, up to 256 KB. Runs offline: no network, only data:image/* images; call Bridget.respond(json) once per question view (up to 16 KB) to answer. After persistence, bridgit:answered lets the card show its confirmation; the trusted Back to questions control reloads the question for resubmission"),
            Param("controls", "array", "Question: 1-6 of {type: button|text|choice|link, id, label, style?, placeholder?, options?, url?, package?}. Notice: links only"),
            Param("priority", "string", "normal or urgent", enum = listOf("normal", "urgent")),
            Param("speak", "boolean", "Read it aloud on the phone"),
            Param("expires_in_seconds", "integer", "60 to 604800, default 86400. After it, waits return expired and you get interaction.expired, but a question stays on the owner's phone and they can still answer it late (interaction.answered with late=true)"),
        ),
        example = "POST /v1/interactions {\"kind\":\"question\",\"title\":\"Pay this invoice?\",\"controls\":[{\"type\":\"button\",\"id\":\"yes\",\"label\":\"Pay\",\"style\":\"primary\"},{\"type\":\"button\",\"id\":\"no\",\"label\":\"Not now\"}]}",
        returns = "interaction_id, status, expires_at",
        errors = listOf("capability_disabled", "notifications_required", "invalid_parameter", "payload_too_large", "too_many_open", "photo_not_found"))
    val TEXT_STREAM = EndpointSpec("POST", "/text/stream", "interactions", "Add text to your live text panel (an interaction of kind text); it scrolls on the phone as it arrives",
        privateFields = setOf("append"),
        body = listOf(Param("interaction_id", "string", "The panel's interaction_id", required = true),
            Param("append", "string", "Text to add, up to 16 KB per call (the panel keeps the last 200 KB)"),
            Param("done", "boolean", "true: the stream is finished; the panel stays until the owner closes it")),
        example = "POST /v1/text/stream {\"interaction_id\":\"42\",\"append\":\"Claude: running the tests now...\\n\"}",
        returns = "interaction_id, length, status", errors = listOf("not_found", "invalid_parameter", "conflict", "rate_limited"))
    val CALLBACK_TEST = EndpointSpec("POST", "/callback/test", "interactions", "Send a signed test event to the callback URL the owner set for you (EVT-05/06)",
        example = "POST /v1/callback/test", returns = "delivered, http_status, host", errors = listOf("not_found", "callback_failed"))
    val MCP = EndpointSpec("POST", "/mcp", "status", "Model Context Protocol (Streamable HTTP, JSON replies): every endpoint you can use is an MCP tool",
        maxBody = 2 * 1024 * 1024, privateFields = setOf("params"),
        body = listOf(Param("jsonrpc", "string", "\"2.0\"", required = true), Param("method", "string", "initialize, tools/list, tools/call, ping", required = true),
            Param("params", "object", "For tools/call: {name, arguments}"), Param("id", "integer", "Request id")),
        example = "POST /v1/mcp {\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
        returns = "JSON-RPC result", errors = listOf("invalid_parameter"))
    val INTERACTION_LIST = EndpointSpec("GET", "/interactions", "interactions", "Your interactions, newest first",
        query = listOf(Param("status", "string", "pending, seen, answered, dismissed, expired or cancelled"), Param("limit", "integer", "1-100, default 50")),
        example = "GET /v1/interactions?status=answered", returns = "interactions[]")
    val INTERACTION_GET = EndpointSpec("GET", "/interactions/{id}", "interactions", "One of your interactions; with wait, holds until the owner answers or closes it",
        query = listOf(Param("wait", "integer", "Hold up to this many seconds (max 55) for the answer")),
        example = "GET /v1/interactions/12?wait=55", returns = "kind, status, answer{button, values}, answered_at, closed_at",
        errors = listOf("not_found", "too_many_waits"))
    val INTERACTION_CANCEL = EndpointSpec("DELETE", "/interactions/{id}", "interactions", "Cancel one of your interactions",
        example = "DELETE /v1/interactions/12", returns = "the interaction", errors = listOf("not_found"))
    val EVENTS = EndpointSpec("GET", "/events", "interactions", "Your events, oldest first: answers, closes, expiries, switch changes. Repeated phone answers emit interaction.answered with resubmission=true and previous_answer; agents decide whether to accept the change. An answer given after the question expired comes as interaction.answered with late=true",
        query = listOf(Param("since", "string", "next_cursor from the last call; omit for the last 50"),
            Param("wait", "integer", "With since: hold up to 55 s for a new event"), Param("types", "string", "Comma list, e.g. interaction.answered")),
        example = "GET /v1/events?since=0&wait=55", returns = "events[id, type, at, data], next_cursor",
        errors = listOf("invalid_parameter", "too_many_waits"))

    val LOCATION = EndpointSpec("GET", "/location", "location", "Where the phone is now, with altitude, speed, heading and the compass",
        capture = true,
        query = listOf(Param("fresh", "boolean", "true (default): a new fix, up to 15 s; false: the last known one at once"),
            Param("accuracy", "string", "high (GPS, default) or balanced", enum = listOf("high", "balanced")),
            Param("compass", "boolean", "Include the compass (default true)")),
        example = "GET /v1/location", returns = "latitude, longitude, altitude_m, accuracy_m, vertical_accuracy_m, speed_mps, bearing_deg, fix_time, age_s, precision, compass{azimuth_deg, direction, pitch_deg, roll_deg}",
        errors = listOf("capability_disabled", "permission_denied", "notifications_required", "capture_timeout", "service_start_blocked"))
    val SPEAKER_PLAY = EndpointSpec("POST", "/speaker/play", "speaker", "Play audio on the phone's speaker: send audio/* bytes as the body, or {\"url\"} (public https)",
        idempotent = true, maxBody = 25 * 1024 * 1024, privateFields = setOf("text"),
        query = listOf(Param("replace", "string", "after_current (default: replace what's queued from you), now, or queue", enum = listOf("after_current", "now", "queue")),
            Param("tour_id", "string", "Narration for your running City tour"), Param("urgent", "boolean", "Jump the queue (answers to questions)"),
            Param("text", "string", "Caption shown on the phone while it plays")),
        body = listOf(Param("url", "string", "https:// audio file (MP3, M4A, OGG, WAV, FLAC), up to 25 MB")),
        example = "POST /v1/speaker/play (Content-Type: audio/mpeg, body = the MP3)", returns = "play_id, position, status (playing, queued or silenced)",
        errors = listOf("capability_disabled", "notifications_required", "invalid_parameter", "payload_too_large", "rate_limited", "not_found"))
    val SPEAKER_SAY = EndpointSpec("POST", "/speaker/say", "speaker", "Read text aloud on the phone (its own text-to-speech)",
        idempotent = true, privateFields = setOf("text"),
        body = listOf(Param("text", "string", "Up to 4,000 characters", required = true), Param("language", "string", "e.g. en-GB, de-DE"),
            Param("rate", "number", "0.5-2, default 1"), Param("pitch", "number", "0.5-2, default 1"),
            Param("replace", "string", "after_current (default), now, or queue"), Param("tour_id", "string", "Narration for your City tour"),
            Param("urgent", "boolean", "Jump the queue")),
        example = "POST /v1/speaker/say {\"text\":\"On your left is the old town hall, built in 1360.\",\"tour_id\":\"t123\"}",
        returns = "play_id, position, status (playing, queued, or silenced: the phone is on silent, Do Not Disturb or in a call, so it won't be heard)",
        errors = listOf("capability_disabled", "invalid_parameter", "rate_limited", "not_found"))
    val SPEAKER_STATUS = EndpointSpec("GET", "/speaker", "speaker", "What's playing, and your queued sounds",
        example = "GET /v1/speaker", returns = "playing{play_id, agent, text} (yours only, else null), busy (someone else's sound is playing), paused, queued[], quiet")
    val SPEAKER_STOP = EndpointSpec("DELETE", "/speaker", "speaker", "Stop your audio and clear your queue",
        example = "DELETE /v1/speaker", returns = "stopped")
    val TOUR_GET = EndpointSpec("GET", "/tour", "tour", "Your running City tour, if the owner started one with you (D30)",
        example = "GET /v1/tour", returns = "tour{tour_id, kinds, interval_s, started_at, ticks, last_tick_at, distance_m, photos_sent, brief} or null")
    val TOUR_PHOTO = EndpointSpec("GET", "/tour/photo/{n}", "tour", "A photo the owner took with Take photo on your tour (n from tour.photo), sized for you",
        media = true, query = sizing,
        example = "GET /v1/tour/photo/1?size=agent", returns = "taken_at, width_px, height_px, format, size_bytes, data_base64",
        errors = listOf("not_found", "photo_not_found", "payload_too_large"))
    val TOUR_END = EndpointSpec("DELETE", "/tour", "tour", "End your City tour",
        query = listOf(Param("tour_id", "string", "Only end this tour (it must be yours and still running)")),
        example = "DELETE /v1/tour", returns = "ended", errors = listOf("not_found"))

    val SCREEN_CAPTURE = EndpointSpec("POST", "/screen/capture", "screen", "What's on the owner's screen right now (D17, no prompt once they've turned it on)",
        capture = true, idempotent = true, media = true, query = sizing,
        body = listOf(Param("mode", "string", "private (default): kept in memory, nothing saved", enum = listOf("private"))),
        example = "POST /v1/screen/capture?size=agent", returns = "captured_at, width_px, height_px, format, size_bytes, mode, data_base64 (or raw=1 bytes)",
        errors = listOf("capability_disabled", "access_needed", "notifications_required", "device_locked", "rate_limited", "sensor_busy",
            "not_supported", "capture_timeout", "service_start_blocked", "payload_too_large"))

    val LISTEN = EndpointSpec("GET", "/listen", "wakewords", "Wake words: whether the phone is listening, and the wake words the owner gave you (CAP-47)",
        example = "GET /v1/listen", returns = "enabled, state (off|listening|hearing|paused), reason, muted_until, model (ready|not_downloaded|downloading|failed), model_name, max_seconds, wake_words[{phrase, send_clip}]. " +
            "Each hit arrives as a listen.heard event: wake_word, transcript, heard_at, ended_by (pause|max_seconds|no_speech|interrupted), duration_seconds, recogniser, clip_id")
    val LISTEN_CLIP = EndpointSpec("GET", "/listen/clip/{id}", "wakewords", "The audio after a wake word, when the owner set that wake word to send it (kept 10 minutes)",
        media = true, example = "GET /v1/listen/clip/c0123abcd", returns = "heard_at, duration_seconds, format (wav), sample_rate_hz, size_bytes, data_base64 (or raw=1 bytes)",
        errors = listOf("not_found"))

    val SOUNDS = EndpointSpec("GET", "/sounds", "sounds", "Listen for any sounds: whether the phone tells you when the room gets loud (8.23)",
        example = "GET /v1/sounds", returns = "enabled, for_you, state (off|listening|paused), reason, sensitivity (quiet|normal|loud), send_clip, cooldown_seconds, max_seconds. " +
            "Each sound arrives as a sound.heard event: heard_at, duration_seconds, peak_dbfs, mean_dbfs, sensitivity, clip_id")
    val SOUNDS_CLIP = EndpointSpec("GET", "/sounds/clip/{id}", "sounds", "The audio of a sound (up to 10 s, from just before it started), when the owner lets sounds send audio (kept 10 minutes)",
        media = true, example = "GET /v1/sounds/clip/c0123abcd", returns = "heard_at, duration_seconds, format (wav), sample_rate_hz, size_bytes, data_base64 (or raw=1 bytes)",
        errors = listOf("not_found"))

    val all = listOf(HEALTH, CAPABILITIES, GUIDE, DEMO, OPENAPI, CHANGES, HELPER, PAIR, SNAPSHOT, MIC, GALLERY_RECENT, GALLERY_PHOTO,
        IMAGE_LAST, SCREEN_CAPTURE, LOG, LOG_ENTRY, LOG_STATS, LOG_EXPORT, LOG_NOTE, AUDIT, FEEDBACK_LIST, FEEDBACK_GET, FEEDBACK_STATUS, FEEDBACK_POST,
        INTERACTION_CREATE, INTERACTION_LIST, INTERACTION_GET, INTERACTION_CANCEL, EVENTS, TEXT_STREAM, CALLBACK_TEST, MCP,
        LOCATION, SPEAKER_PLAY, SPEAKER_SAY, SPEAKER_STATUS, SPEAKER_STOP, TOUR_GET, TOUR_PHOTO, TOUR_END, ME, LISTEN, LISTEN_CLIP, SOUNDS, SOUNDS_CLIP) + cc.bridgit.app.planned.PlannedEndpoints.all
}
