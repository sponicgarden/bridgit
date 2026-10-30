package net.finleg.bridget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ways key and agent logic can fail:
 * 1. Generated keys have the wrong length, alphabet or prefix, so scanners or the format check miss them.
 * 2. A 1.2 key (64 hex) or a near-miss string is accepted.
 * 3. The plaintext key ends up in the persisted JSON.
 * 4. Rotation leaves the old key working, or the new key not working.
 * 5. A rotated/removed key gets a plain 401 instead of its reason, or the reason outlives 7 days.
 * 6. Names: duplicates differing only by case, bad characters, more than 16 agents.
 * 7. Persisted state doesn't survive a reload (tombstones lost, so reasons vanish after a restart).
 * 8. scrub() misses a key inside other text.
 */
class KeysAndAgentsTest {
    private class MemStorage(var json: String? = null) : AgentStorage {
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }

    private var now = 1_000_000L
    private fun registry(s: MemStorage = MemStorage()) = AgentRegistry(s, clock = { now })

    @Test fun keyFormat() {
        repeat(200) {
            val k = Keys.generate()
            assertEquals(47, k.length)
            assertTrue(k, Keys.isWellFormed(k))
            assertTrue(Keys.PATTERN.matches(k))
        }
        assertNotEquals(Keys.generate(), Keys.generate())
    }

    @Test fun malformedKeysRejected() {
        val hex64 = "a".repeat(64)
        for (bad in listOf(null, "", hex64, "brg_", "brg_" + "A".repeat(42), "brg_" + "A".repeat(44), "brg_" + "A".repeat(42) + "=",
            "BRG_" + "A".repeat(43), " brg_" + "A".repeat(43), "brg_" + "A".repeat(42) + "+"))
            assertFalse("$bad", Keys.isWellFormed(bad))
        assertEquals(AuthResult.Invalid, registry().authenticate(hex64))
    }

    @Test fun hashMatchesOnlyTheRightKey() {
        val salt = Keys.newSalt()
        val k = Keys.generate()
        val h = Keys.hash(salt, k)
        assertTrue(Keys.matches(salt, h, k))
        assertFalse(Keys.matches(salt, h, Keys.generate()))
        assertFalse(Keys.matches(Keys.newSalt(), h, k))
    }

    @Test fun addAuthenticateAndNoPlaintextStored() {
        val s = MemStorage()
        val r = registry(s)
        val (a, key) = r.add("Grok", Connection.USERSPACE)
        assertEquals(a.id, (r.authenticate(key) as AuthResult.Ok).agent.id)
        assertEquals(key.take(8), a.keyPrefix)
        assertFalse("key persisted", s.json!!.contains(key))
        assertFalse("key persisted", s.json!!.contains(key.drop(4)))
        assertEquals(Connection.USERSPACE, registry(s).get(a.id)!!.connection)
    }

    @Test fun rotateKillsOldKeyWithReasonForSevenDays() {
        val s = MemStorage()
        val r = registry(s)
        val (a, old) = r.add("Muse", Connection.DIRECT)
        val (_, new) = r.rotate(a.id)
        assertEquals(AuthResult.Revoked("key_rotated", "Muse"), r.authenticate(old))
        assertTrue(r.authenticate(new) is AuthResult.Ok)
        // survives a restart
        assertEquals(AuthResult.Revoked("key_rotated", "Muse"), registry(s).authenticate(old))
        now += AgentRegistry.TOMBSTONE_MS + 1
        assertEquals(AuthResult.Invalid, registry(s).authenticate(old))
    }

    @Test fun removeGivesAgentRemoved() {
        val r = registry()
        val (a, key) = r.add("Codex", Connection.DIRECT)
        r.remove(a.id)
        assertNull(r.get(a.id))
        assertEquals(AuthResult.Revoked("agent_removed", "Codex"), r.authenticate(key))
    }

    @Test fun names() {
        val r = registry()
        r.add("Claude", Connection.DIRECT)
        assertNotNull(r.checkName("claude"))
        assertNotNull(r.checkName(""))
        assertNotNull(r.checkName(" lead"))
        assertNotNull(r.checkName("a".repeat(25)))
        assertNotNull(r.checkName("bad/name"))
        assertNull(r.checkName("Grok-2 beta_1"))
        assertNull("renaming to own name is fine", r.checkName("Claude", exceptId = r.byName("claude")!!.id))
        repeat(15) { r.add("A$it", Connection.DIRECT) }
        assertEquals(16, r.count)
        assertNotNull(r.checkName("Seventeen"))
    }

    @Test fun recordUseCountsAndPersistsEventually() {
        val s = MemStorage()
        val r = registry(s)
        val (a, _) = r.add("Grok", Connection.DIRECT)
        r.recordUse(a.id, "100.88.1.2")
        r.recordUse(a.id, "100.88.1.2")
        assertEquals(2, r.get(a.id)!!.requestCount)
        r.flush()
        assertEquals("100.88.1.2", registry(s).get(a.id)!!.lastSourceIp)
    }

    @Test fun scrub() {
        val k = Keys.generate()
        val text = "header Authorization: Bearer $k and again $k."
        val out = Keys.scrub(text)
        assertFalse(out.contains(k))
        assertTrue(out.contains("brg_[redacted]"))
    }

    @Test fun tailnetAddresses() {
        for (ok in listOf("100.64.0.1", "100.101.102.103", "100.127.255.255", "/100.88.1.2", "fd7a:115c:a1e0::1", "fd7a:115c:a1e0:ab12::5%tun0"))
            assertTrue(ok, Net.isTailnet(ok))
        for (bad in listOf("100.63.255.255", "100.128.0.1", "192.168.1.32", "127.0.0.1", "10.0.0.1", "fd7a:115c:a1e1::1",
            "::1", "face", "pixel.ts.net", "", null))
            assertFalse("$bad", Net.isTailnet(bad))
    }
}
