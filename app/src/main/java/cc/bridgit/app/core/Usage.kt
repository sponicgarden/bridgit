package cc.bridgit.app.core

import android.content.Context
import cc.bridgit.app.BuildConfig
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Feature-use tallies (owner request 31 Oct 2026): how many times each feature or function was used,
 * nothing else. A key names the feature, never what it did: "api:GET /v1/camera/photo:ok" uses the
 * endpoint's path template, so ids, parameters, agent names, addresses and content never appear.
 * About once a day the counts go to bridgit.cc (website/usage.mjs), which adds them to per-day totals.
 * A batch carries no install id, user id or per-action time: only a one-off batch nonce (so a retry
 * isn't counted twice), the app version code and the counts. On by default, with a Settings switch;
 * turning it off deletes the counts not yet sent. Debug builds count locally but never send.
 */
class Usage(context: Context, private val base: String = BASE) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val file = File(context.filesDir, "usage_tallies.json")
    private val counts = HashMap<String, Int>()
    private var lastSent = 0L
    private var pendingBatch: String? = null
    private var savedAt = 0L
    private var triedAt = 0L

    init { load() }

    fun enabled() = !prefs.getBoolean(PREF, false)

    /** Adds one to [key]. Keys that don't fit [KEY] are dropped, so free text can't slip in. */
    fun count(key: String) {
        if (!enabled() || !KEY.matches(key)) return
        synchronized(this) {
            if (key !in counts && counts.size >= MAX_KEYS) return
            val isNew = key !in counts
            counts[key] = (counts[key] ?: 0) + 1
            // Busy agents call often: write at most every 30 s (or for a new key); losing a few counts is fine.
            if (isNew || System.currentTimeMillis() - savedAt > 30_000) save()
        }
    }

    /** What the next batch would send (Settings shows it). */
    fun pending(): Map<String, Int> = synchronized(this) { HashMap(counts) }

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(PREF, !on).apply()
        if (!on) synchronized(this) { counts.clear(); pendingBatch = null; save() }
    }

    /** Sends the tallies if a day has passed since the last batch. Blocking; call off the main thread. */
    fun flushIfDue(now: Long = System.currentTimeMillis()) {
        if (!enabled() || BuildConfig.DEBUG) return
        val (batch, sent) = synchronized(this) {
            // A failed send waits an hour, so an unreachable server isn't asked every 5 minutes.
            if (counts.isEmpty() || now - lastSent < DAY_MS || now - triedAt < HOUR_MS) return
            triedAt = now
            // The same nonce is reused until the server accepts it, so a lost reply can't double-count.
            val id = pendingBatch ?: UUID.randomUUID().toString().also { pendingBatch = it; save() }
            id to HashMap(counts)
        }
        val status = try {
            SafeHttp.post("$base/api/usage", SafeHttp.Policy.PUBLIC, mapOf("Content-Type" to "application/json"),
                payload(batch, BuildConfig.VERSION_CODE, sent).toString().toByteArray(), DEADLINE_MS)
        } catch (e: SafeHttp.Failure) { return }
        if (status != 200 && status != 400) return // 400: the server will never take this batch, so drop it too
        synchronized(this) {
            sent.forEach { (k, n) -> val left = (counts[k] ?: 0) - n; if (left > 0) counts[k] = left else counts.remove(k) }
            pendingBatch = null
            lastSent = now
            save()
        }
    }

    private fun load() = runCatching {
        val o = JSONObject(file.readText())
        o.optJSONObject("counts")?.let { c -> c.keys().forEach { k -> if (KEY.matches(k)) counts[k] = c.optInt(k) } }
        lastSent = o.optLong("last_sent")
        pendingBatch = o.optString("batch").ifEmpty { null }
    }

    private fun save() {
        val o = JSONObject().put("counts", JSONObject(counts as Map<*, *>)).put("last_sent", lastSent)
        pendingBatch?.let { o.put("batch", it) }
        savedAt = System.currentTimeMillis()
        runCatching { file.writeText(o.toString()) }
    }

    companion object {
        const val PREF = "usage_tallies_off"
        const val BASE = "https://bridgit.cc"
        const val MAX_KEYS = 300
        private const val DAY_MS = 24 * 3600_000L
        private const val HOUR_MS = 3600_000L
        private const val DEADLINE_MS = 15_000L

        /** Same rule as website/usage.mjs: a kind, then a feature name built from fixed identifiers. */
        val KEY = Regex("^[a-z]+:[A-Za-z0-9 /_{}.:-]{1,120}$")

        /** The whole request body: nothing here identifies the phone or its owner. */
        fun payload(batch: String, appVersion: Int, counts: Map<String, Int>): JSONObject =
            JSONObject().put("batch", batch).put("app_version", appVersion).put("counts", JSONObject(counts as Map<*, *>))
    }
}
