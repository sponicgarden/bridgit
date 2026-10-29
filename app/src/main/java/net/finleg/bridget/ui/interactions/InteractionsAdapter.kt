package net.finleg.bridget.ui.interactions

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import net.finleg.bridget.R
import net.finleg.bridget.databinding.InteractionsRowBinding
import com.google.android.material.button.MaterialButton
import net.finleg.bridget.interact.Control
import net.finleg.bridget.interact.IKind
import net.finleg.bridget.ui.common.dp
import net.finleg.bridget.interact.Interaction
import net.finleg.bridget.ui.common.Fmt

/**
 * Interactions list rows (D26): one line when collapsed, text, image and inline controls when expanded.
 * Expanded state is kept per interaction id across refreshes; [drafts] keeps unsent answers.
 */
class InteractionsAdapter(
    private val drafts: Drafts,
    private val largeFont: Boolean,
    private val onOpen: (Interaction) -> Unit,
    private val onExpanded: (Interaction) -> Unit,
    private val onAnswer: (Interaction, String?, Map<String, String>) -> Unit,
    private val onClose: (Interaction) -> Unit,
    private val onLink: (Control.Link, View) -> Unit,
    private val loadThumb: (Interaction, ImageView) -> Unit,
) : ListAdapter<Interaction, InteractionsAdapter.Holder>(DIFF) {

    /** Rahul's choice per id; ids not in here use [InteractionUi.defaultExpanded]. */
    val overrides = HashMap<Long, Boolean>()

    fun isExpanded(i: Interaction) = overrides[i.id] ?: InteractionUi.defaultExpanded(i)

    class Holder(val b: InteractionsRowBinding) : RecyclerView.ViewHolder(b.root) {
        var closeActionId = View.NO_ID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val h = Holder(InteractionsRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        if (largeFont) {
            // UI-29: at large font sizes the time and status go under the title instead of squeezing it.
            h.b.textCol.orientation = LinearLayout.VERTICAL
            h.b.textCol.gravity = android.view.Gravity.START
            h.b.headline.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            (h.b.time.layoutParams as LinearLayout.LayoutParams).marginStart = 0
        }
        return h
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val i = getItem(position)
        val b = h.b
        val ctx = b.root.context
        val expanded = isExpanded(i)
        val headline = InteractionUi.headline(ctx, i)
        val status = InteractionUi.statusLabel(ctx, i.status)
        val ago = Fmt.ago(i.createdAt)

        b.avatar.setAgent(InteractionUi.agentName(i))
        b.headline.text = headline
        b.headline.maxLines = if (expanded) Int.MAX_VALUE else 1
        b.time.text = ago
        InteractionUi.styleStatus(b.status, i.status)

        // One focus stop with the full sentence (UI-29); expand state and actions announced.
        b.header.contentDescription = ctx.getString(R.string.interactions_row_a11y, headline.toString(), status, ago)
        ViewCompat.setStateDescription(b.header, ctx.getString(if (expanded) R.string.interactions_expanded else R.string.interactions_collapsed))
        b.header.setOnClickListener { onOpen(i) }
        ViewCompat.replaceAccessibilityAction(b.header, AccessibilityActionCompat.ACTION_EXPAND,
            if (expanded) null else ctx.getString(R.string.interactions_expand), if (expanded) null else { _, _ -> toggle(h); true })
        ViewCompat.replaceAccessibilityAction(b.header, AccessibilityActionCompat.ACTION_COLLAPSE,
            if (expanded) ctx.getString(R.string.interactions_collapse) else null, if (expanded) { _, _ -> toggle(h); true } else null)
        if (h.closeActionId != View.NO_ID) { ViewCompat.removeAccessibilityAction(b.header, h.closeActionId); h.closeActionId = View.NO_ID }
        if (i.status.open) h.closeActionId = ViewCompat.addAccessibilityAction(b.header, ctx.getString(R.string.interactions_close)) { _, _ -> onClose(i); true }

        b.chevron.rotation = if (expanded) 180f else 0f
        b.chevron.contentDescription = ctx.getString(if (expanded) R.string.interactions_collapse else R.string.interactions_expand)
        b.chevron.setOnClickListener { toggle(h) }

        b.body.isVisible = expanded
        if (!expanded) { b.controls.removeAllViews(); b.image.setImageDrawable(null); b.image.tag = null; return }

        b.text.isVisible = !i.text.isNullOrBlank()
        b.text.text = i.text
        b.text.setOnClickListener { onOpen(i) }
        b.text.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

        if (i.image != null) {
            b.image.isVisible = true
            b.image.contentDescription = ctx.getString(R.string.interactions_image, InteractionUi.agentName(i))
            b.image.setOnClickListener { onOpen(i) }
            loadThumb(i, b.image)
        } else {
            b.image.isVisible = false; b.image.setImageDrawable(null); b.image.tag = null
        }

        InteractionUi.buildControls(b.controls, i, drafts, { button, values -> onAnswer(i, button, values) }, onLink)
        if (i.kind == IKind.HTML || i.kind == IKind.TEXT) {
            // CAP-34 cards open in the detail screen; D26 text panels in the lower half.
            val open = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                setText(if (i.kind == IKind.HTML) R.string.interactions_card_open else R.string.interactions_text_open)
                minHeight = ctx.dp(48)
                contentDescription = text.toString() + ", " + headline
                setOnClickListener { onOpen(i) }
            }
            b.controls.addView(open, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = ctx.dp(8) })
            b.controls.isVisible = true
        }

        val summary = InteractionUi.answerSummary(ctx, i)
        b.answer.isVisible = summary != null
        b.answer.text = summary

        b.close.isVisible = i.status.open
        b.close.contentDescription = ctx.getString(R.string.interactions_close) + ", " + headline
        b.close.setOnClickListener { onClose(i) }
    }

    private fun toggle(h: Holder) {
        val pos = h.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION) return
        val i = getItem(pos)
        val now = !isExpanded(i)
        overrides[i.id] = now
        notifyItemChanged(pos)
        if (now) onExpanded(i)
    }

    public override fun getItem(position: Int): Interaction = super.getItem(position)

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Interaction>() {
            override fun areItemsTheSame(a: Interaction, b: Interaction) = a.id == b.id
            @android.annotation.SuppressLint("DiffUtilEquals") // contentKey returns a Kotlin List with structural equality.
            override fun areContentsTheSame(a: Interaction, b: Interaction) = InteractionUi.contentKey(a) == InteractionUi.contentKey(b)
        }
    }
}
