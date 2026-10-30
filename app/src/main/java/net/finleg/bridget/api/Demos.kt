package net.finleg.bridget.api

/**
 * `GET /v1/demo`: a script for showing the owner what Bridgit can do, generated live like the guide.
 * The agent offers a demo, asks a few questions, then picks demos from the menu that fit the answers.
 * Every demo is listed so the owner sees what's possible; each is marked with whether it works right now.
 */
object Demos {
    /** One demo: [capability] decides whether it can run now, [calls] are the endpoints it uses. */
    data class Demo(val title: String, val capability: String, val goodFor: Set<String>, val pitch: String,
                    val steps: List<String>, val calls: List<String>)

    // Interests the owner can pick in question 2; demos are tagged with the ones they suit.
    private const val DECIDE = "decide"
    private const val SEE = "see"
    private const val VOICE = "voice"
    private const val PHONE = "phone"

    val all = listOf(
        Demo("Ask me with buttons", "interactions", setOf(DECIDE), "A question pops up on the phone; one tap answers it and you get the answer back.",
            listOf("POST /v1/interactions {\"kind\":\"question\",\"title\":\"<a real yes/no from their day>\",\"controls\":[{\"type\":\"button\",\"id\":\"yes\",\"label\":\"Yes\",\"style\":\"primary\"},{\"type\":\"button\",\"id\":\"no\",\"label\":\"Not now\"}]}",
                "GET /v1/interactions/{id}?wait=55 until it is answered, then tell them what you received.",
                "Point out they can reopen it from Interactions and change their answer; you'd get interaction.answered again with resubmission=true."),
            listOf("POST /v1/interactions", "GET /v1/interactions/{id}")),
        Demo("Pick from a list, or type a reply", "interactions", setOf(DECIDE), "Choices and free-text answers, for when yes/no isn't enough.",
            listOf("POST /v1/interactions with a choice control (2-8 options) and a text control, e.g. \"Where should we eat?\" with three real options and a \"Something else?\" box."),
            listOf("POST /v1/interactions")),
        Demo("A card you design", "interactions", setOf(DECIDE, SEE), "Your own HTML on their phone: a slider, a mini form, a chart, a checklist.",
            listOf("POST /v1/interactions {\"kind\":\"html\",\"title\":\"...\",\"html\":\"<your page>\"}. The page runs offline and answers with Bridget.respond({...}).",
                "Make it about them: a packing checklist for their trip, a 1-10 mood slider, a chart of something you're working on together."),
            listOf("POST /v1/interactions")),
        Demo("Live text panel", "interactions", setOf(DECIDE, SEE), "Watch you work: text scrolls on the phone as you write it.",
            listOf("POST /v1/interactions {\"kind\":\"text\",\"title\":\"<what you're doing>\"}",
                "POST /v1/text/stream {\"interaction_id\":\"<id>\",\"append\":\"...\"} a line at a time while you do a real task, then {\"done\":true}."),
            listOf("POST /v1/interactions", "POST /v1/text/stream")),
        Demo("Talk out loud", "speaker", setOf(VOICE), "The phone speaks your words, so they can hear you from across the room.",
            listOf("POST /v1/speaker/say {\"text\":\"<a short, friendly line>\"}",
                "Or add \"speak\":true to any interaction so a question is read aloud as it arrives."),
            listOf("POST /v1/speaker/say")),
        Demo("Camera control", "camera", setOf(SEE), "You take the photo yourself: pick the lens, zoom in, fire the flash, then say what you see.",
            listOf("Ask them to point the phone at something (a shelf, the view, their desk).",
                "POST /v1/camera/snapshot {\"camera\":\"rear\",\"zoom\":0.5}, then {\"zoom\":1}, then {\"zoom\":2} (or 5 on a telephoto phone). The zoom range is in /v1/capabilities after the first photo.",
                "Describe what changed between shots. Try {\"camera\":\"front\"} for a selfie, or \"flash\":true in a dark room."),
            listOf("POST /v1/camera/snapshot")),
        Demo("Show me something", "gallery", setOf(SEE), "They take a photo or screenshot the usual way; you see it the moment it's saved.",
            listOf("Note the newest image id from GET /v1/image/last?type=any, then ask them to snap a photo or screenshot of anything.",
                "GET /v1/image/last?type=any&since=<that id>&wait=55 returns it as soon as it lands. Describe it, or act on it (a receipt, a plant, an error message)."),
            listOf("GET /v1/image/last")),
        Demo("Read the text in a photo", "ocr", setOf(SEE), "Text recognised on the phone itself; the image never leaves it for a cloud service.",
            listOf("After a photo of a sign, label or page: POST /v1/ocr {\"gallery_id\":<id>} (or data_base64). Summarise or translate what it says."),
            listOf("POST /v1/ocr")),
        Demo("A short video", "video", setOf(SEE), "A few seconds of video from either camera.",
            listOf("POST /v1/camera/video {\"camera\":\"rear\",\"duration_seconds\":5}, then describe what moved."),
            listOf("POST /v1/camera/video")),
        Demo("Listen and transcribe", "mic", setOf(VOICE), "You hear the room, transcribed on the phone with no cloud speech service.",
            listOf("Tell them you're about to listen and for how long. POST /v1/mic/record {\"duration_seconds\":10,\"transcribe\":true}.",
                "Ask them to say a reminder or a to-do; read back what you heard and offer to act on it."),
            listOf("POST /v1/mic/record")),
        Demo("Say the wake word", "wakewords", setOf(VOICE), "Hands-free: they say their wake word and what follows reaches you, recognised on the phone.",
            listOf("GET /v1/listen. If wake_words is empty, ask them to add one for you in Bridgit (Features > Wake words), e.g. \"hey bridget\", and wait until it's listed.",
                "Ask them to say it and then a request, e.g. \"hey bridget, remind me to water the plants\". Keep your GET /v1/events?wait=55 loop running.",
                "When listen.heard arrives, read back its transcript and act on it (POST /v1/speaker/say makes the answer hands-free too). Point out the green mic dot and the Mute button in the notification."),
            listOf("GET /v1/listen", "GET /v1/events")),
        Demo("What's on my screen", "screen", setOf(SEE), "One look at what they're looking at, kept in memory and never saved.",
            listOf("Ask them to open something they want help with (an email, a settings page, a map). POST /v1/screen/capture, then help with it."),
            listOf("POST /v1/screen/capture")),
        Demo("Where am I", "location", setOf(PHONE), "Location with altitude, speed, heading and compass.",
            listOf("GET /v1/location. Tell them something useful about where they are: which way they're facing, how high up, the nearest landmark you know."),
            listOf("GET /v1/location")),
        Demo("Flashlight", "flashlight", setOf(PHONE), "Turn the torch on from your side; it always switches itself off.",
            listOf("POST /v1/flashlight {\"on\":true,\"auto_off_seconds\":10}"),
            listOf("POST /v1/flashlight")),
        Demo("Find my phone", "ring", setOf(PHONE, VOICE), "Ring the phone when it's lost in the sofa.",
            listOf("Only when they're nearby and expect it: POST /v1/phone/ring {\"duration_seconds\":5}. DELETE /v1/phone/ring stops it."),
            listOf("POST /v1/phone/ring")),
        Demo("Focus mode", "controls", setOf(PHONE), "Quiet the phone for a while; it puts things back on its own.",
            listOf("POST /v1/phone/dnd {\"mode\":\"priority\",\"duration_minutes\":1} so they see it switch on and back off. POST /v1/phone/undo restores it early."),
            listOf("POST /v1/phone/dnd", "POST /v1/phone/undo")),
        Demo("How's the phone doing", "phone", setOf(PHONE), "Battery, network, sound mode and whether the screen is on.",
            listOf("GET /v1/phone/status, and GET /v1/sensors?which=light,steps,orientation if sensors are on. Turn it into one helpful sentence."),
            listOf("GET /v1/phone/status")),
        Demo("City tour", "tour", setOf(SEE, VOICE), "A walking guide: they start it on the phone, and you send stories as they walk and answer questions.",
            listOf("The owner starts it in Bridgit's Tour screen and picks you; you can't start it. Explain it, then keep your /v1/events loop running so you get tour.started and tour.tick."),
            listOf("GET /v1/tour")),
    )

    /** [allowed] says whether the owner lets this agent use a capability (per-agent grants). */
    fun build(agentName: String, caps: List<CapInfo>, allowed: (String) -> Boolean): String {
        val byId = caps.associateBy { it.id }
        fun state(d: Demo): String? {
            val c = byId[d.capability] ?: return "not on this phone's version of Bridgit"
            return when {
                !allowed(d.capability) -> "the owner hasn't allowed you to use ${c.title}; they can in your agent page in Bridgit"
                !c.enabled -> "${c.title} is switched off; the owner can switch it on in Bridgit"
                !c.ready -> "${c.title} isn't ready: ${c.blockedReason}"
                else -> null
            }
        }
        val ready = all.filter { state(it) == null }
        val sb = StringBuilder()
        sb.appendLine("# Show the owner what Bridgit can do")
        sb.appendLine()
        sb.appendLine("You are $agentName. This page is a short script for giving the owner a hands-on tour of Bridgit, built from what's switched on right now.")
        sb.appendLine("The goal: they finish knowing the handful of things you can do through their phone that matter to *them*.")
        sb.appendLine()
        sb.appendLine("## 1. Offer it")
        sb.appendLine("Once you're connected, ask once: \"Want me to show you a few things I can do with your phone now? It takes about two minutes.\"")
        sb.appendLine("If they say no, drop it; they can ask for it later. Don't run any demo they haven't agreed to.")
        sb.appendLine()
        sb.appendLine("## 2. Ask a few questions first")
        sb.appendLine("Ask these, one at a time, and keep it light. Skip any you already know the answer to.")
        sb.appendLine("1. **Where's the phone?** In your hand / on the desk next to you / somewhere else in the room.")
        sb.appendLine("2. **What would help you most?** Quick decisions and approvals / seeing and reading things / hands-free voice and sound / knowing what the phone is doing.")
        sb.appendLine("3. **How long?** A quick taste (3 demos) / the full tour.")
        sb.appendLine("4. **Anything on your plate today?** A decision, a trip, a task. Use it as the content of the demos so they're about their life, not placeholders.")
        sb.appendLine()
        if (byId["interactions"]?.usable == true && allowed("interactions")) {
            sb.appendLine("If the owner isn't in your chat (you run in the background), ask these on the phone instead: one `POST /v1/interactions` question per item, " +
                "with a `choice` control holding the options. That's the first demo already.")
            sb.appendLine()
        }
        sb.appendLine("## 3. Pick the demos")
        sb.appendLine("- In their hand: anything. On the desk: prefer camera, screen, speaker and cards. Elsewhere in the room: lead with speaker, flashlight and find my phone.")
        sb.appendLine("- Match question 2 to each demo's **Good for**. Quick taste: three demos, one of them a question card if it's on. Full tour: every ready demo that fits, best first.")
        sb.appendLine("- Before each capture (camera, mic, screen, location), say what you're about to do and wait for an OK. Every capture shows on their phone and is logged under your name.")
        sb.appendLine("- One demo at a time: run it, say in a sentence what just happened and what they could use it for, then ask if they want the next one.")
        sb.appendLine("- Send an `Idempotency-Key` header on every POST. If a call fails, explain the error in plain words and move on.")
        sb.appendLine()
        sb.appendLine("## 4. Wrap up")
        sb.appendLine("Finish with two or three ideas for using what they saw in their own routine, based on their answers. If a demo they'd like is switched off, " +
            "tell them which switch to turn on. Then offer to set one of those ideas up for real.")
        sb.appendLine()
        sb.appendLine("## Demos")
        sb.appendLine()
        sb.appendLine("Ready now: " + (if (ready.isEmpty()) "none yet. Ask the owner to switch on a few features in Bridgit first." else ready.joinToString("; ") { it.title }) + ".")
        for (d in all) {
            sb.appendLine()
            val st = state(d)
            sb.appendLine("### ${d.title}" + if (st == null) "" else " (not available: $st)")
            sb.appendLine(d.pitch)
            sb.appendLine("Good for: " + d.goodFor.joinToString(", ") { INTEREST_LABELS.getValue(it) } + ".")
            d.steps.forEach { sb.appendLine("- $it") }
        }
        return sb.toString()
    }

    private val INTEREST_LABELS = mapOf(DECIDE to "decisions and approvals", SEE to "seeing and reading",
        VOICE to "voice and sound", PHONE to "the phone itself")
}
