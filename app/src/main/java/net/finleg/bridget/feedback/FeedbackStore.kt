package net.finleg.bridget.feedback

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Thrown when a report can't be filed or moved; the API maps [code] to its error. */
class FeedbackError(val code: String, message: String) : Exception(message)

/**
 * Reports live on the phone first (section 11): SQLite `feedback.db` plus JPEGs under
 * filesDir/feedback. Separate from log.db so neither schema blocks the other.
 */
class FeedbackStore(private val context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val db = Helper(context).writableDatabase
    private val dir = File(context.filesDir, "feedback").apply { mkdirs() }
    private val _changed = MutableStateFlow(0L)
    private val listeners = mutableListOf<(Report) -> Unit>()

    /** Bumps on every new report or status change; long-polls and screens watch it. */
    val changed: StateFlow<Long> get() = _changed

    /** Called after a status change (for the owner notification). */
    fun onStatusChange(l: (Report) -> Unit) = synchronized(listeners) { listeners += l }

    /**
     * Files a report. [images] are already downscaled JPEGs (FBK-07). [captureSource] is
     * "form" (Rahul) or "agent". Rate limits per FBK-13 are checked here.
     */
    @Synchronized
    fun create(kind: ReportKind, description: String, noAutofix: Boolean, images: List<ByteArray>,
               log: JSONArray, ctx: JSONObject, captureSource: String, agentId: String? = null, agent: String? = null): Report {
        val text = description.trim()
        if (text.isEmpty()) throw FeedbackError("invalid_parameter", "Please describe the problem before sending.")
        if (text.length > FeedbackLimits.MAX_DESCRIPTION) throw FeedbackError("invalid_parameter", "The description is limited to 10,000 characters.")
        if (images.size > FeedbackLimits.MAX_IMAGES) throw FeedbackError("invalid_parameter", "Up to 5 screenshots per report.")
        val hourAgo = clock() - 3_600_000L
        if (captureSource == "form") {
            if (count("capture_source = 'form' AND kind = ? AND created_at > ?", kind.id, hourAgo.toString()) >= FeedbackLimits.FORM_PER_HOUR_PER_KIND)
                throw FeedbackError("rate_limited", "That's 10 reports this hour. Try again later.")
        } else if (count("agent_id = ? AND created_at > ?", agentId ?: "", hourAgo.toString()) >= FeedbackLimits.API_PER_HOUR_PER_AGENT) {
            throw FeedbackError("rate_limited", "At most 5 reports per hour per agent.")
        }
        val now = clock()
        val held = noAutofix || kind == ReportKind.FEATURE // features are always held for review (FBK-04)
        val id = db.insertOrThrow("reports", null, ContentValues().apply {
            put("kind", kind.id); put("description", text); put("no_autofix", if (held) 1 else 0)
            put("status", ReportStatus.NEW.id); put("created_at", now); put("updated_at", now)
            put("capture_source", captureSource); put("agent_id", agentId); put("agent", agent)
            put("context_json", ctx.toString()); put("log_json", log.toString()); put("images_json", "[]")
            put("last_by", agent ?: "Rahul")
        })
        val names = images.mapIndexed { i, bytes -> "$id-${i + 1}.jpg".also { File(dir, it).writeBytes(bytes) } }
        db.update("reports", ContentValues().apply { put("images_json", JSONArray(names).toString()) }, "id = ?", arrayOf(id.toString()))
        addHistory(id, ReportStatus.NEW, agent ?: "Rahul", null, now)
        _changed.value = _changed.value + 1
        return get(id)!!
    }

    fun get(id: Long): Report? = db.query("reports", null, "id = ?", arrayOf(id.toString()), null, null, null)
        .use { c -> if (c.moveToFirst()) row(c, history(id)) else null }

    /** Newest first. Filters are optional; [sinceTs] means updated after. */
    fun list(status: ReportStatus? = null, kind: ReportKind? = null, sinceTs: Long? = null, limit: Int = 100): List<Report> {
        val where = mutableListOf<String>(); val args = mutableListOf<String>()
        status?.let { where += "status = ?"; args += it.id }
        kind?.let { where += "kind = ?"; args += it.id }
        sinceTs?.let { where += "updated_at > ?"; args += it.toString() }
        return db.query("reports", null, if (where.isEmpty()) null else where.joinToString(" AND "), args.toTypedArray(),
            null, null, "id DESC", limit.toString()).use { c -> buildList { while (c.moveToNext()) add(row(c, emptyList())) } }
    }

    /** Moves a report if FBK-11a allows it; returns the updated report. */
    @Synchronized
    fun move(id: Long, to: ReportStatus, actor: Actor, by: String, note: String?): Report {
        val r = get(id) ?: throw FeedbackError("not_found", "No report $id.")
        if (r.status == to && actor != Actor.AUTO) return r
        if (!StatusMachine.allowed(r.status, to, actor)) {
            val next = if (actor == Actor.DEVELOPER) StatusMachine.nextForDeveloper(r.status).joinToString { it.id } else ""
            throw FeedbackError("invalid_parameter", "A report that is ${r.status.id} can't move to ${to.id}" +
                (if (next.isNotEmpty()) ". Allowed: $next." else "."))
        }
        val now = clock()
        db.update("reports", ContentValues().apply { put("status", to.id); put("updated_at", now); put("last_by", by) },
            "id = ?", arrayOf(id.toString()))
        addHistory(id, to, by, note?.trim()?.take(500)?.ifEmpty { null }, now)
        _changed.value = _changed.value + 1
        val updated = get(id)!!
        synchronized(listeners) { listeners.toList() }.forEach { runCatching { it(updated) } }
        return updated
    }

    fun imageFile(name: String): File? = File(dir, name).takeIf { it.exists() && it.parentFile == dir }

    fun counts(): Map<ReportStatus, Int> = db.rawQuery("SELECT status, COUNT(*) FROM reports GROUP BY status", null).use { c ->
        buildMap { while (c.moveToNext()) ReportStatus.of(c.getString(0))?.let { put(it, c.getInt(1)) } }
    }

    private fun count(where: String, vararg args: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM reports WHERE $where", args).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun addHistory(id: Long, s: ReportStatus, by: String, note: String?, at: Long) {
        db.insert("history", null, ContentValues().apply {
            put("report_id", id); put("at", at); put("status", s.id); put("by", by); put("note", note)
        })
    }

    private fun history(id: Long): List<HistoryItem> =
        db.query("history", null, "report_id = ?", arrayOf(id.toString()), null, null, "id ASC").use { c ->
            buildList {
                while (c.moveToNext()) add(HistoryItem(c.getLong(c.getColumnIndexOrThrow("at")),
                    ReportStatus.of(c.getString(c.getColumnIndexOrThrow("status")))!!,
                    c.getString(c.getColumnIndexOrThrow("by")), c.getString(c.getColumnIndexOrThrow("note"))))
            }
        }

    private fun row(c: Cursor, history: List<HistoryItem>): Report {
        fun s(n: String) = c.getColumnIndexOrThrow(n).let { if (c.isNull(it)) null else c.getString(it) }
        fun l(n: String) = c.getLong(c.getColumnIndexOrThrow(n))
        val imgs = JSONArray(s("images_json") ?: "[]")
        return Report(l("id"), ReportKind.of(s("kind"))!!, s("description") ?: "", l("no_autofix") == 1L,
            ReportStatus.of(s("status"))!!, l("created_at"), l("updated_at"), s("capture_source") ?: "form",
            s("agent_id"), s("agent"), JSONObject(s("context_json") ?: "{}"), JSONArray(s("log_json") ?: "[]"),
            (0 until imgs.length()).map { imgs.getString(it) }, s("last_by"), history)
    }

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "feedback.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE reports (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, description TEXT NOT NULL,
                no_autofix INTEGER NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                capture_source TEXT NOT NULL, agent_id TEXT, agent TEXT, context_json TEXT, log_json TEXT, images_json TEXT, last_by TEXT)""")
            db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, report_id INTEGER NOT NULL, at INTEGER NOT NULL, status TEXT NOT NULL, by TEXT NOT NULL, note TEXT)")
            db.execSQL("CREATE INDEX reports_status ON reports(status, id)")
            db.execSQL("CREATE INDEX history_report ON history(report_id, id)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }
}
