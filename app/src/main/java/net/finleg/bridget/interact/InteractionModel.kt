package net.finleg.bridget.interact

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Interactions (8.13, D26): what agents send Rahul. Pure Kotlin; validation is unit-tested. */
enum class IKind(val id: String) {
    NOTICE("notice"), QUESTION("question"), HTML("html"), TEXT("text");
    companion object { fun of(id: String?) = values().firstOrNull { it.id == id } }
}

enum class IStatus(val id: String) {
    PENDING("pending"), SEEN("seen"), ANSWERED("answered"), DISMISSED("dismissed"), EXPIRED("expired"), CANCELLED("cancelled");
    val open get() = this == PENDING || this == SEEN
    companion object { fun of(id: String?) = values().firstOrNull { it.id == id } }
}

sealed class Control(val id: String, val label: String) {
    class Button(id: String, label: String, val style: String) : Control(id, label)
    class Text(id: String, label: String, val placeholder: String?) : Control(id, label)
    class Choice(id: String, label: String, val options: List<String>) : Control(id, label)
    /** [target] is a URL (https/geo/tel/mailto) or null when [pkg] names an app. [shownAs] is drawn by Bridget under the button. */
    class Link(id: String, label: String, val target: String?, val pkg: String?, val shownAs: String) : Control(id, label)

    fun toJson(): JSONObject = JSONObject().put("id", id).put("label", label).apply {
        when (val c = this@Control) {
            is Button -> put("type", "button").put("style", c.style)
            is Text -> put("type", "text").put("placeholder", c.placeholder ?: JSONObject.NULL)
            is Choice -> put("type", "choice").put("options", JSONArray(c.options))
            is Link -> put("type", "link").put("url", c.target ?: JSONObject.NULL).put("package", c.pkg ?: JSONObject.NULL).put("shown_as", c.shownAs)
        }
    }

    companion object {
        fun fromJson(o: JSONObject): Control = when (o.getString("type")) {
            "button" -> Button(o.getString("id"), o.getString("label"), o.optString("style", "normal"))
            "text" -> Text(o.getString("id"), o.getString("label"), o.optString("placeholder").ifEmpty { null }.takeIf { !o.isNull("placeholder") })
            "choice" -> Choice(o.getString("id"), o.getString("label"), o.getJSONArray("options").let { a -> (0 until a.length()).map { a.getString(it) } })
            else -> Link(o.getString("id"), o.getString("label"), o.optString("url").takeIf { !o.isNull("url") && it.isNotEmpty() },
                o.optString("package").takeIf { !o.isNull("package") && it.isNotEmpty() }, o.optString("shown_as"))
        }
    }
}

/** A validated request, before it is stored. Image bytes are decoded by the API module. */
data class Draft(
    val kind: IKind, val title: String, val text: String?, val controls: List<Control>, val priority: String,
    val speak: Boolean, val expiresInS: Long, val imageGalleryId: Long?, val imageBase64: String?, val html: String?,
)

data class Interaction(
    val id: Long, val agentId: String, val agent: String, val kind: IKind, val title: String, val text: String?,
    val image: String?, val controls: List<Control>, val html: String?, val priority: String, val speak: Boolean,
    val status: IStatus, val answer: JSONObject?, val createdAt: Long, val expiresAt: Long,
    val seenAt: Long?, val answeredAt: Long?, val closedAt: Long?, val streamText: String = "", val streamDone: Boolean = false,
) {
    fun toJson(iso: (Long) -> String): JSONObject = JSONObject()
        .put("interaction_id", id).put("kind", kind.id).put("status", status.id).put("title", title)
        .put("priority", priority).put("created_at", iso(createdAt)).put("expires_at", iso(expiresAt))
        .put("seen_at", seenAt?.let(iso) ?: JSONObject.NULL)
        .put("answer", answer ?: JSONObject.NULL).put("answered_at", answeredAt?.let(iso) ?: JSONObject.NULL)
        .put("closed_at", closedAt?.let(iso) ?: JSONObject.NULL)
}

class InteractionError(val code: String, message: String) : Exception(message)

object InteractionRules {
    const val TITLE_MAX = 80
    const val TEXT_MAX = 2000
    const val IMAGE_MAX_BYTES = 2 * 1024 * 1024
    const val HTML_MAX_BYTES = 256 * 1024
    const val MAX_CONTROLS = 6
    const val LABEL_MAX = 40
    const val MIN_EXPIRES_S = 60L
    const val MAX_EXPIRES_S = 7L * 24 * 3600
    const val DEFAULT_EXPIRES_S = 24L * 3600
    const val OPEN_QUESTIONS_PER_AGENT = 5
    const val NOTICES_PER_HOUR = 30
    const val ANSWER_TEXT_MAX = 2000
    private val ID_RE = Regex("^[A-Za-z0-9_-]{1,32}$")
    private val PKG_RE = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

    private fun bad(msg: String): Nothing = throw InteractionError("invalid_parameter", msg)

    /** Parses and checks a POST /v1/interactions body (CAP-31). */
    fun parse(b: JSONObject): Draft {
        val kind = IKind.of(b.optString("kind")) ?: bad("kind must be notice, question, html or text.")
        // CAP-34: the raw HTML size is checked before anything else is looked at.
        val html = if (kind == IKind.HTML) CardRules.checkHtml(b.opt("html")) else null
        if (kind != IKind.HTML && b.has("html") && !b.isNull("html")) bad("html is only for kind html.")
        val title = b.optString("title", "").trim()
        if (title.isEmpty()) bad("title is required.")
        if (title.length > TITLE_MAX) bad("title is limited to $TITLE_MAX characters.")
        val text = b.optString("text", "").takeIf { b.has("text") && !b.isNull("text") }?.trim()
        if (text != null && text.length > TEXT_MAX) bad("text is limited to $TEXT_MAX characters.")
        val arr = b.optJSONArray("controls") ?: JSONArray()
        if (arr.length() > MAX_CONTROLS) bad("At most $MAX_CONTROLS controls.")
        val ids = HashSet<String>()
        val controls = (0 until arr.length()).map { i ->
            val o = arr.optJSONObject(i) ?: bad("controls[$i] must be an object.")
            val id = o.optString("id", "")
            if (!ID_RE.matches(id)) bad("controls[$i].id must be 1-32 letters, digits, - or _.")
            if (!ids.add(id)) bad("controls ids must be unique (\"$id\" twice).")
            val label = o.optString("label", "").trim()
            if (label.isEmpty() || label.length > LABEL_MAX) bad("controls[$i].label must be 1-$LABEL_MAX characters.")
            when (o.optString("type")) {
                "button" -> Control.Button(id, label, o.optString("style", "normal").let { if (it == "primary") "primary" else "normal" })
                "text" -> Control.Text(id, label, o.optString("placeholder", "").take(LABEL_MAX).ifEmpty { null })
                "choice" -> {
                    val opts = o.optJSONArray("options") ?: bad("controls[$i].options is required for a choice.")
                    if (opts.length() !in 2..8) bad("controls[$i].options must have 2 to 8 entries.")
                    Control.Choice(id, label, (0 until opts.length()).map { opts.optString(it, "").trim().take(LABEL_MAX).ifEmpty { bad("controls[$i].options can't be empty.") } })
                }
                "link" -> link(o, i, id, label)
                else -> bad("controls[$i].type must be button, text, choice or link.")
            }
        }
        when (kind) {
            IKind.NOTICE -> if (controls.any { it !is Control.Link }) bad("A notice can only have link controls.")
            IKind.QUESTION -> {
                if (controls.isEmpty()) bad("A question needs 1 to $MAX_CONTROLS controls.")
                if (controls.none { it is Control.Button || it is Control.Text || it is Control.Choice })
                    bad("A question needs at least one button, text or choice control.")
            }
            IKind.HTML -> if (controls.isNotEmpty()) bad("An html card draws its own controls; leave controls out and call Bridget.respond(json).")
            IKind.TEXT -> if (controls.isNotEmpty()) bad("A text panel has no controls; stream text with POST /v1/text/stream.")
        }
        val priority = b.optString("priority", "normal")
        if (priority != "normal" && priority != "urgent") bad("priority must be normal or urgent.")
        val exp = if (b.has("expires_in_seconds")) (b.opt("expires_in_seconds") as? Number)?.toLong()
            ?: bad("expires_in_seconds must be a number.") else DEFAULT_EXPIRES_S
        if (exp !in MIN_EXPIRES_S..MAX_EXPIRES_S) bad("expires_in_seconds must be 60 to 604800 (7 days).")
        val img = b.optJSONObject("image")
        val galleryId = img?.takeIf { it.has("gallery_id") }?.let { it.optString("gallery_id").toLongOrNull() ?: bad("image.gallery_id must be a gallery id.") }
        val b64 = img?.takeIf { it.has("data_base64") }?.optString("data_base64")
        if (b64 != null && b64.length > IMAGE_MAX_BYTES * 4 / 3 + 4) bad("image is limited to 2 MB.")
        if (img != null && galleryId == null && b64 == null) bad("image needs gallery_id or data_base64.")
        if (img != null && (kind == IKind.HTML || kind == IKind.TEXT)) bad("image is for notices and questions; put pictures in the card as data:image/* instead.")
        val speak = b.optBoolean("speak", false)
        return Draft(kind, title, text, controls, priority, speak, exp, galleryId, b64, html)
    }

    /** CAP-31 link allowlist: https, geo, tel (dial only), mailto, or an app package. Everything else is refused. */
    fun link(o: JSONObject, i: Int, id: String, label: String): Control.Link {
        val pkg = o.optString("package", "").trim()
        if (pkg.isNotEmpty()) {
            if (!PKG_RE.matches(pkg) || pkg.length > 120) bad("controls[$i].package must be an Android package name.")
            return Control.Link(id, label, null, pkg, pkg)
        }
        val url = o.optString("url", "").trim()
        if (url.isEmpty() || url.length > 2000) bad("controls[$i] needs url or package.")
        val shown = linkShownAs(url) ?: bad("controls[$i].url must be https:, geo:, tel: or mailto:.")
        return Control.Link(id, label, url, null, shown)
    }

    /** What Bridget draws under a link button, or null if the URL isn't allowed. */
    fun linkShownAs(url: String): String? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        return when (u.scheme?.lowercase()) {
            "https" -> u.host?.lowercase()?.takeIf { it.isNotEmpty() && u.userInfo == null }
            "geo" -> "Map: " + url.removePrefix("geo:").take(40)
            "tel" -> "Phone: " + url.removePrefix("tel:").take(30)
            "mailto" -> "Email: " + url.removePrefix("mailto:").substringBefore('?').take(40)
            else -> null
        }
    }

    /**
     * Checks Rahul's answer against the question's controls: [button] must be one of its buttons
     * (or null when a text/choice is enough); values only for its text/choice controls.
     */
    fun checkAnswer(controls: List<Control>, button: String?, values: Map<String, String>): JSONObject {
        if (button != null && controls.none { it is Control.Button && it.id == button }) throw InteractionError("invalid_parameter", "No button \"$button\".")
        val out = JSONObject()
        for ((k, v) in values) {
            when (val c = controls.firstOrNull { it.id == k }) {
                is Control.Text -> out.put(k, v.take(ANSWER_TEXT_MAX))
                is Control.Choice -> if (v in c.options) out.put(k, v) else throw InteractionError("invalid_parameter", "\"$v\" is not an option of $k.")
                else -> throw InteractionError("invalid_parameter", "No text or choice control \"$k\".")
            }
        }
        if (button == null && out.length() == 0) throw InteractionError("invalid_parameter", "An answer needs a button or a value.")
        return JSONObject().put("button", button ?: JSONObject.NULL).put("values", out)
    }
}

/** D26 live text panel: limits and trimming. Pure, unit-tested. */
object TextPanelRules {
    /** Most text one POST /v1/text/stream may add, in UTF-8 bytes. */
    const val APPEND_MAX_BYTES = 16 * 1024
    /** The panel keeps the last this many characters. */
    const val KEEP_CHARS = 200 * 1024
    /** POST /v1/text/stream calls per agent per second. */
    const val CALLS_PER_SECOND = 20
    /** How far [keepTail] looks for a line break before it cuts mid-line instead. */
    const val LINE_SEARCH = 4096

    /**
     * The last [max] characters of [text], dropped from the front. The cut moves forward to just after the next
     * line break when there is one within [LINE_SEARCH] characters, so the panel rarely starts mid-line; it never splits a
     * surrogate pair.
     */
    fun keepTail(text: String, max: Int = KEEP_CHARS): String {
        if (text.length <= max) return text
        var cut = text.length - max
        if (text[cut - 1] == '\n') return text.substring(cut) // already at the start of a line
        val nl = text.indexOf('\n', cut)
        if (nl >= 0 && nl < text.length - 1 && nl - cut <= LINE_SEARCH) cut = nl + 1
        else if (Character.isLowSurrogate(text[cut])) cut++
        return text.substring(cut)
    }

    /** Checks one append; returns an error message or null. */
    fun checkAppend(append: String): String? =
        if (append.toByteArray(Charsets.UTF_8).size > APPEND_MAX_BYTES) "append is limited to 16 KB per call." else null
}

