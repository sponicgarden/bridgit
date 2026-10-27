package net.finleg.bridget.api

import org.json.JSONObject

/** A structured API error (API-05). [code] must be in [ErrorCatalog]. */
class ApiError(val http: Int, val code: String, message: String, val extra: Map<String, Any?> = emptyMap()) :
    Exception(message) {
    fun toJson(requestId: String): JSONObject {
        val o = JSONObject().put("error", code).put("message", message).put("request_id", requestId)
        extra.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) }
        return o
    }
}

/**
 * Appendix C, as code. The guide and openapi.json are generated from this list,
 * and a unit test checks every code here is in the spec table and vice versa.
 * [built] = Bridget can return it in this release.
 */
object ErrorCatalog {
    data class Entry(val http: Int, val code: String, val meaning: String, val agentShould: String, val built: Boolean = true)

    val all = listOf(
        Entry(400, "bad_request", "Malformed HTTP or JSON", "Fix the request"),
        Entry(401, "unauthorized", "Key missing or wrong; `reason` = `key_rotated` or `agent_removed` when Bridget knows", "Stop; tell Rahul the key needs replacing"),
        Entry(403, "forbidden_source", "Caller is not on the tailnet", "Check Tailscale"),
        Entry(403, "capability_disabled", "Master switch off", "Tell Rahul which switch (`setting` field)"),
        Entry(403, "permission_denied", "Android permission not granted (`permission` field)", "Tell Rahul; the Access screen fixes it"),
        Entry(403, "access_needed", "Android special access off (`setting` field)", "Tell Rahul; Bridget also notifies him", built = false),
        Entry(403, "pairing_closed", "No pairing window open", "Ask Rahul to open one"),
        Entry(403, "screen_denied", "Rahul denied screen access", "Don't ask again soon", built = false),
        Entry(403, "agent_not_allowed", "(Reserved for section 15)", "—", built = false),
        Entry(404, "not_found", "Unknown endpoint or id", "Fix the request"),
        Entry(404, "photo_not_found", "Gallery item gone or outside access", "Refresh the list"),
        Entry(409, "sensor_busy", "Sensor in use or capture running", "Retry once after a few seconds"),
        Entry(409, "device_locked", "Needs the phone unlocked", "Ask Rahul to unlock"),
        Entry(413, "payload_too_large", "Over 12 MB base64 (40 MB raw)", "Ask for a smaller size (`photo_id` given)"),
        Entry(422, "invalid_parameter", "Bad value", "Fix; never retry unchanged"),
        Entry(422, "not_supported", "Android doesn't allow this (for example Wi-Fi toggle)", "Don't retry", built = false),
        Entry(429, "rate_limited", "Over a limit (`retry_after_s`)", "Wait"),
        Entry(429, "too_many_open", "Too many open questions/notices", "Wait for answers or cancel some", built = false),
        Entry(403, "notifications_required", "Bridget's notifications are off, so captures are refused (PRD-05)", "Tell Rahul to turn notifications on"),
        Entry(403, "forbidden", "Not allowed for this agent (`reason`, for example `developer_only`)", "Don't retry"),
        Entry(409, "in_progress", "Same `Idempotency-Key` still running", "Wait, then retry with the same key"),
        Entry(409, "idempotency_expired", "Same `Idempotency-Key` already done, but its media result is no longer kept (SEC-13)", "Don't repeat; use a new key only if you really want a new capture"),
        Entry(429, "quota_exceeded", "Daily data, file storage or relay job quota used up (`limit`)", "Wait until tomorrow", built = false),
        Entry(429, "too_many_waits", "Too many open `wait` requests (SEC-08a)", "Close some waits"),
        Entry(500, "internal_error", "Bridget bug", "Report via `POST /v1/feedback`"),
        Entry(503, "service_start_blocked", "Android hasn't given the service this sensor", "Ask Rahul to open Bridget once"),
        Entry(504, "capture_timeout", "Sensor didn't answer in time", "Retry once"),
        Entry(504, "phone_unreachable", "(Relay) phone didn't pick up the job", "Retry later", built = false),
    )

    private val byCode = all.associateBy { it.code }

    fun http(code: String): Int = byCode[code]?.http ?: error("Unknown error code $code")

    /** Builds an error whose HTTP status always matches the catalog. */
    fun error(code: String, message: String, extra: Map<String, Any?> = emptyMap()) = ApiError(http(code), code, message, extra)
}

/** API-12: "accepted, not done" (202). No P1a endpoint produces it yet; relay jobs and screen-share consent will. */
object Pending {
    fun body(code: String, id: String, pollUrl: String, requestId: String, retryAfterS: Int = 1): JSONObject {
        require(code == "consent_pending" || code == "job_queued")
        return JSONObject().put("pending", true).put("code", code).put("id", id).put("poll_url", pollUrl)
            .put("retry_after_s", retryAfterS).put("request_id", requestId)
    }
}
