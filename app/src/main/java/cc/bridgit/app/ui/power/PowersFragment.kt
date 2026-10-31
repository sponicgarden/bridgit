package cc.bridgit.app.ui.power

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.R as M
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.R
import cc.bridgit.app.capability.granted
import cc.bridgit.app.databinding.AccessRowBinding
import cc.bridgit.app.databinding.FragmentPowersBinding
import cc.bridgit.app.power.PowerAccess
import cc.bridgit.app.power.PowerPlan
import cc.bridgit.app.power.PowerPlan.Kind
import cc.bridgit.app.power.PowerPlan.Status
import cc.bridgit.app.power.PowerPlan.Step
import cc.bridgit.app.ui.common.dp
import cc.bridgit.app.ui.common.themeColor
import cc.bridgit.app.ui.home.UiActions

/**
 * Maximum powers: shown after the owner confirms (see [confirmMaximum]), with every switch
 * already on. A checklist of each Android permission and special access this build declares,
 * with live status; Continue asks with Android's own dialogs, then opens each real settings page
 * in turn. Re-checks on return, skips what's on, and ends with a summary. Nothing here imitates
 * a system dialog (REQ-05). Optional `agentId`: the agent that was just given every grant.
 * With `auto` (Max power on Home) it starts at once and moves on after each step by itself,
 * until a step comes back still off (see [PowerPlan.autoContinue]).
 */
class PowersFragment : Fragment() {
    private var _b: FragmentPowersBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp
    private val access by lazy { PowerAccess(app) }

    private var steps: List<Step> = emptyList()
    /** Steps the owner was shown and came back from still off. */
    private val declined = mutableSetOf<String>()
    /** The settings page opened last; checked when the owner comes back. */
    private var opened: String? = null
    /** Runtime steps in the batch Android is asking about now. */
    private var asking: List<String> = emptyList()
    private var askedAt = 0L
    private var last: Map<String, Status> = emptyMap()
    private val auto get() = arguments?.getBoolean(ARG_AUTO) == true
    /** Auto mode already started; kept on the instance so coming back from Features doesn't start it again. */
    private var started = false

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val ctx = context ?: return@registerForActivityResult
        val quick = SystemClock.elapsedRealtime() - askedAt < NO_DIALOG_MS
        for (id in asking) {
            val s = steps.firstOrNull { it.id == id } ?: continue
            if (!s.permissions.all(ctx::granted)) declined += id
        }
        // A quick denial without rationale means Android didn't show the dialog: offer App info.
        if (quick && result.isNotEmpty() && result.values.none { it } && result.keys.none { shouldShowRequestPermissionRationale(it) })
            view?.let { v -> com.google.android.material.snackbar.Snackbar.make(v, R.string.power_denied_settings, 6000)
                .setAction(R.string.perm_open_settings) { UiActions.openAppDetails(ctx) }.setAnchorView(b.bottomBar).show() }
        // The listener adds camera, mic and location service types only when restarted from a visible screen (ARC-03).
        if (result.values.any { it } && app.settings.listenerEnabled) runCatching { BridgeService.start(ctx) }
        val shown = asking
        asking = emptyList()
        refresh()
        // Posted: the fragment resumes right after this result, and a page opened now would read as skipped.
        autoNext(shown)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentPowersBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        savedInstanceState?.let { s ->
            declined += s.getStringArrayList(STATE_DECLINED).orEmpty()
            opened = s.getString(STATE_OPENED)
            asking = s.getStringArrayList(STATE_ASKING).orEmpty()
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            b.toolbar.updatePadding(top = bars.top)
            b.bottomBar.updatePadding(bottom = bars.bottom + requireContext().dp(12)); insets
        }
        b.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        val agent = arguments?.getString(ARG_AGENT)?.let { app.agents.get(it) }
        b.intro.text = when {
            auto && agent != null -> getString(R.string.power_intro_auto_agent, agent.name)
            auto -> getString(R.string.power_intro_auto)
            agent != null -> getString(R.string.power_intro_agent, agent.name)
            else -> getString(R.string.power_intro)
        }
        b.openFeatures.setOnClickListener { findNavController().navigate(R.id.features) }
        b.next.setOnClickListener { continueNext() }
        steps = access.steps()
        if (auto && savedInstanceState == null && !started) {
            started = true
            if (agent == null) com.google.android.material.snackbar.Snackbar.make(b.root, R.string.power_max_switched, 6000)
                .setAction(R.string.undo) { access.allSwitchesOff(); refresh() }.setAnchorView(b.bottomBar).show()
            autoNext(null)
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from a settings page: still off means the owner skipped it.
        val back = opened
        opened?.let { id ->
            opened = null
            val s = steps.firstOrNull { it.id == id }
            if (id == PowerPlan.RESTRICTED) declined -= steps.filter(PowerPlan::isRestrictable).map { it.id }.toSet()
            if (s != null && s.kind != Kind.RESTRICTED && !access.granted(s)) declined += id
            if (id == PowerPlan.RESTRICTED) declined += id
        }
        refresh()
        back?.let { autoNext(listOf(it)) }
    }

    /** Auto mode: the next step, unless [shown] (null = first step) came back skipped or nothing is left. */
    private fun autoNext(shown: List<String>?) {
        if (!auto || (shown != null && !PowerPlan.autoContinue(shown, declined))) return
        view?.post {
            if (_b != null && opened == null && asking.isEmpty() && PowerPlan.next(steps, status()) != null) continueNext()
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putStringArrayList(STATE_DECLINED, ArrayList(declined))
        out.putString(STATE_OPENED, opened)
        out.putStringArrayList(STATE_ASKING, ArrayList(asking))
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }

    private fun status() = PowerPlan.status(steps, access::granted, declined)

    private fun refresh() {
        val bb = _b ?: return
        val st = status()
        val visible = steps.filter { st[it.id] != null }
        bb.steps.removeAllViews()
        visible.forEach { addRow(bb, it, st.getValue(it.id)) }

        val next = PowerPlan.next(steps, st)
        bb.next.text = next?.let { getString(R.string.power_continue, access.title(it)) } ?: getString(R.string.power_done)

        val on = visible.count { st[it.id] == Status.ON }
        val skipped = visible.filter { st[it.id] == Status.DECLINED }
        bb.summaryCard.isVisible = next == null
        if (next == null) {
            bb.summary.text = buildString {
                append(getString(R.string.power_summary, on, visible.size))
                if (skipped.isNotEmpty()) append("\n").append(getString(R.string.power_summary_skipped, skipped.joinToString(", ") { access.title(it) }))
            }
        }
        // Features: every switch with what it still needs, straight from the registry (later modules join on their own).
        val modules = app.registry.switchable()
        val notReady = modules.filter { !it.enabled || it.blockedReason() != null }
        bb.features.text = if (notReady.isEmpty()) getString(R.string.power_features_ready, modules.size)
            else getString(R.string.power_features_some, modules.size - notReady.size, modules.size, notReady.joinToString(", ") { it.title })

        // Announce only what changed (ui-spec 7).
        if (last.isNotEmpty()) visible.filter { last[it.id] != null && last[it.id] != st[it.id] }.forEach {
            @Suppress("DEPRECATION")
            bb.root.announceForAccessibility(getString(R.string.access_now, access.title(it), label(st.getValue(it.id)).lowercase()))
        }
        last = st
    }

    private fun label(s: Status) = getString(when (s) {
        Status.ON -> R.string.access_status_on
        Status.OFF -> R.string.access_status_off
        Status.DECLINED -> R.string.power_status_skipped
    })

    private fun addRow(bb: FragmentPowersBinding, s: Step, st: Status) {
        val ctx = bb.root.context
        val r = AccessRowBinding.inflate(layoutInflater, bb.steps, false)
        val title = access.title(s)
        val unlocks = access.unlocks(s)
        r.title.text = title
        r.unlocks.text = unlocks
        r.extra.isVisible = false
        r.status.text = label(st)
        val (bg, fg) = when (st) {
            Status.ON -> R.attr.colorSuccessContainer to R.attr.colorOnSuccessContainer
            Status.OFF -> M.attr.colorSurfaceContainerHigh to M.attr.colorOnSurfaceVariant
            Status.DECLINED -> M.attr.colorSecondaryContainer to M.attr.colorOnSecondaryContainer
        }
        r.status.backgroundTintList = ColorStateList.valueOf(ctx.themeColor(bg))
        r.status.setTextColor(ctx.themeColor(fg))
        if (st == Status.ON) {
            val check = ContextCompat.getDrawable(ctx, R.drawable.ic_check)?.mutate()
            check?.setBounds(0, 0, ctx.dp(14), ctx.dp(14))
            check?.setTint(ctx.themeColor(fg))
            r.status.setCompoundDrawablesRelative(check, null, null, null)
            r.action.isVisible = false
            r.root.isClickable = false
            r.root.contentDescription = getString(R.string.access_row_cd, title, label(st).lowercase(), unlocks)
        } else {
            val button = getString(when {
                st == Status.DECLINED -> R.string.power_btn_try_again
                s.kind == Kind.RUNTIME -> R.string.access_btn_grant
                else -> R.string.access_btn_turn_on
            })
            r.action.text = button
            r.action.setOnClickListener { go(s) }
            r.root.setOnClickListener { go(s) }
            r.root.contentDescription = getString(R.string.access_row_cd_button, title, label(st).lowercase(), unlocks, button)
        }
        bb.steps.addView(r.root)
    }

    /** Continue: every pending runtime permission in one batch first (Android shows its dialogs in turn), then each page. */
    private fun continueNext() {
        val st = status()
        val next = PowerPlan.next(steps, st) ?: run { findNavController().navigateUp(); return }
        if (next.kind == Kind.RUNTIME && next.id != HEALTH) {
            val perms = PowerPlan.pendingRuntime(steps, st, app::granted)
            if (perms.isNotEmpty()) {
                asking = steps.filter { it.kind == Kind.RUNTIME && st[it.id] == Status.OFF && it.permissions.any { p -> p in perms } }.map { it.id }
                ask(perms)
                return
            }
        }
        go(next)
    }

    /** One step: its runtime prompt, or its settings page when the permissions are in but more is needed (notification channel). */
    private fun go(s: Step) {
        val ctx = requireContext()
        if (s.kind == Kind.RUNTIME && !s.permissions.all(ctx::granted)) {
            val pending = s.permissions.filter { !ctx.granted(it) }
            // Android has stopped asking: its own page is the only place to change it.
            if (s.id in declined && pending.none { shouldShowRequestPermissionRationale(it) } && s.id != HEALTH) { open(s, appInfo = true); return }
            asking = listOf(s.id)
            ask(pending)
            return
        }
        open(s)
    }

    private fun ask(perms: List<String>) {
        askedAt = SystemClock.elapsedRealtime()
        permLauncher.launch(perms.toTypedArray())
    }

    private fun open(s: Step, appInfo: Boolean = false) {
        opened = s.id
        val ctx = requireContext()
        if (appInfo) { UiActions.openAppDetails(ctx); return }
        runCatching { startActivity(access.intent(s)) }
            .recoverCatching { startActivity(access.fallback(s)) }
            .onFailure { UiActions.openAppDetails(ctx) }
    }

    companion object {
        const val ARG_AGENT = "agentId"
        const val ARG_AUTO = "auto"
        private const val HEALTH = "health"
        private const val NO_DIALOG_MS = 600L
        private const val STATE_DECLINED = "declined"
        private const val STATE_OPENED = "opened"
        private const val STATE_ASKING = "asking"

        /**
         * Asks the owner, then turns every switch on (and gives [agentId] every grant) and opens the
         * guided pass. [onCancel] runs when the owner backs out.
         */
        fun confirmMaximum(fragment: Fragment, agentId: String?, onCancel: () -> Unit = {}) {
            val app = fragment.requireActivity().application as BridgeApp
            val agent = agentId?.let { app.agents.get(it) }
            val features = app.registry.switchable().joinToString(", ") { it.title }
            val body = if (agent != null) fragment.getString(R.string.power_confirm_body, agent.name, features)
                else fragment.getString(R.string.power_confirm_body_device, features)
            MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(if (agent != null) fragment.getString(R.string.power_confirm_title, agent.name) else fragment.getString(R.string.settings_max_control))
                .setMessage(body)
                .setPositiveButton(R.string.power_confirm_yes) { _, _ ->
                    val access = PowerAccess(app)
                    access.allSwitchesOn()
                    if (agent != null) {
                        access.grantAll(agent.id)
                        app.log.system("audit", "agent", fragment.getString(R.string.power_granted_log, agent.name))
                    }
                    fragment.findNavController().navigate(R.id.powers, bundleOf(ARG_AGENT to agent?.id, ARG_AUTO to true))
                }
                .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
                .setOnCancelListener { onCancel() }
                .show()
        }

        /** Settings: every switch off after the owner confirms. Agents stay paired; Android permissions stay granted. */
        fun confirmAllOff(fragment: Fragment, onDone: () -> Unit) {
            val app = fragment.requireActivity().application as BridgeApp
            MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.power_off_title)
                .setMessage(R.string.power_off_body)
                .setPositiveButton(R.string.power_off_yes) { _, _ -> PowerAccess(app).allSwitchesOff(); onDone() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }
}
