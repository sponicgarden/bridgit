package net.finleg.bridget.api

import net.finleg.bridget.core.Connection
import net.finleg.bridget.core.Keys
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Ways the pure API logic can fail:
 * Rate limiter: hits never expire; the limit is off by one; a refused attempt still counts.
 * Idempotency: two concurrent repeats both run; a repeat after success runs again; one agent's key
 *   replays another agent's result; media bodies kept longer than 15 min (SEC-13); 5xx results replayed.
 * Sizing: upscaling; wrong aspect ratio; presets ignored; explicit values not overriding presets; bad values accepted.
 * Error catalog: a code used by the app missing from the spec (or the other way round); HTTP status mismatch.
 * Setup prompt: key missing or present twice; wrong port/host; userspace prompt without the helper; direct
 *   prompt over the 2,500-character budget (AGT-10); capture calls listed while switched off (AGT-07).
 * Guide / openapi: an endpoint missing; invalid structure; a secret leaking in.
 * Demo script: a demo names an endpoint that doesn't exist; a switched-off or ungranted demo offered as ready;
 *   the setup prompt or guide not pointing at it.
 * Wait gate: more than 4 waits per agent; counts never released.
 */
class ApiLogicTest {
    private var now = 0L

    @Test fun rateLimiterWindow() {
        val r = RateLimiter(clock = { now })
        repeat(5) { assertTrue(r.tryAcquire("ip", 5)) }
        assertFalse(r.tryAcquire("ip", 5))
        assertEquals(5, r.count("ip"))
        assertTrue(r.retryAfterS("ip") in 1..60)
        assertTrue("another key is separate", r.tryAcquire("other", 5))
        now += 60_001
        assertEquals(0, r.count("ip"))
        assertTrue(r.tryAcquire("ip", 5))
    }

    @Test fun waitGate() {
        val g = WaitGate(perAgent = 4, total = 5)
        repeat(4) { assertTrue(g.enter("a")) }
        assertFalse(g.enter("a"))
        assertTrue(g.enter("b"))
        assertFalse("total cap", g.enter("c"))
        g.exit("a")
        assertTrue(g.enter("c"))
    }

    @Test fun idempotency() {
        val c = IdempotencyCache(clock = { now })
        assertEquals(IdempotencyCache.Claim.New, c.claim("a1", "k1"))
        assertEquals(IdempotencyCache.Claim.Running, c.claim("a1", "k1"))
        assertEquals("other agent, same key", IdempotencyCache.Claim.New, c.claim("a2", "k1"))
        // hasResult (used to skip pre-body admission for replays): only once finished, only for that agent.
        assertFalse("running isn't a result", c.hasResult("a1", "k1"))
        c.finish("a1", "k1", 200, "application/json", "{\"x\":1}".toByteArray(), emptyMap(), isMedia = false)
        assertTrue(c.hasResult("a1", "k1")); assertFalse(c.hasResult("a2", "k1"))
        val d = c.claim("a1", "k1") as IdempotencyCache.Claim.Done
        assertEquals("{\"x\":1}", String(d.stored.body!!))
        now += 23 * 3600_000L
        assertTrue(c.claim("a1", "k1") is IdempotencyCache.Claim.Done)
        now += 2 * 3600_000L
        assertEquals("expired after 24 h", IdempotencyCache.Claim.New, c.claim("a1", "k1"))
    }

    @Test fun idempotencyMediaAndErrors() {
        val c = IdempotencyCache(clock = { now })
        c.claim("a", "m"); c.finish("a", "m", 200, "image/jpeg", ByteArray(10), emptyMap(), isMedia = true)
        assertTrue(c.claim("a", "m") is IdempotencyCache.Claim.Done)
        now += 16 * 60_000L
        assertEquals("media body dropped after 15 min", IdempotencyCache.Claim.Expired, c.claim("a", "m"))
        c.claim("a", "e"); c.finish("a", "e", 500, "application/json", ByteArray(1), emptyMap(), isMedia = false)
        assertEquals("5xx is not remembered", IdempotencyCache.Claim.New, c.claim("a", "e"))
        c.finish("a", "e", 422, "application/json", ByteArray(1), emptyMap(), isMedia = false)
        assertEquals("4xx is not remembered (nothing was done)", IdempotencyCache.Claim.New, c.claim("a", "e"))
        c.abandon("a", "e")
        // Byte budget: a body that doesn't fit leaves only the done marker, so no second capture.
        val small = IdempotencyCache(clock = { now }, maxBodyBytes = 5)
        small.claim("a", "big"); small.finish("a", "big", 200, "image/jpeg", ByteArray(10), emptyMap(), isMedia = true)
        assertEquals(IdempotencyCache.Claim.Expired, small.claim("a", "big"))
        // Full: new keys are refused, existing markers are never evicted.
        val tiny = IdempotencyCache(clock = { now }, maxEntries = 1)
        tiny.claim("a", "k1"); tiny.finish("a", "k1", 200, "application/json", ByteArray(1), emptyMap(), isMedia = false)
        assertEquals(IdempotencyCache.Claim.Full, tiny.claim("a", "k2"))
        assertTrue(tiny.claim("a", "k1") is IdempotencyCache.Claim.Done)
        assertTrue(IdempotencyCache.KEY_RE.matches("123e4567-e89b-12d3-a456-426614174000"))
        assertFalse(IdempotencyCache.KEY_RE.matches("bad key!"))
    }

    @Test fun sizingTargets() {
        val agent = Sizing.PRESETS.getValue("agent")
        assertEquals(1568 to 1176, Sizing.target(4000, 3000, agent))
        assertEquals(1176 to 1568, Sizing.target(3000, 4000, agent))
        assertEquals("never upscales", 800 to 600, Sizing.target(800, 600, agent))
        assertEquals(512 to 384, Sizing.target(4000, 3000, Sizing.PRESETS.getValue("thumb")))
        assertEquals(4000 to 3000, Sizing.target(4000, 3000, Sizing.PRESETS.getValue("original")))
        assertEquals(1024 to 768, Sizing.target(4000, 3000, Sizing.Spec(null, 1024, 90)))
        assertEquals(1, Sizing.sampleSize(4000, 3000))
        assertEquals(2, Sizing.sampleSize(4000, 1568))
        assertEquals(4, Sizing.sampleSize(8000, 1568))
    }

    @Test fun sizingResolve() {
        fun r(vararg kv: Pair<String, Any?>) = Sizing.resolve({ k -> kv.toMap()[k] }, "agent")
        assertEquals(Sizing.PRESETS["agent"], r())
        assertEquals(Sizing.PRESETS["thumb"], r("size" to "thumb"))
        assertEquals(800, r("max_edge_px" to "800").maxEdge)
        assertEquals(60, r("size" to "large", "quality" to 60).quality)
        assertEquals(2560, r("size" to "large", "quality" to 60).maxEdge)
        for (bad in listOf("size" to "huge", "max_edge_px" to "10", "quality" to "101", "max_width_px" to "abc"))
            try { r(bad); fail("accepted $bad") } catch (e: ApiError) { assertEquals("invalid_parameter", e.code) }
    }

    @Test fun errorCatalogMatchesSpecAppendixC() {
        val spec = File("../docs/bridgit-spec-v2.md").takeIf { it.exists() } ?: File("docs/bridgit-spec-v2.md")
        val text = spec.readText()
        val appendix = text.substringAfter("## Appendix C. Error catalog").substringBefore("## Appendix D")
        val rows = Regex("^\\| (\\d{3}) \\| (?:\\(pending[^|]*|`([a-z_]+)`)", RegexOption.MULTILINE).findAll(appendix)
            .mapNotNull { m -> m.groupValues[2].takeIf { it.isNotEmpty() }?.let { it to m.groupValues[1].toInt() } }.toMap()
        val code = ErrorCatalog.all.associate { it.code to it.http }
        assertEquals("codes in the app but not in the spec", emptySet<String>(), code.keys - rows.keys)
        assertEquals("codes in the spec but not in the app", emptySet<String>(), rows.keys - code.keys)
        for ((c, h) in code) assertEquals("HTTP for $c", rows[c], h)
        for (e in Endpoints.all) e.errors.forEach { ErrorCatalog.http(it) }
    }

    private val addr = Address("my-phone.tailnet-name.ts.net", "100.101.102.103", 8735)
    private fun caps(vararg on: String) = listOf("camera", "mic", "gallery").map {
        CapInfo(it, it, it, it in on, true, null)
    }

    @Test fun setupPromptDirect() {
        val key = Keys.generate()
        val p = SetupPrompt.build("Muse", key, Connection.DIRECT, addr, "Pixel 10 Pro XL", caps("gallery", "camera"), null)
        assertEquals("key exactly once", 1, Regex(Regex.escape(key)).findAll(p).count())
        assertTrue(p.contains("http://my-phone.tailnet-name.ts.net:8735/v1"))
        assertTrue(p.contains("IP fallback: http://100.101.102.103:8735/v1"))
        assertTrue(p.contains("Your agent name in Bridgit is \"Muse\""))
        assertTrue(p.contains("curl -s -m 30 -H @\$HOME/.config/bridget/header"))
        assertTrue(p.contains("/v1/camera/snapshot") && p.contains("/v1/image/last"))
        assertTrue(p.contains("GET /v1/guide"))
        assertTrue(p.contains("GET /v1/demo"))
        assertTrue("AGT-10 budget: ${p.length}", p.length < 2500)
        // AGT-09 order
        val order = listOf("CONNECTION", "YOUR KEY", "HOW TO CALL", "START HERE", "MOST USEFUL CALLS", "RULES", "FIRST TEST").map { p.indexOf(it) }
        assertEquals(order.sorted(), order)
    }

    @Test fun setupPromptWithCallback() {
        val key = Keys.generate()
        val secret = net.finleg.bridget.core.CallbackRules.newSecret()
        val p = SetupPrompt.build("Grok", key, Connection.DIRECT, addr, "Pixel 10 Pro XL", caps("gallery", "camera"), null,
            secret)
        assertEquals("secret exactly once", 1, Regex(Regex.escape(secret)).findAll(p).count())
        assertTrue(p.contains("CALLBACK SECRET (verify: /v1/guide): $secret"))
        assertTrue("AGT-10 budget: ${p.length}", p.length < 2500)
        assertTrue(p.indexOf("CALLBACK SECRET") in p.indexOf("RULES")..p.indexOf("FIRST TEST"))
        assertFalse(SetupPrompt.build("Grok", key, Connection.DIRECT, addr, "Pixel", caps(), null).contains("CALLBACK"))
    }

    @Test fun setupPromptNothingOnAndUserspace() {
        val key = Keys.generate()
        val p = SetupPrompt.build("Grok", key, Connection.DIRECT, addr, "Pixel", caps(), null)
        assertTrue(p.contains("hasn't switched on any features yet"))
        assertFalse(p.contains("/v1/camera/snapshot"))
        val helper = "#!/usr/bin/env python3\nprint('helper')\n"
        val u = SetupPrompt.build("Grok", key, Connection.USERSPACE, addr, "Pixel", caps("camera"), helper)
        assertTrue(u.contains("BRIDGET_HOST=100.101.102.103") && u.contains("BRIDGET_PORT=8735"))
        assertTrue(u.contains("----- BEGIN ~/bin/bridget -----\n$helper".trimEnd()))
        assertEquals(1, Regex(Regex.escape(key)).findAll(u).count())
        val qr = JSONObject(SetupPrompt.qrPayload("Muse", key, addr))
        assertEquals(1, qr.getInt("bridget")); assertEquals(key, qr.getString("key"))
        assertTrue(SetupPrompt.qrPayload("Muse", key, addr).length < 200)
    }

    @Test fun guideAndOpenApi() {
        val g = Guide.build("Grok", "1.3.0", addr, caps("camera"), "own", developer = false)
        for (e in Endpoints.all) assertTrue(e.fullPath, g.contains(e.fullPath))
        assertTrue(g.contains("only your own entries"))
        assertTrue(g.contains("**switched off**"))
        assertNull(Keys.PATTERN.find(g))
        val o = OpenApi.build("1.3.0", addr)
        assertEquals("3.1.0", o.getString("openapi"))
        val paths = o.getJSONObject("paths")
        for (e in Endpoints.all) assertTrue(e.fullPath, paths.getJSONObject(e.fullPath).has(e.method.lowercase()))
        assertEquals("id", paths.getJSONObject("/v1/log/{id}").getJSONObject("get").getJSONArray("parameters").getJSONObject(0).getString("name"))
        assertEquals(0, paths.getJSONObject("/v1/tools/bridget").getJSONObject("get").getJSONArray("security").length())
    }

    @Test fun demoScript() {
        val keys = Endpoints.all.map { "${it.method} ${it.fullPath}" }.toSet()
        for (d in Demos.all) d.calls.forEach { assertTrue("${d.title}: no endpoint $it", it in keys) }
        val caps = listOf(CapInfo("interactions", "Questions", "interactions", true, true, null),
            CapInfo("camera", "Camera", "camera", true, true, null), CapInfo("mic", "Microphone", "mic", false, true, null),
            CapInfo("location", "Location", "location", true, true, null))
        val m = Demos.build("Grok", caps) { it != "location" }
        val ready = m.lines().first { it.startsWith("Ready now:") }
        assertTrue(ready.contains("Camera control") && ready.contains("Ask me with buttons"))
        assertFalse(ready.contains("Listen and transcribe") || ready.contains("Where am I"))
        assertTrue(m.contains("### Listen and transcribe (not available: Microphone is switched off"))
        assertTrue(m.contains("### Where am I (not available: the owner hasn't allowed you"))
        assertTrue(m.contains("ask these on the phone instead"))
        assertFalse(Demos.build("Grok", caps.drop(1)) { true }.contains("ask these on the phone instead"))
        assertTrue(Guide.build("Grok", "1.6.0", addr, caps, "own", developer = false).contains("`GET /v1/demo`"))
    }

    @Test fun pendingSchema() {
        val p = Pending.body("job_queued", "j1", "/v1/jobs/j1?wait=55", "r1")
        assertTrue(p.getBoolean("pending")); assertEquals(1, p.getInt("retry_after_s"))
        try { Pending.body("nope", "x", "/", "r"); fail() } catch (_: IllegalArgumentException) {}
    }
}

/**
 * Ways API revisions (API-14) can fail: a release adds an endpoint but no revision entry
 * (agents never hear of it), an entry names an endpoint that doesn't exist, revisions out
 * of order, or whats_new still nags an agent that is up to date.
 */
class ApiChangesTest {
    @Test fun revisionsAreOrderedAndNameRealEndpoints() {
        val revs = ApiChanges.revisions.map { it.revision }
        assertEquals((1..revs.size).toList(), revs)
        val paths = Endpoints.all.map { it.fullPath }.toSet()
        for (r in ApiChanges.revisions) for (i in r.items) i.endpoint?.let { assertTrue("unknown endpoint $it", it in paths) }
        // every endpoint is announced in some revision (directly or as part of a family)
        for (e in Endpoints.all) assertTrue("${e.fullPath} not announced", ApiChanges.revisions.flatMap { it.items }
            .any { it.endpoint != null && (e.fullPath == it.endpoint || e.fullPath.startsWith(it.endpoint.substringBefore("/{")) || it.endpoint.startsWith(e.fullPath)) }
            || e.fullPath in setOf("/v1/capabilities", "/v1/guide", "/v1/openapi.json", "/v1/pair", "/v1/audit"))
    }

    @Test fun whatsNew() {
        assertEquals(org.json.JSONObject.NULL, ApiChanges.whatsNew(ApiChanges.CURRENT))
        val w = ApiChanges.whatsNew(0) as org.json.JSONObject
        assertEquals(ApiChanges.CURRENT, w.getInt("new_revisions"))
        assertEquals("/v1/changes?since=0", w.getString("url"))
        assertEquals(1, ApiChanges.json(ApiChanges.CURRENT - 1, "x").getJSONArray("changes").length())
    }
}
