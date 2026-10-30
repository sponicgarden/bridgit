package net.finleg.bridget.api

/**
 * The public API page (owner request, 1.5.1): one section per "What can Bridgit do?" card with
 * every call it uses, then the full endpoint reference, the error catalog and the API revisions.
 * Generated from the same EndpointSpecs as the router and openapi.json, so it can't drift.
 * Published next to the updates (tools/publish-apk.sh); each card's Share button links to its anchor.
 * No phone address or secret is in it: the host is a placeholder.
 */
object ApiDocs {
    const val URL = "https://pub-3a79691572bc4abc93fce31910fe320b.r2.dev/bridget/api.html"

    data class Card(val title: String, val stage: String, val technical: Boolean, val what: String, val useCase: String,
                    val options: List<String>, val endpoints: List<String>, val ref: String)

    /** Stable anchor for a card title, e.g. "Take a photo" -> "take-a-photo". */
    fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    fun link(title: String) = "$URL#${slug(title)}"

    /** The Interactions › Applets subtab's "How to build a ready-made applet" card links here. */
    const val APPLET_TITLE = "Build an applet"

    fun endpointKey(e: EndpointSpec) = "${e.method} ${e.fullPath}"
    private fun epAnchor(e: EndpointSpec) = "ep-" + slug("${e.method} ${e.fullPath}")
    fun find(key: String): EndpointSpec? = Endpoints.all.firstOrNull { endpointKey(it) == key }

    private fun h(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    /** `code` in descriptions -> <code>. */
    private fun md(s: String) = h(s).replace(Regex("`([^`]+)`"), "<code>$1</code>")

    fun html(cards: List<Card>, appVersion: String): String = buildString {
        append("""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Bridgit API</title><style>
:root{--bg:#F8F9FF;--card:#E6EBF5;--ink:#151B27;--muted:#3C4558;--line:#B5BFD2;--accent:#1F5BD6;--ok:#C3EFCD;--okink:#00210B;--next:#D6E2FF;--nextink:#001849;--code:#D7DEEC}
@media (prefers-color-scheme:dark){:root{--bg:#0B0F17;--card:#1A2130;--ink:#E1E6F2;--muted:#BAC4D8;--line:#3D475C;--accent:#ADC6FF;--ok:#005226;--okink:#9CF6B4;--next:#1B4AAE;--nextink:#DCE6FF;--code:#262F42}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:860px;margin:0 auto;padding:24px 16px 64px}h1{font-size:28px;margin:0 0 4px}h2{font-size:22px;margin:40px 0 12px}
.sub{color:var(--muted)}section.card{background:var(--card);border-radius:12px;padding:16px;margin:12px 0;scroll-margin-top:16px}
section.card:target{outline:2px solid var(--accent)}h3{margin:6px 0 4px;font-size:19px}.chip{display:inline-block;font-size:12px;font-weight:600;padding:2px 8px;border-radius:6px;background:var(--code);color:var(--muted)}
.chip.now{background:var(--ok);color:var(--okink)}.chip.next{background:var(--next);color:var(--nextink)}
.label{color:var(--accent);font-size:13px;font-weight:600;margin-top:10px}.use{font-style:italic;color:var(--muted)}
code,pre{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:13px}code{background:var(--code);padding:1px 4px;border-radius:4px}
pre{background:var(--code);padding:10px;border-radius:8px;overflow-x:auto;white-space:pre-wrap;word-break:break-word}
.ep{border-top:1px solid var(--line);margin-top:12px;padding-top:10px;scroll-margin-top:16px}.ep:target{outline:2px solid var(--accent);border-radius:8px}
table{border-collapse:collapse;width:100%;font-size:14px}td,th{border-bottom:1px solid var(--line);padding:6px 4px;text-align:left;vertical-align:top}
a{color:var(--accent)}nav a{margin-right:12px}ul{margin:4px 0 4px 20px;padding:0}
</style></head><body><main>""")
        append("<h1>Bridgit API</h1><p class=\"sub\">Bridgit: Agentic Android Device Bridge, version ${h(appVersion)}, API revision ${ApiChanges.CURRENT}. ")
        append("What an AI agent can ask an Android phone to do, and the exact calls. Base URL <code>http://&lt;phone&gt;:8734/v1</code> on the owner's Tailscale network; ")
        append("every call needs <code>Authorization: Bearer &lt;key&gt;</code> except where noted. A running phone also serves this live at <code>GET /v1/guide</code> and <code>GET /v1/openapi.json</code>.</p>")
        append("<nav><a href=\"#abilities\">Supported Agent Requests</a><a href=\"#technical\">How it works</a><a href=\"#${slug(APPLET_TITLE)}\">Build an applet</a><a href=\"#reference\">All endpoints</a><a href=\"#errors\">Errors</a><a href=\"#revisions\">Revisions</a></nav>")
        for ((id, title, technical) in listOf(Triple("abilities", "Supported Agent Requests:", false), Triple("technical", "How Bridgit works", true))) {
            append("<h2 id=\"$id\">${h(title)}</h2>")
            for (c in cards.filter { it.technical == technical }) card(c)
        }
        appletGuide()
        append("<h2 id=\"reference\">All endpoints</h2>")
        for (e in Endpoints.all) endpoint(e)
        append("<h2 id=\"errors\">Errors</h2><p>Errors are <code>{\"error\",\"message\",\"request_id\"}</code> with these codes.</p><table><tr><th>HTTP</th><th>Code</th><th>Meaning</th><th>The agent should</th></tr>")
        for (e in ErrorCatalog.all.filter { it.built }) append("<tr><td>${e.http}</td><td><code>${e.code}</code></td><td>${md(e.meaning)}</td><td>${md(e.agentShould)}</td></tr>")
        append("</table><h2 id=\"revisions\">API revisions</h2>")
        for (r in ApiChanges.revisions.reversed()) {
            append("<p><b>Revision ${r.revision}</b> (app ${h(r.appVersion)}, ${h(r.date)})</p><ul>")
            r.items.forEach { append("<li>${md(it.what)}${it.endpoint?.let { e -> " <code>${h(e)}</code>" } ?: ""}</li>") }
            append("</ul>")
        }
        append("</main></body></html>")
    }

    /**
     * Instructions an agent can follow to build an applet (owner request, 27 Oct 2026). City tour
     * (8.25) is the worked example; these steps are how it was built.
     */
    private fun StringBuilder.appletGuide() {
        append("<h2 id=\"${slug(APPLET_TITLE)}\">Build an applet</h2>")
        append("<p>An applet is a ready-made session between the owner and one agent, like City tour: the phone sends the agent data on a schedule or when the owner acts, ")
        append("and the agent answers out loud or with a question card. There are two ways to build one. Start with A; do B when the applet works and the owner wants a button for it.</p>")
        append("<section class=\"card\"><h3>A. Run it from your side (no app change)</h3><p>Any paired agent can do this today with the calls below.</p><ol>")
        listOf(
            "Report your model once: `POST /v1/me {\"model\",\"harness\"}`.",
            "Offer the applet with a question card: `POST /v1/interactions` with buttons, e.g. Start / Not now. Wait for the answer with `GET /v1/interactions/{id}?wait=55`.",
            "Keep one loop on `GET /v1/events?since=<last id>&wait=55` (60 s timeout). If your harness only acts during a turn, run the wait as a background command so each event wakes you.",
            "Read what the applet needs on a timer you keep: `GET /v1/location` (with compass), `POST /v1/camera/snapshot`, `GET /v1/image/last?type=photo`, `POST /v1/mic/record`. Check `GET /v1/capabilities` first and ask the owner to switch on what is off.",
            "Talk back with `POST /v1/speaker/say {\"text\"}` (2-4 short spoken sentences; newer narration replaces what is queued) or a question card when you need a choice.",
            "Stop when the owner says so (a Stop button on your card) or after a time limit you announced. Only capture while the applet runs; every capture is shown and logged.",
        ).forEach { append("<li>${md(it)}</li>") }
        append("</ol></section>")
        append("<section class=\"card\"><h3>B. Add it to the Bridgit app (a coding agent with the repository)</h3>")
        append("<p>Copy City tour. Each step names the City tour file to copy from; keep its rules.</p><ol>")
        listOf(
            "Write the spec first: a section next to 8.25 City tour in `docs/bridgit-spec-v2.md` with numbered rules like TOUR-01..07 (what it needs switched on, what is sent to whom, limits).",
            "Session engine: a manager like `TourManager` in `tour/Tour.kt`. One session at a time, one agent, sends `<applet>.started` (with a `brief` string telling the agent what to do), `<applet>.tick`, owner actions, and `<applet>.ended` through `app.interactions.event(agentId, type, data)`. Create it in `BridgeApp`.",
            "Agent API: a `Capability` module like `TourModule` (register it in `capability/Capability.kt`), with an `EndpointSpec` for every call in `api/Endpoints.kt` and in `Endpoints.all`. Only the session's own agent may read it.",
            "Screen: a fragment like `ui/tour/TourFragment.kt` (setup: pick an agent and options, list what is missing; running: big buttons, what was said, End). Add it to `res/navigation/nav_main.xml`.",
            "Card: add it to `res/layout/fragment_applets.xml` and `AppletsFragment`, with a running chip.",
            "Tell agents: add an `ApiChanges` revision, a section in the live guide (`api/SelfDescription.kt`), and a \"What can Bridgit do?\" card in `ui/more/WhatCanData.kt`.",
            "Bump `versionName` / `versionCode` in `app/build.gradle.kts`, run `./gradlew testDebugUnitTest` (ApiDocsTest checks every endpoint is documented), install, and try it on the phone with a real agent.",
        ).forEach { append("<li>${md(it)}</li>") }
        append("</ol><p class=\"sub\">Rules every applet keeps: data goes only to the session's agent and only while it runs; the owner can always end it; nothing is kept afterwards except log lines.</p></section>")
    }

    private fun StringBuilder.card(c: Card) {
        val cls = when { c.stage.startsWith("Available") -> "now"; c.stage.startsWith("Next") -> "next"; else -> "" }
        append("<section class=\"card\" id=\"${slug(c.title)}\"><span class=\"chip $cls\">${h(c.stage)}</span><h3>${h(c.title)}</h3><p>${h(c.what)}</p>")
        if (c.options.isNotEmpty()) { append("<div class=\"label\">Options</div><ul>"); c.options.forEach { append("<li>${h(it)}</li>") }; append("</ul>") }
        append("<div class=\"label\">For example</div><p class=\"use\">${h(c.useCase)}</p>")
        val eps = c.endpoints.mapNotNull { find(it) }
        if (eps.isNotEmpty()) {
            append("<div class=\"label\">API</div>")
            eps.forEach { endpoint(it, short = true) }
        } else if (!c.stage.startsWith("Available")) {
            append("<div class=\"label\">API</div><p class=\"sub\">Not built yet. Spec ${h(c.ref)} in <code>docs/bridgit-spec-v2.md</code> describes the planned calls.</p>")
        }
        append("</section>")
    }

    private fun StringBuilder.endpoint(e: EndpointSpec, short: Boolean = false) {
        val anchor = if (short) "" else " id=\"${epAnchor(e)}\""
        append("<div class=\"ep\"$anchor><b><code>${e.method} ${h(e.fullPath)}</code></b>")
        if (!e.auth) append(" <span class=\"chip\">no key needed</span>")
        if (e.idempotent) append(" <span class=\"chip\">Idempotency-Key</span>")
        if (short) append(" <a href=\"#${epAnchor(e)}\">details</a>")
        append("<p>${md(e.summary)}.</p>")
        if (!short) {
            if (e.query.isNotEmpty()) { append("<table><tr><th>Query</th><th>Type</th><th>What</th></tr>"); e.query.forEach { p -> append("<tr><td><code>${p.name}</code></td><td>${p.type}</td><td>${md(p.description)}${p.enum?.let { " (${it.joinToString(", ")})" } ?: ""}</td></tr>") }; append("</table>") }
            if (e.body.isNotEmpty()) { append("<table><tr><th>Body</th><th>Type</th><th>What</th></tr>"); e.body.forEach { p -> append("<tr><td><code>${p.name}</code>${if (p.required) " *" else ""}</td><td>${p.type}</td><td>${md(p.description)}</td></tr>") }; append("</table>") }
            append("<p><b>Returns:</b> ${md(e.returns)}</p>")
            if (e.errors.isNotEmpty()) append("<p><b>Errors:</b> ${e.errors.joinToString(", ") { "<code>$it</code>" }}</p>")
        }
        append("<pre>${h(e.example)}</pre></div>")
    }
}
