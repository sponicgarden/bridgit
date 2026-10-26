package net.finleg.bridget

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * Append-only JSON-lines log of every request and settings change. Lives only in
 * app-private storage. Keeps the newest [MAX_ENTRIES] entries.
 */
class AuditLog(context: Context) {
    private val file = File(context.filesDir, "audit.jsonl")
    private val lock = Any()
    private var count = -1

    fun append(entry: JSONObject) = synchronized(lock) {
        if (!entry.has("ts")) entry.put("ts", nowIso())
        file.appendText(entry.toString() + "\n")
        if (count < 0) count = readLines().size else count++
        if (count > MAX_ENTRIES + 100) trim()
    }

    fun event(kind: String, detail: String) = append(
        JSONObject().put("endpoint", "app:$kind").put("result", "ok").put("detail", detail)
    )

    /** Newest first. */
    fun recent(limit: Int): List<JSONObject> = synchronized(lock) {
        readLines().takeLast(limit).reversed().mapNotNull {
            runCatching { JSONObject(it) }.getOrNull()
        }
    }

    fun exportText(): String = synchronized(lock) { if (file.exists()) file.readText() else "" }

    /** Clearing is itself recorded as the first entry of the new log. */
    fun clear() = synchronized(lock) {
        val old = readLines().size
        file.writeText("")
        count = 0
        append(JSONObject().put("endpoint", "app:log_cleared").put("result", "ok")
            .put("detail", "cleared $old entries"))
    }

    private fun trim() {
        val lines = readLines().takeLast(MAX_ENTRIES)
        file.writeText(lines.joinToString("\n", postfix = "\n"))
        count = lines.size
    }

    private fun readLines(): List<String> =
        if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()

    companion object {
        const val MAX_ENTRIES = 1000
        fun nowIso(): String = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()
    }
}
