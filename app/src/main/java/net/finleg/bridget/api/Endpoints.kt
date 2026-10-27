package net.finleg.bridget.api

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

    val HEALTH = EndpointSpec("GET", "/health", "status", "Is Bridget up, and which agent you are",
        example = "GET /v1/health", returns = "status, app, app_version, agent, listener_uptime_seconds, battery_level, charging, bind_address, port")
    val CAPABILITIES = EndpointSpec("GET", "/capabilities", "status", "What's switched on and ready, and why not",
        example = "GET /v1/capabilities", returns = "per capability: enabled, ready, blocked_reason, details")
    val GUIDE = EndpointSpec("GET", "/guide", "status", "This guide, generated live (Markdown)",
        example = "GET /v1/guide", returns = "text/markdown")
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
        body = listOf(Param("duration_seconds", "integer", "Whole seconds, 1-120", required = true)),
        example = "POST /v1/mic/record {\"duration_seconds\":5}",
        returns = "recorded_at, duration_seconds, format (m4a), sample_rate_hz, size_bytes, data_base64",
        errors = listOf("capability_disabled", "permission_denied", "notifications_required", "sensor_busy", "capture_timeout", "service_start_blocked"))
    val GALLERY_RECENT = EndpointSpec("GET", "/gallery/recent", "gallery", "Newest gallery images (metadata only)",
        query = listOf(Param("limit", "integer", "1-100, default 20")),
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
        Param("agent", "string", "Agent name or id; `me` for you; `bridget` for Bridget's own entries"),
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
            Param("note", "string", "Short note shown to Rahul, e.g. \"Fix in PR #412\" (500 chars)")),
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

    val all = listOf(HEALTH, CAPABILITIES, GUIDE, OPENAPI, CHANGES, HELPER, PAIR, SNAPSHOT, MIC, GALLERY_RECENT, GALLERY_PHOTO,
        IMAGE_LAST, LOG, LOG_ENTRY, LOG_STATS, LOG_EXPORT, LOG_NOTE, AUDIT, FEEDBACK_LIST, FEEDBACK_GET, FEEDBACK_STATUS, FEEDBACK_POST)
}
