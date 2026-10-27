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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.finleg.bridget.BridgeApp
import net.finleg.bridget.BridgeService
import net.finleg.bridget.R
import net.finleg.bridget.core.Agent
import net.finleg.bridget.databinding.AgentsRenameDialogBinding
import net.finleg.bridget.databinding.FragmentAgentDetailBinding
import net.finleg.bridget.log.LogQuery
import net.finleg.bridget.ui.common.modelText
import net.finleg.bridget.ui.common.Fmt
import net.finleg.bridget.ui.common.LogRowAdapter
import net.finleg.bridget.ui.common.themeColor
import net.finleg.bridget.ui.feedback.handleFeedbackMenu
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Agent detail (UI-14, AGT-12 … AGT-15, ui-spec 5.5). No Callback section until P2. */
class AgentDetailFragment : Fragment() {
    private var _b: FragmentAgentDetailBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp
    private lateinit var id: String
    private lateinit var activityAdapter: LogRowAdapter
    private var bindingSwitch = false
    private var savedNotes: String? = null

    private val agentsChanged: () -> Unit = { view?.post { if (_b != null) bindAgent() } }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentAgentDetailBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        id = requireArguments().getString("agentId") ?: run { findNavController().popBackStack(); return }
        if (app.agents.get(id) == null) { findNavController().popBackStack(); return }

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            b.toolbar.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime()).bottom)
            insets
        }
        b.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        b.toolbar.inflateMenu(R.menu.agents_detail_menu)
        b.toolbar.inflateMenu(R.menu.feedback_overflow) // Report a bug / Request a feature (FBK-01)
        b.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.agents_rename) { rename(); true }
            // Agent names go in the context only from an agent screen (FBK-06).
            else handleFeedbackMenu(it, "Agent detail") { agent()?.name?.let { n -> "Agent: $n" } }
        }

        // Row taps open the entry sheet in Activity; here the rows are read-only and "See all" goes there.
        activityAdapter = LogRowAdapter(app.agents) { }
        b.activityList.adapter = activityAdapter
        b.seeAll.setOnClickListener { findNavController().navigate(R.id.activity, bundleOf("agentId" to id)) }

        b.rotate.setOnClickListener { confirmRotate() }
        b.remove.setOnClickListener { confirmRemove() }
        b.developer.setOnCheckedChangeListener { _, on ->
            if (bindingSwitch) return@setOnCheckedChangeListener
            val a = app.agents.update(id) { it.copy(developer = on) } ?: return@setOnCheckedChangeListener
            app.log.system("audit", "agent", getString(
                if (on) R.string.agents_developer_on_log else R.string.agents_developer_off_log, a.name))
        }
        b.notes.setOnFocusChangeListener { _, has -> if (!has) saveNotes() }

        bindAgent()
        app.agents.addListener(agentsChanged)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.log.latestId.collectLatest { delay(300); loadLog() } }
                launch { while (true) { delay(30_000); bindAgent() } }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (_b != null) saveNotes()
    }

    override fun onDestroyView() {
        if (this::id.isInitialized) app.agents.removeListener(agentsChanged)
        super.onDestroyView()
        _b = null
    }

    private fun agent(): Agent? = app.agents.get(id)

    private fun bindAgent() {
        val a = agent() ?: return
        b.toolbar.title = a.name
        b.avatar.setAgent(a.name)
        b.name.text = a.name
        b.connection.text = getString(R.string.agents_model_via, a.modelText(requireContext()), a.connection.label)
        val added = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(a.createdAt))
        if (a.lastUsedAt > 0) {
            b.added.text = getString(R.string.agents_added_line, added, Fmt.ago(a.lastUsedAt))
            b.lastIp.text = a.lastSourceIp ?: ""
            b.lastIp.isVisible = a.lastSourceIp != null
        } else {
            b.added.text = getString(R.string.agents_added_line_never, added)
            b.lastIp.isVisible = false
        }
        b.header.contentDescription = listOf(a.name, b.connection.text, b.added.text,
            if (b.lastIp.isVisible) b.lastIp.text else null).filterNotNull().joinToString(", ")
        b.keyPrefix.text = a.keyPrefix
        b.keyPrefix.contentDescription = getString(R.string.agents_key_desc, a.keyPrefix.trimEnd('…'))
        bindingSwitch = true
        b.developer.isChecked = a.developer
        bindingSwitch = false
        if (!b.notes.hasFocus() && b.notes.text?.toString() != a.notes) {
            b.notes.setText(a.notes)
            savedNotes = a.notes
        }
        if (savedNotes == null) savedNotes = a.notes
        b.activityEmpty.text = getString(R.string.agents_no_calls, a.name)
    }

    private fun loadLog() {
        val lifecycle = viewLifecycleOwner
        lifecycle.lifecycleScope.launch {
            val (today, entries) = withContext(Dispatchers.IO) {
                AgentStats.today(app.log, id) to runCatching {
                    app.log.query(LogQuery(agentIds = listOf(id), limit = 50))
                }.getOrDefault(emptyList())
            }
            if (_b == null) return@launch
            b.statRequests.text = today.requests.toString()
            b.statErrors.text = today.errors.toString()
            b.statSent.text = if (today.bytesOut == 0L) "0 B" else Fmt.bytes(today.bytesOut)
            b.tileRequests.contentDescription = "${today.requests} ${getString(R.string.agents_stat_requests)}"
            b.tileErrors.contentDescription = "${today.errors} ${getString(R.string.agents_stat_errors)}"
            b.tileSent.contentDescription = "${b.statSent.text} ${getString(R.string.agents_stat_sent)}"
            activityAdapter.submitList(entries)
            b.activityEmpty.isVisible = entries.isEmpty()
            b.activityList.isVisible = entries.isNotEmpty()
        }
    }

    private fun saveNotes() {
        val text = b.notes.text?.toString()?.take(200) ?: return
        if (text == savedNotes) return
        savedNotes = text
        app.agents.update(id) { it.copy(notes = text) }
    }

    private fun rename() {
        val a = agent() ?: return
        val db = AgentsRenameDialogBinding.inflate(layoutInflater)
        db.input.setText(a.name)
        db.input.setSelection(a.name.length)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.agents_rename_title, a.name))
            .setView(db.root)
            .setPositiveButton(R.string.agents_save, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val name = db.input.text?.toString()?.trim() ?: ""
            if (name == a.name) { dialog.dismiss(); return@setOnClickListener }
            val err = app.agents.checkName(name, exceptId = id)
            if (err != null) { db.layout.error = err; return@setOnClickListener }
            app.agents.update(id) { it.copy(name = name) }
            app.log.system("audit", "agent", getString(R.string.agents_renamed_log, a.name, name))
            dialog.dismiss()
        }
    }

    private fun confirmRotate() {
        val a = agent() ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.agents_rotate_body, a.name))
            .setPositiveButton(R.string.agents_rotate_confirm) { _, _ ->
                val (rotated, key) = app.agents.rotate(id)
                app.vault.put(id, key, rotated = true)
                app.log.system("audit", "agent", getString(R.string.agents_rotated_log, rotated.name))
                // "fromRotate" isn't declared in nav_main.xml; Navigation passes extra bundle keys through.
                findNavController().navigate(R.id.addAgent, bundleOf("agentId" to id, "fromRotate" to true))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmRemove() {
        val a = agent() ?: return
        val last = app.agents.count == 1
        val body = getString(R.string.agents_remove_body, a.name) +
            (if (last) " " + getString(R.string.agents_remove_last) else "")
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(body)
            .setPositiveButton(R.string.agents_remove_confirm) { _, _ ->
                val removed = app.agents.remove(id) ?: return@setPositiveButton
                app.log.system("audit", "agent", getString(R.string.agents_removed_log, removed.name))
                if (app.agents.count == 0) {
                    app.settings.listenerEnabled = false
                    app.log.system("audit", "setting", "Bridget stopped listening: no agents left")
                    runCatching { BridgeService.pauseSilently(requireContext()) }
                }
                findNavController().popBackStack()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
            .also { d ->
                // The confirming action is destructive: error colour (ui-spec 5.5).
                d.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setTextColor(
                    requireContext().themeColor(com.google.android.material.R.attr.colorError))
            }
    }
}
