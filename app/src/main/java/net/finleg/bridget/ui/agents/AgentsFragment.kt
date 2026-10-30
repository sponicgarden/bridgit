package net.finleg.bridget.ui.agents

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.finleg.bridget.BridgeApp
import net.finleg.bridget.R
import net.finleg.bridget.core.Agent
import net.finleg.bridget.core.AgentRegistry
import net.finleg.bridget.databinding.AgentsRowBinding
import net.finleg.bridget.databinding.FragmentAgentsBinding
import net.finleg.bridget.ui.common.modelText
import net.finleg.bridget.ui.common.Fmt
import net.finleg.bridget.ui.common.themeColor
import net.finleg.bridget.ui.feedback.handleFeedbackMenu

/** Agents list (UI-12, ui-spec 5.3): one card per agent, Add agent FAB, empty state. */
class AgentsFragment : Fragment() {
    private var _b: FragmentAgentsBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp
    private lateinit var adapter: Adapter
    private var rows: List<Row> = emptyList()

    /** Registry changes arrive per request (recordUse); coalesce them so a busy agent doesn't flood the log. */
    private val refreshSoon = Runnable { if (_b != null) refresh() }
    private val agentsChanged: () -> Unit = { view?.let { v -> v.removeCallbacks(refreshSoon); v.postDelayed(refreshSoon, 500) } }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentAgentsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(b.toolbar) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top); insets
        }
        b.toolbar.inflateMenu(R.menu.feedback_overflow) // Report a bug / Request a feature (FBK-01)
        b.toolbar.setOnMenuItemClickListener { handleFeedbackMenu(it, "Agents") }
        adapter = Adapter { a -> findNavController().navigate(R.id.agentDetail, bundleOf("agentId" to a.id)) }
        b.list.adapter = adapter
        b.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 4 && b.fab.isExtended) b.fab.shrink()
                else if (dy < -4 && !b.fab.isExtended) b.fab.extend()
            }
        })
        b.fab.setOnClickListener { addAgent() }
        b.emptyAdd.setOnClickListener { addAgent() }

        app.agents.addListener(agentsChanged)
        refresh()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // New log entries change "N today"; a slow tick keeps "2 min ago" and the dot fresh.
                launch { app.log.latestId.collectLatest { delay(500); refresh() } }
                launch { while (true) { delay(30_000); refresh() } }
            }
        }
    }

    private fun addAgent() {
        if (app.agents.count >= AgentRegistry.MAX_AGENTS) {
            Snackbar.make(b.root, R.string.agents_full, Snackbar.LENGTH_LONG).show()
            return
        }
        findNavController().navigate(R.id.addAgent)
    }

    private fun refresh() {
        val agents = app.agents.all()
        // Show the cards straight away with the last known counts, then fill in today's numbers.
        val known = rows.associate { it.agent.id to it.today }
        show(agents.map { Row(it, known[it.id] ?: 0, System.currentTimeMillis()) })
        viewLifecycleOwner.lifecycleScope.launch {
            val counted = withContext(Dispatchers.IO) {
                agents.map { Row(it, AgentStats.requestsToday(app.log, it.id), System.currentTimeMillis()) }
            }
            if (_b != null) show(counted)
        }
    }

    private fun show(list: List<Row>) {
        rows = list
        adapter.submitList(list)
        val empty = list.isEmpty()
        b.empty.isVisible = empty
        b.list.isVisible = !empty
        if (empty) b.fab.hide() else b.fab.show()
    }

    override fun onDestroyView() {
        app.agents.removeListener(agentsChanged)
        view?.removeCallbacks(refreshSoon)
        super.onDestroyView()
        _b = null
    }

    /** [now] is part of the row so "2 min ago" and the dot are rebound on each tick. */
    data class Row(val agent: Agent, val today: Int, val now: Long)

    private class Adapter(private val onClick: (Agent) -> Unit) : ListAdapter<Row, Adapter.Holder>(DIFF) {
        class Holder(val b: AgentsRowBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(AgentsRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val r = getItem(position)
            val a = r.agent
            val ctx = h.b.root.context
            h.b.avatar.setAgent(a.name)
            h.b.name.text = a.name
            h.b.connection.text = a.modelText(ctx)
            h.b.developer.isVisible = a.developer
            val active = AgentStats.isActive(a, r.now)
            h.b.dot.setBackgroundResource(if (active) R.drawable.agents_dot_active else R.drawable.agents_dot_idle)
            val status = ctx.getString(if (active) R.string.agents_active else R.string.agents_idle)
            h.b.dot.contentDescription = status
            val rotated = AgentStats.waitingForNewPrompt(a, r.now)
            val lastUsed = if (a.lastUsedAt > 0) Fmt.ago(a.lastUsedAt) else null
            h.b.line2.text = when {
                rotated -> ctx.getString(R.string.agents_key_rotated)
                lastUsed != null -> ctx.getString(R.string.agents_line2, lastUsed, r.today)
                else -> ctx.getString(R.string.agents_not_used, r.today)
            }
            h.b.line2.setTextColor(ctx.themeColor(
                if (rotated) com.google.android.material.R.attr.colorSecondary
                else com.google.android.material.R.attr.colorOnSurfaceVariant))
            val dev = if (a.developer) ctx.getString(R.string.agents_card_desc_dev) else ""
            h.b.root.contentDescription = if (rotated)
                ctx.getString(R.string.agents_card_desc_rotated, a.name, a.modelText(ctx), status.lowercase()) + dev
            else ctx.getString(R.string.agents_card_desc, a.name, a.modelText(ctx),
                lastUsed?.let { ctx.getString(R.string.agents_desc_last_used, it) }
                    ?: ctx.getString(R.string.agents_desc_not_used), r.today, status.lowercase()) + dev
            h.b.root.setOnClickListener { onClick(a) }
        }

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<Row>() {
                override fun areItemsTheSame(x: Row, y: Row) = x.agent.id == y.agent.id
                override fun areContentsTheSame(x: Row, y: Row) =
                    x.agent == y.agent && x.today == y.today && x.now / 30_000 == y.now / 30_000
            }
        }
    }
}
