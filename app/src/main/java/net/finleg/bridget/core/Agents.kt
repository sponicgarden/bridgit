package net.finleg.bridget.core

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/** How an agent reaches the phone (6.5). Relay is reserved until Q10. */
enum class Connection(val id: String, val label: String) {
    DIRECT("direct", "Tailscale"), USERSPACE("userspace", "Userspace"), RELAY("relay", "Relay");
    companion object { fun of(id: String?) = values().firstOrNull { it.id == id } ?: DIRECT }
}

/** The agent record, AGT-01. `caps` is an explicit grant set; null preserves unrestricted legacy access. */
data class Agent(
    val id: String,
    val name: String,
    val keyHash: String,
    val keySalt: String,
    val keyPrefix: String,
    val createdAt: Long,
    val lastUsedAt: Long = 0,
    val lastSourceIp: String? = null,
    val requestCount: Long = 0,
    val connection: Connection = Connection.DIRECT,
    val notes: String = "",
    val developer: Boolean = false,
    val keyCreatedAt: Long = createdAt,
    /** The API revision this agent last read about (guide or changes); 0 = never (API-14). */
    val knownApiRevision: Int = 0,
    /** What the agent says it runs on (POST /v1/me, AGT-16): the model and the program around it. Unverified. */
    val model: String? = null,
    val harness: String? = null,
    val modelReportedAt: Long = 0,
    val caps: Set<String>? = null,
) {
    fun allows(capability: String): Boolean = capability == "status" || caps == null || capability in caps

    /** "Claude Opus 5.5 · Claude Code", or null until the agent reports it. */
    val modelLabel: String? get() = model?.let { m -> harness?.let { "$m · $it" } ?: m }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("key_hash", keyHash).put("key_salt", keySalt)
        .put("key_prefix", keyPrefix).put("created_at", createdAt).put("last_used_at", lastUsedAt)
        .put("last_source_ip", lastSourceIp ?: JSONObject.NULL).put("request_count", requestCount)
        .put("connection", connection.id).put("notes", notes).put("developer", developer)
        .put("key_created_at", keyCreatedAt).put("known_api_revision", knownApiRevision).put("caps", caps?.let { JSONArray(it.sorted()) } ?: JSONObject.NULL)
        .put("model", model ?: JSONObject.NULL).put("harness", harness ?: JSONObject.NULL).put("model_reported_at", modelReportedAt)

    companion object {
        fun fromJson(o: JSONObject) = Agent(
            id = o.getString("id"), name = o.getString("name"), keyHash = o.getString("key_hash"),
            keySalt = o.getString("key_salt"), keyPrefix = o.optString("key_prefix", ""),
            createdAt = o.getLong("created_at"), lastUsedAt = o.optLong("last_used_at", 0),
            lastSourceIp = o.optString("last_source_ip", "").ifEmpty { null }.takeIf { !o.isNull("last_source_ip") },
            requestCount = o.optLong("request_count", 0), connection = Connection.of(o.optString("connection")),
            notes = o.optString("notes", ""), developer = o.optBoolean("developer", false),
            keyCreatedAt = o.optLong("key_created_at", o.getLong("created_at")),
            knownApiRevision = o.optInt("known_api_revision", 0),
            model = o.optString("model", "").takeIf { it.isNotEmpty() && !o.isNull("model") },
            harness = o.optString("harness", "").takeIf { it.isNotEmpty() && !o.isNull("harness") },
            modelReportedAt = o.optLong("model_reported_at", 0),
            caps = o.optJSONArray("caps")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() },
        )
    }
}

/** A key that no longer works, kept 7 days so the agent learns why (AGT-12). Holds only the hash. */
data class Tombstone(val agentId: String, val agentName: String, val keyHash: String, val keySalt: String,
                     val reason: String, val at: Long) {
    fun toJson(): JSONObject = JSONObject().put("agent_id", agentId).put("agent_name", agentName)
        .put("key_hash", keyHash).put("key_salt", keySalt).put("reason", reason).put("at", at)
    companion object {
        fun fromJson(o: JSONObject) = Tombstone(o.getString("agent_id"), o.getString("agent_name"),
            o.getString("key_hash"), o.getString("key_salt"), o.getString("reason"), o.getLong("at"))
    }
}

/** Where agents are persisted. Android uses SharedPreferences; tests use memory. */
interface AgentStorage {
    fun load(): String?
    fun save(json: String)
}

sealed class AuthResult {
    data class Ok(val agent: Agent) : AuthResult()
    /** Key matches a rotated or removed agent; [reason] is "key_rotated" or "agent_removed". */
    data class Revoked(val reason: String, val agentName: String) : AuthResult()
    object Invalid : AuthResult()
}

/**
 * All agents, cached in memory because auth reads them on every request.
 * Writes are synchronised and persisted immediately; per-request usage counters
 * ([recordUse]) are persisted at most every few seconds.
 */
class AgentRegistry(private val storage: AgentStorage, private val clock: () -> Long = System::currentTimeMillis,
                    private val rnd: SecureRandom = SecureRandom()) {
    @Volatile private var agents: List<Agent> = emptyList()
    @Volatile private var tombstones: List<Tombstone> = emptyList()
    private var lastFlush = 0L
    private val listeners = mutableListOf<() -> Unit>()

    init { load() }

    fun all(): List<Agent> = agents
    fun get(id: String): Agent? = agents.firstOrNull { it.id == id }
    fun byName(name: String): Agent? = agents.firstOrNull { it.name.equals(name, ignoreCase = true) }
    val count get() = agents.size

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun changed() = synchronized(listeners) { listeners.toList() }.forEach { it() }

    /** Returns an error sentence for a proposed name, or null if it's fine. */
    fun checkName(name: String, exceptId: String? = null): String? = when {
        !NAME_RE.matches(name) -> "Use letters, numbers, spaces, - or _ (1 to 24)."
        agents.any { it.id != exceptId && it.name.equals(name, ignoreCase = true) } ->
            "You already have an agent called ${agents.first { it.name.equals(name, true) }.name}."
        exceptId == null && agents.size >= MAX_AGENTS -> "Bridgit holds up to $MAX_AGENTS agents. Remove one to add another."
        else -> null
    }

    /** Adds an agent and returns it with its new key. The key is never stored. */
    @Synchronized
    fun add(name: String, connection: Connection): Pair<Agent, String> {
        val n = name.trim()
        checkName(n)?.let { throw IllegalArgumentException(it) }
        val key = Keys.generate(rnd)
        val salt = Keys.newSalt(rnd)
        val now = clock()
        val agent = Agent(newId(), n, Keys.hash(salt, key), salt, Keys.displayPrefix(key), now, connection = connection)
        agents = agents + agent
        persist(); changed()
        return agent to key
    }

    /** New key for an agent. The old key stops working now and gets "key_rotated" for 7 days. */
    @Synchronized
    fun rotate(id: String): Pair<Agent, String> {
        val old = get(id) ?: throw IllegalArgumentException("No such agent")
        val key = Keys.generate(rnd)
        val salt = Keys.newSalt(rnd)
        val now = clock()
        val updated = old.copy(keyHash = Keys.hash(salt, key), keySalt = salt, keyPrefix = Keys.displayPrefix(key),
            keyCreatedAt = now, lastUsedAt = 0)
        tombstones = tombstones + Tombstone(old.id, old.name, old.keyHash, old.keySalt, "key_rotated", now)
        agents = agents.map { if (it.id == id) updated else it }
        persist(); changed()
        return updated to key
    }

    @Synchronized
    fun remove(id: String): Agent? {
        val old = get(id) ?: return null
        tombstones = tombstones + Tombstone(old.id, old.name, old.keyHash, old.keySalt, "agent_removed", clock())
        agents = agents.filterNot { it.id == id }
        persist(); changed()
        return old
    }

    @Synchronized
    fun update(id: String, f: (Agent) -> Agent): Agent? {
        val old = get(id) ?: return null
        val new = f(old)
        agents = agents.map { if (it.id == id) new else it }
        persist(); changed()
        return new
    }

    /** Checks a presented key against every agent in constant time per agent, then against tombstones. */
    fun authenticate(candidate: String?): AuthResult {
        if (!Keys.isWellFormed(candidate)) return AuthResult.Invalid
        val key = candidate!!
        var match: Agent? = null
        for (a in agents) if (Keys.matches(a.keySalt, a.keyHash, key)) match = a
        if (match != null) return AuthResult.Ok(match)
        val cutoff = clock() - TOMBSTONE_MS
        for (t in tombstones) if (t.at >= cutoff && Keys.matches(t.keySalt, t.keyHash, key))
            return AuthResult.Revoked(t.reason, t.agentName)
        return AuthResult.Invalid
    }

    /** Counts a request. Persisted at most every [FLUSH_MS] so a busy agent doesn't rewrite prefs per call. */
    @Synchronized
    fun recordUse(id: String, sourceIp: String) {
        val now = clock()
        agents = agents.map {
            if (it.id == id) it.copy(lastUsedAt = now, lastSourceIp = sourceIp, requestCount = it.requestCount + 1) else it
        }
        if (now - lastFlush > FLUSH_MS) { persist(); lastFlush = now }
        changed()
    }

    @Synchronized
    fun flush() = persist()

    private fun newId(): String {
        while (true) {
            val id = ByteArray(6).also { rnd.nextBytes(it) }.toHex()
            if (get(id) == null) return id
        }
    }

    private fun load() {
        val raw = storage.load() ?: return
        runCatching {
            val o = JSONObject(raw)
            val a = o.optJSONArray("agents") ?: JSONArray()
            agents = (0 until a.length()).map { Agent.fromJson(a.getJSONObject(it)) }
            val t = o.optJSONArray("tombstones") ?: JSONArray()
            tombstones = (0 until t.length()).map { Tombstone.fromJson(t.getJSONObject(it)) }
        }
    }

    private fun persist() {
        val cutoff = clock() - TOMBSTONE_MS
        tombstones = tombstones.filter { it.at >= cutoff }
        val o = JSONObject()
            .put("agents", JSONArray().apply { agents.forEach { put(it.toJson()) } })
            .put("tombstones", JSONArray().apply { tombstones.forEach { put(it.toJson()) } })
        storage.save(o.toString())
    }

    companion object {
        const val MAX_AGENTS = 16
        const val TOMBSTONE_MS = 7L * 24 * 3600 * 1000
        const val FLUSH_MS = 5_000L
        val NAME_RE = Regex("^[A-Za-z0-9][A-Za-z0-9 _-]{0,23}$")
        val SUGGESTIONS = listOf("Muse", "Grok", "Claude", "Codex", "ChatGPT", "Gemini", "Instinct")
    }
}
