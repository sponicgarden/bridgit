package net.finleg.bridget.ui.interactions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import net.finleg.bridget.R
import net.finleg.bridget.databinding.InteractionsControlChoiceBinding
import net.finleg.bridget.databinding.InteractionsControlLinkBinding
import net.finleg.bridget.databinding.InteractionsControlTextBinding
import net.finleg.bridget.interact.Control
import net.finleg.bridget.interact.IKind
import net.finleg.bridget.interact.IStatus
import net.finleg.bridget.interact.Interaction
import net.finleg.bridget.interact.InteractionRules
import net.finleg.bridget.ui.common.Fmt
import net.finleg.bridget.ui.common.dp
import net.finleg.bridget.ui.common.themeColor
import com.google.android.material.R as M

/**
 * What Rahul has typed or picked but not sent yet, per interaction and control, so a list refresh or a
 * rebind never wipes a half-written answer.
 */
class Drafts {
    private val map = HashMap<String, String>()
    fun get(id: Long, control: String): String? = map["$id/$control"]
    fun set(id: Long, control: String, v: String?) { if (v.isNullOrEmpty()) map.remove("$id/$control") else map["$id/$control"] = v }
    fun clear(id: Long) { map.keys.removeAll { it.startsWith("$id/") } }

    /** The text and choice values for [i] (blank text left out), as InteractionStore.answer wants them. */
    fun values(i: Interaction): Map<String, String> = i.controls.mapNotNull { c ->
        when (c) {
            is Control.Text -> get(i.id, c.id)?.trim()?.takeIf { it.isNotEmpty() }?.let { c.id to it }
            is Control.Choice -> get(i.id, c.id)?.let { c.id to it }
            else -> null
        }
    }.toMap()
}

/** Shared pieces of the Interactions list and detail (D26, UI-15, UI-16). */
object InteractionUi {
    fun isQuestion(i: Interaction) = i.kind == IKind.QUESTION || i.kind == IKind.HTML

    /** Rows rebind only when something Rahul can see changed (Interaction holds JSON and controls compared by reference). */
    fun contentKey(i: Interaction) = listOf(i.status, i.seenAt, i.answeredAt, i.closedAt, i.title, i.text, i.image, i.agent, i.answer?.toString())

    fun agentName(i: Interaction) = i.agent.ifBlank { "Your agent" }

    /** Questions open by default; everything else starts collapsed to one line (D26). */
    fun defaultExpanded(i: Interaction) = isQuestion(i) && i.status.open

    fun statusLabel(ctx: Context, s: IStatus): String = ctx.getString(when (s) {
        IStatus.PENDING -> R.string.interactions_status_waiting
        IStatus.SEEN -> R.string.interactions_status_seen
        IStatus.ANSWERED -> R.string.interactions_status_answered
        IStatus.DISMISSED -> R.string.interactions_status_closed
        IStatus.EXPIRED -> R.string.interactions_status_expired
        IStatus.CANCELLED -> R.string.interactions_status_cancelled
    })

    /** Status chip: always a word, colour is never the only signal (UI-29). */
    fun styleStatus(v: TextView, s: IStatus) {
        val ctx = v.context
        val (bg, fg) = when (s) {
            IStatus.PENDING -> M.attr.colorPrimaryContainer to M.attr.colorOnPrimaryContainer
            IStatus.SEEN -> M.attr.colorSecondaryContainer to M.attr.colorOnSecondaryContainer
            IStatus.ANSWERED -> R.attr.colorSuccessContainer to R.attr.colorOnSuccessContainer
            else -> M.attr.colorSurfaceContainerHigh to M.attr.colorOnSurfaceVariant
        }
        v.background = GradientDrawable().apply { cornerRadius = ctx.dp(8).toFloat(); setColor(ctx.themeColor(bg)) }
        v.setTextColor(ctx.themeColor(fg))
        v.text = statusLabel(ctx, s)
    }

    /** "**Grok** asks: Pay this invoice?" or "**Claude**: Backup finished". */
    fun headline(ctx: Context, i: Interaction): CharSequence {
        val agent = agentName(i)
        val s = ctx.getString(when (i.kind) {
            IKind.HTML -> R.string.interactions_card_headline
            IKind.TEXT -> R.string.interactions_text_headline
            IKind.QUESTION -> R.string.interactions_asks
            IKind.NOTICE -> R.string.interactions_says
        }, agent, i.title)
        val out = SpannableStringBuilder(s)
        val at = s.indexOf(agent)
        if (at >= 0) out.setSpan(StyleSpan(Typeface.BOLD), at, at + agent.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return out
    }

    /** "You answered Pay · 14:03" plus one "Label: value" line per text or choice value; null if not answered. */
    fun answerSummary(ctx: Context, i: Interaction): CharSequence? {
        if (i.status != IStatus.ANSWERED) return null
        val a = i.answer
        val at = Fmt.time(i.answeredAt ?: i.closedAt ?: i.createdAt)
        val buttonId = a?.takeIf { it.has("button") && !it.isNull("button") }?.optString("button")
        val buttonLabel = buttonId?.let { id -> i.controls.firstOrNull { it is Control.Button && it.id == id }?.label ?: id }
        val out = SpannableStringBuilder(
            if (buttonLabel != null) ctx.getString(R.string.interactions_you_answered, buttonLabel, at)
            else ctx.getString(R.string.interactions_you_answered_plain, at))
        buttonLabel?.let { l ->
            val p = out.indexOf(l)
            if (p >= 0) out.setSpan(StyleSpan(Typeface.BOLD), p, p + l.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        // CAP-34: what an HTML card sent with Bridget.respond, shortened.
        if (a != null && a.has("data")) out.append("\n").append(ctx.getString(R.string.interactions_card_sent, a.opt("data").toString().take(200)))
        val values = a?.optJSONObject("values")
        values?.keys()?.forEach { k ->
            val label = i.controls.firstOrNull { it.id == k }?.label ?: k
            out.append("\n").append(label).append(": ").append(values.optString(k))
        }
        return out
    }

    /**
     * Builds the controls of [i] into [container]: text fields and choice chips, then the buttons (side by side
     * when there are one or two, stacked full width when more), then links with where they go drawn under them.
     * Inputs and buttons only while a question is open; links always.
     */
    fun buildControls(
        container: LinearLayout,
        i: Interaction,
        drafts: Drafts,
        onAnswer: (button: String?, values: Map<String, String>) -> Unit,
        onLink: (Control.Link, View) -> Unit,
    ) {
        container.removeAllViews()
        val ctx = container.context
        val inflater = LayoutInflater.from(ctx)
        val answerable = isQuestion(i) && i.status.open && i.kind == IKind.QUESTION
        val sendValues = { anchor: View ->
            val v = drafts.values(i)
            if (v.isEmpty()) Snackbar.make(anchor, R.string.interactions_need_answer, Snackbar.LENGTH_SHORT).show()
            else onAnswer(null, v)
        }
        if (answerable) {
            for (c in i.controls) when (c) {
                is Control.Text -> {
                    val t = InteractionsControlTextBinding.inflate(inflater, container, false)
                    t.fieldLayout.hint = c.label
                    c.placeholder?.let { t.fieldLayout.placeholderText = it }
                    t.field.setText(drafts.get(i.id, c.id) ?: "")
                    t.field.addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                        override fun afterTextChanged(s: Editable?) { drafts.set(i.id, c.id, s?.toString()) }
                    })
                    t.send.contentDescription = ctx.getString(R.string.interactions_send) + ", " + c.label
                    t.send.setOnClickListener { sendValues(it) }
                    container.addView(t.root)
                }
                is Control.Choice -> {
                    val ch = InteractionsControlChoiceBinding.inflate(inflater, container, false)
                    ch.label.text = c.label
                    val picked = drafts.get(i.id, c.id)
                    for (opt in c.options) {
                        val chip = inflater.inflate(R.layout.interactions_choice_chip, ch.options, false) as Chip
                        chip.id = View.generateViewId()
                        chip.text = opt
                        chip.isCheckable = true
                        chip.isChecked = opt == picked
                        chip.setOnCheckedChangeListener { _, on ->
                            if (on) drafts.set(i.id, c.id, opt) else if (drafts.get(i.id, c.id) == opt) drafts.set(i.id, c.id, null)
                        }
                        ch.options.addView(chip)
                    }
                    container.addView(ch.root)
                }
                else -> {}
            }
            val buttons = i.controls.filterIsInstance<Control.Button>()
            if (buttons.isNotEmpty()) {
                val stacked = buttons.size > 2
                val row = LinearLayout(ctx).apply {
                    orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = ctx.dp(8) }
                }
                buttons.forEachIndexed { n, c ->
                    val style = if (c.style == "primary") M.attr.materialButtonStyle else M.attr.materialButtonOutlinedStyle
                    val btn = MaterialButton(ctx, null, style).apply {
                        text = c.label
                        minHeight = ctx.dp(48)
                        setOnClickListener { onAnswer(c.id, drafts.values(i)) }
                    }
                    val lp = if (stacked) LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    else LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (n > 0) marginStart = ctx.dp(8) }
                    row.addView(btn, lp)
                }
                container.addView(row)
            } else if (i.controls.none { it is Control.Text }) {
                // Only choices: they still need a way to send.
                val send = MaterialButton(ctx, null, M.attr.materialButtonStyle).apply {
                    setText(R.string.interactions_send)
                    minHeight = ctx.dp(48)
                    setOnClickListener { sendValues(it) }
                }
                container.addView(send, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = ctx.dp(8) })
            }
        }
        for (c in i.controls.filterIsInstance<Control.Link>()) {
            val l = InteractionsControlLinkBinding.inflate(inflater, container, false)
            l.button.text = c.label
            l.shownAs.text = c.shownAs
            // The real destination is read out too, so TalkBack users see what Bridget drew (CAP-41).
            l.button.contentDescription = c.label + ". " + ctx.getString(R.string.interactions_link_hint, c.shownAs)
            l.button.setOnClickListener { onLink(c, it) }
            container.addView(l.root)
        }
        container.visibility = if (container.childCount > 0) View.VISIBLE else View.GONE
    }

    /**
     * Opens a link control from Rahul's tap (CAP-41): https/geo/mailto view, tel only dials (never calls),
     * a package launches that app. The target is checked against the allowlist again before it is opened.
     */
    fun openLink(anchor: View, c: Control.Link) {
        val ctx = anchor.context
        val pkg = c.pkg
        if (pkg != null) {
            // Needs a <queries> entry for launcher activities in the manifest, or Android 11+ hides other apps.
            val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (launch == null) {
                Snackbar.make(anchor, ctx.getString(R.string.interactions_not_installed, pkg), Snackbar.LENGTH_LONG).show()
                return
            }
            start(anchor, launch)
            return
        }
        val target = c.target
        if (target == null || InteractionRules.linkShownAs(target) == null) {
            Snackbar.make(anchor, R.string.interactions_no_app, Snackbar.LENGTH_LONG).show()
            return
        }
        val uri = Uri.parse(target)
        val intent = if (uri.scheme.equals("tel", ignoreCase = true)) Intent(Intent.ACTION_DIAL, uri) else Intent(Intent.ACTION_VIEW, uri)
        start(anchor, intent)
    }

    private fun start(anchor: View, intent: Intent) {
        try {
            anchor.context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(anchor, R.string.interactions_no_app, Snackbar.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Snackbar.make(anchor, R.string.interactions_no_app, Snackbar.LENGTH_LONG).show()
        }
    }
}
