package net.finleg.bridget.ui.interactions

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.finleg.bridget.BridgeApp
import net.finleg.bridget.R
import net.finleg.bridget.databinding.FragmentInteractionDetailBinding
import net.finleg.bridget.interact.IKind
import net.finleg.bridget.interact.IStatus
import net.finleg.bridget.interact.Interaction
import net.finleg.bridget.ui.common.Fmt
import net.finleg.bridget.ui.feedback.decodeFileThumb

/**
 * Interaction detail (UI-16): the header is drawn by Bridgit (agent, time, status), then the agent's title,
 * image, text and controls. Toolbar: Play aloud and Close. Opening a new notice marks it seen.
 */
class InteractionDetailFragment : Fragment() {
    private var _b: FragmentInteractionDetailBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp
    private val id get() = requireArguments().getLong("interactionId")

    private val drafts = Drafts()
    private var current: Interaction? = null
    private var shownKey: List<Any?>? = null
    private var seenAsked = false
    /** CAP-34: the agent's HTML card, created once per view and destroyed with it. */
    private var card: CardWebView? = null
    private var cardCrashed = false
    private var editingAnswer = false
    private var localAnswerInFlight = false
    private var closeAfterSend: kotlinx.coroutines.Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentInteractionDetailBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(b.toolbar) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top); insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.scroll) { v, insets ->
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime()).bottom); insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.cardBox) { v, insets ->
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime()).bottom); insets
        }
        editingAnswer = savedInstanceState?.getBoolean("editingAnswer") ?: false
        b.backToQuestions.setOnClickListener { backToQuestions() }
        b.toolbar.setNavigationOnClickListener { navigateBack() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = navigateBack()
        })
        b.toolbar.setOnMenuItemClickListener { item ->
            val i = current ?: return@setOnMenuItemClickListener false
            when (item.itemId) {
                R.id.interactions_play -> { SpeakHook.speak?.invoke(requireContext().applicationContext, InteractionUi.agentName(i), i.title, i.text); true }
                R.id.interactions_close -> { close(i); true }
                else -> false
            }
        }
        shownKey = null
        renderMenu()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var first = true
                app.interactions.changed.collectLatest {
                    if (!first) delay(300)
                    first = false
                    val i = withContext(Dispatchers.IO) { app.interactions.get(id) }
                    render(i)
                    if (i != null && !seenAsked && !InteractionUi.isQuestion(i) && i.status == IStatus.PENDING) {
                        seenAsked = true
                        val store = app.interactions
                        withContext(Dispatchers.IO) { runCatching { store.markSeen(i.id) } }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("editingAnswer", editingAnswer)
        super.onSaveInstanceState(outState)
    }

    private fun navigateBack() {
        if (current?.status == IStatus.ANSWERED && !editingAnswer) backToQuestions()
        else findNavController().navigateUp()
    }

    private fun backToQuestions() {
        val i = current ?: return
        if (i.status != IStatus.ANSWERED || localAnswerInFlight) return
        closeAfterSend?.cancel(); closeAfterSend = null
        editingAnswer = true
        // A card may replace its entire DOM with a confirmation. Reload the original question.
        card?.release(); card = null
        cardCrashed = false
        i.answer?.optJSONObject("values")?.let { values ->
            values.keys().forEach { key -> drafts.set(i.id, key, values.optString(key)) }
        }
        shownKey = null
        render(i)
        Snackbar.make(b.root, R.string.interactions_resubmit_hint, Snackbar.LENGTH_LONG).show()
    }

    private fun renderMenu() {
        val bb = _b ?: return
        val i = current
        bb.toolbar.menu.findItem(R.id.interactions_play)?.isVisible = i != null && SpeakHook.speak != null
        bb.toolbar.menu.findItem(R.id.interactions_close)?.isVisible = i != null && InteractionUi.waiting(i)
    }

    private fun render(i: Interaction?) {
        val bb = _b ?: return
        current = i
        bb.backToQuestions.isVisible = i?.status == IStatus.ANSWERED && !editingAnswer && InteractionUi.isQuestion(i)
        renderMenu()
        if (i == null) {
            bb.header.isVisible = false
            bb.banner.isVisible = false
            bb.image.isVisible = false
            bb.text.isVisible = false
            bb.controls.removeAllViews()
            bb.answer.isVisible = false
            bb.title.setText(R.string.interactions_not_found)
            shownKey = null
            return
        }
        // Rebuild only when something visible changed, so typing is never interrupted by other agents' updates.
        val key = InteractionUi.contentKey(i)
        if (key == shownKey) return
        val firstRender = shownKey == null
        shownKey = key
        val ctx = requireContext()
        val agent = InteractionUi.agentName(i)
        if (i.kind == IKind.HTML) { renderCard(i); return }

        bb.header.isVisible = true
        bb.avatar.setAgent(agent)
        bb.from.text = getString(R.string.interactions_from, agent)
        bb.time.text = Fmt.ago(i.createdAt) + " · " + Fmt.dateTime(i.createdAt)
        InteractionUi.styleStatus(bb.status, i.status)
        bb.header.contentDescription = bb.from.text.toString() + ", " + bb.time.text + ", " + InteractionUi.statusLabel(ctx, i.status)

        val banner = when (i.status) {
            IStatus.EXPIRED -> getString(if (i.kind == IKind.QUESTION) R.string.interactions_late_banner else R.string.interactions_expired_banner, agent)
            IStatus.CANCELLED -> getString(R.string.interactions_cancelled_banner, agent)
            IStatus.DISMISSED -> getString(R.string.interactions_closed_banner, agent)
            else -> null
        }
        bb.banner.isVisible = banner != null
        bb.banner.text = banner

        bb.title.text = i.title
        // Text from an agent is plain: never auto-linked (links only through link controls, CAP-41).
        bb.text.isVisible = !i.text.isNullOrBlank()
        bb.text.text = i.text

        if (firstRender) loadImage(i)

        InteractionUi.buildControls(bb.controls, i, drafts, { button, values -> answer(i, button, values) }, resubmission = editingAnswer) { c, anchor ->
            InteractionUi.openLink(anchor, c)
        }

        val summary = InteractionUi.answerSummary(ctx, i)
        bb.answer.isVisible = summary != null
        bb.answer.text = summary
    }

    /**
     * CAP-34: Bridgit's header ("From <Agent> · custom card", title, status), the agent's page in a bounded
     * frame, and Bridgit's toolbar with Dismiss under it. The page is loaded once; later refreshes only
     * update Bridgit's parts.
     */
    private fun renderCard(i: Interaction) {
        val bb = _b ?: return
        val ctx = requireContext()
        val agent = InteractionUi.agentName(i)
        bb.scroll.isVisible = false
        bb.cardBox.isVisible = true
        bb.cardAvatar.setAgent(agent)
        bb.cardFrom.text = getString(R.string.interactions_card_from, agent)
        bb.cardTitle.text = i.title
        InteractionUi.styleStatus(bb.cardStatus, i.status)
        bb.cardHeader.contentDescription = bb.cardFrom.text.toString() + ". " + i.title + ". " + InteractionUi.statusLabel(ctx, i.status)

        val html = i.html
        if (card == null && !cardCrashed && html != null) {
            val v = CardWebView(ctx, onRespond = { data -> answerCard(i, data) }, onCrash = ::cardCrashed)
            v.contentDescription = getString(R.string.interactions_card_desc, agent)
            bb.cardFrame.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            v.load(html)
            card = v
        }
        if (i.status == IStatus.ANSWERED && !editingAnswer && !localAnswerInFlight) card?.showConfirmation(i.answer,
            getString(R.string.interactions_confirmation_title), getString(R.string.interactions_confirmation_history, agent))
        else if (!i.status.acceptsAnswer(editingAnswer)) card?.stopAccepting()

        val note: CharSequence? = when {
            cardCrashed -> getString(R.string.interactions_card_crashed, agent)
            i.status == IStatus.ANSWERED -> InteractionUi.answerSummary(ctx, i)
            i.status == IStatus.EXPIRED -> getString(R.string.interactions_late_banner, agent)
            i.status == IStatus.CANCELLED -> getString(R.string.interactions_cancelled_banner, agent)
            i.status == IStatus.DISMISSED -> getString(R.string.interactions_closed_banner, agent)
            else -> null
        }
        bb.cardNote.isVisible = note != null
        bb.cardNote.text = note
        bb.cardDismiss.isVisible = InteractionUi.waiting(i)
        bb.cardDismiss.contentDescription = getString(R.string.interactions_card_dismiss_desc, agent)
        bb.cardDismiss.setOnClickListener { close(i) }
    }

    /** The card's renderer died: drop the WebView (Bridgit keeps running) and say so. */
    private fun cardCrashed() {
        cardCrashed = true
        card?.let { runCatching { it.release() } }
        card = null
        shownKey = null
        current?.let { render(it) }
    }

    /** The card's page called Bridget.respond(json): stored as `answer.data`, like a button answer. */
    private fun answerCard(i: Interaction, data: Any) {
        if (_b == null || !isAdded || localAnswerInFlight) return // the screen is going away; the card is released with it
        localAnswerInFlight = true
        val resubmission = editingAnswer
        val store = app.interactions
        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { store.answerCard(i.id, data, resubmission = resubmission) } }
            val bb = _b ?: return@launch
            r.onSuccess {
                editingAnswer = false
                render(it)
                card?.showConfirmation(it.answer, getString(R.string.interactions_confirmation_title),
                    getString(R.string.interactions_confirmation_detail, InteractionUi.agentName(i)), net.finleg.bridget.interact.CardConfirmation.CLOSE_DELAY_MS)
                localAnswerInFlight = false
                closeAfterSend?.cancel()
                closeAfterSend = owner.lifecycleScope.launch autoClose@ {
                    delay(net.finleg.bridget.interact.CardConfirmation.CLOSE_DELAY_MS)
                    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        if (_b != null && findNavController().currentDestination?.id == R.id.interactionDetail)
                            findNavController().navigateUp()
                        this@autoClose.cancel()
                    }
                }
            }.onFailure { e ->
                localAnswerInFlight = false
                card?.retryAnswer()
                Snackbar.make(bb.root, getString(R.string.interactions_answer_failed, e.message ?: e.javaClass.simpleName), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun loadImage(i: Interaction) {
        val name = i.image
        val bb = _b ?: return
        if (name == null) { bb.image.isVisible = false; return }
        val store = app.interactions
        val edge = resources.displayMetrics.widthPixels
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp: Bitmap? = withContext(Dispatchers.IO) { store.imageFile(name)?.let { runCatching { decodeFileThumb(it.path, edge) }.getOrNull() } }
            val v = _b ?: return@launch
            v.image.isVisible = bmp != null
            if (bmp == null) return@launch
            v.image.setImageBitmap(bmp)
            v.image.contentDescription = getString(R.string.interactions_image, InteractionUi.agentName(i)) + ". " + getString(R.string.interactions_image_hint)
            v.image.setOnClickListener { showBig(name, InteractionUi.agentName(i)) }
        }
    }

    /** Tap the picture to see it bigger. */
    private fun showBig(name: String, agent: String) {
        val store = app.interactions
        val edge = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { store.imageFile(name)?.let { runCatching { decodeFileThumb(it.path, edge) }.getOrNull() } } ?: return@launch
            if (_b == null) return@launch
            val iv = ImageView(requireContext()).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(bmp)
                contentDescription = getString(R.string.interactions_image, agent)
            }
            MaterialAlertDialogBuilder(requireContext()).setView(iv).setPositiveButton(R.string.interactions_ok, null).show()
        }
    }

    private fun answer(i: Interaction, button: String?, values: Map<String, String>) {
        if (localAnswerInFlight) return
        localAnswerInFlight = true
        val resubmission = editingAnswer
        val store = app.interactions
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { store.answer(i.id, button, values, resubmission = resubmission) } }
            localAnswerInFlight = false
            val bb = _b ?: return@launch
            r.onSuccess {
                drafts.clear(i.id)
                editingAnswer = false
                render(it)
                Snackbar.make(bb.root, getString(R.string.interactions_sent_snack, InteractionUi.agentName(i)), Snackbar.LENGTH_SHORT).show()
            }.onFailure { e ->
                Snackbar.make(bb.root, getString(R.string.interactions_answer_failed, e.message ?: e.javaClass.simpleName), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun close(i: Interaction) {
        val store = app.interactions
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { store.dismiss(i.id) } }
            val bb = _b ?: return@launch
            r.getOrNull()?.let { render(it) }
            Snackbar.make(bb.root, getString(R.string.interactions_closed_snack, InteractionUi.agentName(i)), Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        localAnswerInFlight = false
        closeAfterSend?.cancel(); closeAfterSend = null
        card?.let { runCatching { it.release() } }
        card = null
        super.onDestroyView(); _b = null; shownKey = null
    }
}
