package net.finleg.bridget

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Settings, capability toggles and the paired agents, kept in app-private
 * storage. Each agent has its own token; only a salted SHA-256 hash of it is
 * stored. The token itself lives in memory only while a pairing window is open.
 */
class Policy(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("policy", Context.MODE_PRIVATE)
    private val rnd = SecureRandom()

    enum class Capability(val key: String) { CAMERA("camera"), MIC("mic"), GALLERY("gallery") }

    class Agent(val id: String, val name: String, val salt: String, val hash: String,
                val createdAt: Long, val lastUsedAt: Long)

    fun isEnabled(c: Capability) = prefs.getBoolean("cap_${c.key}", false)
    fun setEnabled(c: Capability, on: Boolean) = prefs.edit().putBoolean("cap_${c.key}", on).apply()

    var listenerEnabled: Boolean
        get() = prefs.getBoolean("listener", false)
        set(v) = prefs.edit().putBoolean("listener", v).apply()

    var port: Int
        get() = prefs.getInt("port", 8734)
        set(v) = prefs.edit().putInt("port", v).apply()

    // ---- agents ---------------------------------------------------------------

    @Synchronized
    fun agents(): List<Agent> {
        val arr = JSONArray(prefs.getString("agents", "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Agent(o.getString("id"), o.getString("name"), o.getString("salt"), o.getString("hash"),
                o.getLong("created_at"), o.optLong("last_used_at", 0))
        }
    }

    val hasAgents: Boolean get() = agents().isNotEmpty()

    /** Validates a new agent name; returns an error message or null. */
    fun checkName(name: String): String? = when {
        !NAME_RE.matches(name) -> "Use 1-24 letters, numbers, spaces, - or _"
        agents().any { it.name.equals(name, ignoreCase = true) } -> "An agent called \"$name\" already exists"
        agents().size >= MAX_AGENTS -> "At most $MAX_AGENTS agents"
        else -> null
    }

    /** Adds an agent with a fresh token. Returns the agent and its token. */
    @Synchronized
    fun addAgent(name: String): Pair<Agent, String> {
        checkName(name)?.let { throw IllegalArgumentException(it) }
        val id = ByteArray(6).also { rnd.nextBytes(it) }.toHex()
        val (agent, token) = newCredential(id, name, System.currentTimeMillis())
        save(agents() + agent)
        return agent to token
    }

    /** Replaces an agent's token. The old one stops working immediately. */
    @Synchronized
    fun rotate(id: String): Pair<Agent, String> {
        val old = agents().first { it.id == id }
        val (agent, token) = newCredential(id, old.name, old.createdAt)
        save(agents().map { if (it.id == id) agent else it })
        return agent to token
    }

    @Synchronized
    fun remove(id: String) = save(agents().filterNot { it.id == id })

    /** Returns the agent whose token matches, or null. Checks every agent in constant time. */
    fun authenticate(candidate: String?): Agent? {
        if (candidate.isNullOrEmpty()) return null
        var match: Agent? = null
        for (a in agents()) {
            if (MessageDigest.isEqual(a.hash.toByteArray(), hash(a.salt, candidate).toByteArray())) match = a
        }
        return match
    }

    @Synchronized
    fun touch(id: String) {
        val now = System.currentTimeMillis()
        save(agents().map { if (it.id == id) Agent(it.id, it.name, it.salt, it.hash, it.createdAt, now) else it })
    }

    private fun newCredential(id: String, name: String, createdAt: Long): Pair<Agent, String> {
        val token = ByteArray(32).also { rnd.nextBytes(it) }.toHex()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }.toHex()
        return Agent(id, name, salt, hash(salt, token), createdAt, 0) to token
    }

    private fun save(list: List<Agent>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("salt", it.salt).put("hash", it.hash)
                .put("created_at", it.createdAt).put("last_used_at", it.lastUsedAt))
        }
        prefs.edit().putString("agents", arr.toString()).apply()
    }

    private fun hash(salt: String, token: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + ":" + token).toByteArray()).toHex()

    companion object {
        const val MAX_AGENTS = 8
        val NAME_RE = Regex("^[A-Za-z0-9][A-Za-z0-9 _-]{0,23}$")
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
