package net.finleg.bridget.feedback

import org.json.JSONArray
import org.json.JSONObject

/** Bug report or feature request (section 11). Copied in concept from the Spotka reporter (D14). */
enum class ReportKind(val id: String) {
    BUG("bug"), FEATURE("feature");
    companion object { fun of(id: String?) = values().firstOrNull { it.id == id } }
}

/** Report statuses (FBK-09, FBK-11a). */
enum class ReportStatus(val id: String) {
    NEW("new"), SEEN("seen"), IN_PROGRESS("in_progress"), FIXED("fixed"), WONT_FIX("wont_fix"),
    DUPLICATE("duplicate"), CONFIRMED("confirmed"), REOPENED("reopened");
    companion object { fun of(id: String?) = values().firstOrNull { it.id == id } }
}

/** Who moves a report: Bridgit itself (first developer read), a developer agent, or the owner on the phone. */
enum class Actor { AUTO, DEVELOPER, OWNER }

/**
 * FBK-11a status machine. Pure, unit-tested row by row.
 * `reopened` behaves as `new`.
 */
object StatusMachine {
    private fun asNew(s: ReportStatus) = if (s == ReportStatus.REOPENED) ReportStatus.NEW else s

    fun allowed(from: ReportStatus, to: ReportStatus, actor: Actor): Boolean {
        val f = asNew(from)
        return when (actor) {
            Actor.AUTO -> f == ReportStatus.NEW && to == ReportStatus.SEEN
            Actor.DEVELOPER -> when (f) {
                ReportStatus.NEW, ReportStatus.SEEN -> to in setOf(ReportStatus.IN_PROGRESS, ReportStatus.DUPLICATE, ReportStatus.WONT_FIX)
                ReportStatus.IN_PROGRESS -> to in setOf(ReportStatus.FIXED, ReportStatus.WONT_FIX)
                else -> false
            }
            Actor.OWNER -> f in setOf(ReportStatus.FIXED, ReportStatus.WONT_FIX, ReportStatus.DUPLICATE) &&
                to in setOf(ReportStatus.CONFIRMED, ReportStatus.REOPENED)
        }
    }

    /** Statuses a developer agent may set from [from], for error messages and the guide. */
    fun nextForDeveloper(from: ReportStatus) = ReportStatus.values().filter { allowed(from, it, Actor.DEVELOPER) }

    /** the owner is told (FBK-11) when a report becomes one of these. */
    fun notifiesOwner(to: ReportStatus) = to == ReportStatus.FIXED || to == ReportStatus.WONT_FIX || to == ReportStatus.DUPLICATE

    /** Labels for My reports (ui-spec 5.12). */
    fun label(s: ReportStatus) = when (s) {
        ReportStatus.NEW -> "New"; ReportStatus.SEEN -> "Seen by agent"; ReportStatus.IN_PROGRESS -> "In progress"
        ReportStatus.FIXED -> "Fixed — check it"; ReportStatus.WONT_FIX -> "Won't fix"; ReportStatus.DUPLICATE -> "Duplicate"
        ReportStatus.CONFIRMED -> "Closed"; ReportStatus.REOPENED -> "Reopened"
    }
}

data class HistoryItem(val at: Long, val status: ReportStatus, val by: String, val note: String?) {
    fun toJson(iso: (Long) -> String) = JSONObject().put("at", iso(at)).put("status", status.id).put("by", by)
        .put("note", note ?: JSONObject.NULL)
}

/** One report. [images] are file names under filesDir/feedback. [log] holds already-redacted LOG-01 entries. */
data class Report(
    val id: Long,
    val kind: ReportKind,
    val description: String,
    val noAutofix: Boolean,
    val status: ReportStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val captureSource: String,        // "form" | "agent"
    val agentId: String?,             // filed by (agent reports)
    val agent: String?,
    val context: JSONObject,          // FBK-06
    val log: JSONArray,
    val images: List<String>,
    val lastBy: String?,
    val history: List<HistoryItem> = emptyList(),
) {
    val number get() = "#" + id.toString().padStart(4, '0')
    val firstLine get() = description.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(120) ?: ""

    fun summaryJson(iso: (Long) -> String): JSONObject = JSONObject()
        .put("id", id).put("number", number).put("kind", kind.id).put("status", status.id)
        .put("first_line", firstLine).put("created_at", iso(createdAt)).put("updated_at", iso(updatedAt))
        .put("no_autofix", noAutofix).put("capture_source", captureSource)
        .put("filed_by", agent ?: "owner").put("image_count", images.size).put("last_updated_by", lastBy ?: JSONObject.NULL)
}

/** Limits from FBK-13 and FBK-04/07. */
object FeedbackLimits {
    const val FORM_PER_HOUR_PER_KIND = 10
    const val API_PER_HOUR_PER_AGENT = 5
    const val MAX_IMAGES = 5
    const val MAX_DESCRIPTION = 10_000
    const val IMAGE_EDGE = 1600
    const val IMAGE_MAX_BYTES = 1_000_000
    const val LOG_ENTRIES = 50
    const val CONTEXT_MAX = 500
}
