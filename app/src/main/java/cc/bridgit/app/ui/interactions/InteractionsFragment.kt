package cc.bridgit.app.ui.interactions

import android.graphics.Bitmap
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.databinding.FragmentInteractionsBinding
import cc.bridgit.app.interact.IKind
import cc.bridgit.app.interact.IStatus
import cc.bridgit.app.interact.Interaction
import cc.bridgit.app.ui.feedback.decodeFileThumb
import cc.bridgit.app.ui.feedback.handleFeedbackMenu

/**
 * Interactions tab (D26, UI-15): every question and notice from the agents, open ones first, then newest.
 * Rows expand or collapse to one line; open ones can be answered inline and closed (button or swipe left).
 * Owner, 30 Oct 2026: two subtabs under the toolbar, Inbox (this list) and Applets (AppletsFragment, a child).
 */
class InteractionsFragment : Fragment() {
    private var _b: FragmentInteractionsBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp

    private val drafts = Drafts()
    private val thumbs = LruCache<String, Bitmap>(24)
    private var all: List<Interaction> = emptyList()
    private var adapter: InteractionsAdapter? = null
    private var restoredOverrides: Map<Long, Boolean> = emptyMap()
    private var tab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { s ->
            val ids = s.getLongArray(KEY_IDS) ?: return@let
            val open = s.getBooleanArray(KEY_OPEN) ?: return@let
            restoredOverrides = ids.indices.associate { ids[it] to open[it] }
        }
        tab = savedInstanceState?.getInt(KEY_TAB) ?: 0
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentInteractionsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(b.toolbar) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top); insets
        }
        for ((label, kind) in listOf("Files" to "files", "Shared" to "shares")) {
            b.chips.addView(com.google.android.material.chip.Chip(requireContext()).apply {
                text = label; isCheckable = false
                setOnClickListener { startActivity(android.content.Intent(requireContext(), cc.bridgit.app.planned.OwnerToolsActivity::class.java).putExtra("filter", kind)) }
            })
        }
        b.toolbar.inflateMenu(R.menu.feedback_overflow) // Report a bug / Request a feature (FBK-01)
        b.toolbar.setOnMenuItemClickListener { handleFeedbackMenu(it, if (tab == TAB_APPLETS) "Applets" else "Interactions") }
        b.tabs.getTabAt(tab)?.select()
        showTab()
        b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) { tab = t.position; showTab() }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })

        val a = InteractionsAdapter(
            drafts = drafts,
            largeFont = resources.configuration.fontScale >= 1.3f,
            onOpen = ::open,
            onExpanded = ::markSeenIfNotice,
            onAnswer = ::answer,
            onClose = ::close,
            onLink = { c, anchor -> InteractionUi.openLink(anchor, c) },
            loadThumb = ::loadThumb,
        )
        a.overrides.putAll(restoredOverrides)
        adapter = a
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = a
        (b.list.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        // Swipe left to close (D26). Only rows still waiting (open, or expired unanswered questions) can be swiped.
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                val pos = vh.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION || pos >= a.itemCount) return 0
                return if (InteractionUi.waiting(a.getItem(pos))) ItemTouchHelper.LEFT else 0
            }
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val i = a.getItem(pos)
                a.notifyItemChanged(pos) // bring the row back; it stays in the list as Closed
                close(i)
            }
        }).attachToRecyclerView(b.list)

        b.chips.setOnCheckedStateChangeListener { _, _ -> render() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var first = true
                app.interactions.changed.collectLatest {
                    if (!first) delay(300) // debounce bursts (an answer bumps more than once)
                    first = false
                    all = withContext(Dispatchers.IO) { app.interactions.listAll() }
                    render()
                }
            }
        }
    }

    private fun showTab() {
        val bb = _b ?: return
        bb.inbox.isVisible = tab != TAB_APPLETS
        bb.applets.isVisible = tab == TAB_APPLETS
    }

    private fun render() {
        val bb = _b ?: return
        val shown = when (bb.chips.checkedChipId) {
            R.id.chip_questions -> all.filter { InteractionUi.isQuestion(it) }
            R.id.chip_notices -> all.filter { !InteractionUi.isQuestion(it) }
            R.id.chip_open -> all.filter { InteractionUi.waiting(it) }
            else -> all
        }
        adapter?.submitList(shown)
        bb.empty.isVisible = shown.isEmpty()
        bb.list.isVisible = shown.isNotEmpty()
        if (shown.isEmpty()) {
            val chip = bb.chips.checkedChipId
            val (title, body) = when {
                all.isEmpty() || chip == R.id.chip_all || chip == View.NO_ID -> getString(R.string.interactions_empty_title) to getString(R.string.interactions_empty_body)
                chip == R.id.chip_questions -> null to getString(R.string.interactions_empty_questions)
                chip == R.id.chip_notices -> null to getString(R.string.interactions_empty_notices)
                else -> null to getString(R.string.interactions_empty_open)
            }
            bb.emptyTitle.isVisible = title != null
            bb.emptyTitle.text = title
            bb.emptyBody.text = body
        }
    }

    private fun open(i: Interaction) {
        // D26: a text interaction opens (or reopens) the live text panel instead of a detail screen.
        if (i.kind == IKind.TEXT) { (activity as? cc.bridgit.app.ui.MainActivity)?.showTextPanel(i.id); return }
        findNavController().navigate(R.id.interactionDetail, bundleOf("interactionId" to i.id))
    }

    /** Expanding or opening a notice that is still new marks it seen (the agent gets interaction.seen). */
    private fun markSeenIfNotice(i: Interaction) {
        if (InteractionUi.isQuestion(i) || i.status != IStatus.PENDING) return
        val store = app.interactions
        viewLifecycleOwner.lifecycleScope.launch { withContext(Dispatchers.IO) { runCatching { store.markSeen(i.id) } } }
    }

    private fun answer(i: Interaction, button: String?, values: Map<String, String>) {
        val store = app.interactions
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { store.answer(i.id, button, values) } }
            val bb = _b ?: return@launch
            r.onSuccess {
                drafts.clear(i.id)
                adapter?.overrides?.put(i.id, true) // keep "You answered ..." on screen after the refresh
                Snackbar.make(bb.root, getString(R.string.interactions_sent_snack, InteractionUi.agentName(i)), Snackbar.LENGTH_SHORT).show()
            }.onFailure { e ->
                Snackbar.make(bb.root, getString(R.string.interactions_answer_failed, e.message ?: e.javaClass.simpleName), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun close(i: Interaction) {
        val store = app.interactions
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { store.dismiss(i.id) } }
            drafts.clear(i.id)
            val bb = _b ?: return@launch
            Snackbar.make(bb.root, getString(R.string.interactions_closed_snack, InteractionUi.agentName(i)), Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun loadThumb(i: Interaction, into: ImageView) {
        val name = i.image ?: return
        into.tag = name
        thumbs.get(name)?.let { into.setImageBitmap(it); return }
        into.setImageDrawable(null)
        val store = app.interactions
        val edge = (240 * resources.displayMetrics.density).toInt()
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { store.imageFile(name)?.let { runCatching { decodeFileThumb(it.path, edge) }.getOrNull() } }
            if (bmp != null) thumbs.put(name, bmp)
            if (into.tag == name) {
                if (bmp != null) into.setImageBitmap(bmp) else into.isVisible = false
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val o = adapter?.overrides ?: restoredOverrides
        outState.putLongArray(KEY_IDS, o.keys.toLongArray())
        outState.putBooleanArray(KEY_OPEN, o.keys.map { o[it] == true }.toBooleanArray())
        outState.putInt(KEY_TAB, tab)
    }

    override fun onDestroyView() {
        adapter?.let { restoredOverrides = HashMap(it.overrides) }
        adapter = null
        super.onDestroyView()
        _b = null
    }

    private companion object {
        const val KEY_IDS = "interactions.expanded.ids"
        const val KEY_OPEN = "interactions.expanded.open"
        const val KEY_TAB = "interactions.tab"
        const val TAB_APPLETS = 1
    }
}
