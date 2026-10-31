package cc.bridgit.app.ui.home

import cc.bridgit.app.ui.common.brandVersion
import android.Manifest
import android.os.BatteryManager
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.google.android.material.R as M
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.R
import cc.bridgit.app.capability.Capability
import cc.bridgit.app.capability.address
import cc.bridgit.app.capability.granted
import cc.bridgit.app.databinding.FragmentHomeBinding
import cc.bridgit.app.databinding.HomeAgentRowBinding
import cc.bridgit.app.databinding.HomeBannerBinding
import cc.bridgit.app.databinding.HomeSwitchCardBinding
import cc.bridgit.app.databinding.TourHomeCardBinding
import cc.bridgit.app.interact.Interaction
import cc.bridgit.app.tour.TourKinds
import cc.bridgit.app.log.LogQuery
import cc.bridgit.app.power.PowerAccess
import cc.bridgit.app.power.PowerPlan
import cc.bridgit.app.ui.common.Fmt
import cc.bridgit.app.ui.common.themeColor
import cc.bridgit.app.ui.feedback.handleFeedbackMenu
import cc.bridgit.app.ui.interactions.InteractionUi
import cc.bridgit.app.ui.power.PowersFragment
import java.util.Calendar

/**
 * Home (UI-09 … UI-11, ui-spec 5.2): is Bridgit listening, does anything need
 * me, who used the phone today, and the quick switches.
 */
class HomeFragment : Fragment() {
    private var _b: FragmentHomeBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp

    private val perms = PermissionAsker(this) { requestRender() }
    private val powers by lazy { PowerAccess(app) }
    private val powerSteps by lazy { powers.steps() }

    /** Bumped by registry/settings listeners (any thread); collected and throttled on main. */
    private val dirty = MutableStateFlow(0)
    private val onChange: () -> Unit = { dirty.update { it + 1 } }

    private data class Today(val requests: Int, val photos: Int, val clips: Int, val errors: Int)
    private var today: Today? = null
    private var lastEndpoint: Map<String, String> = emptyMap()
    /** Questions the owner hasn't answered, expired ones included (loaded off the main thread). */
    private var waiting: List<Interaction> = emptyList()

    private var heroDescription = ""
    private var bannerKey = ""
    private var agentsKey = ""
    private val switchCards = mutableListOf<Pair<Capability, HomeSwitchCardBinding>>()
    private var tourCard: TourHomeCardBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentHomeBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(b.toolbar) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top); insets
        }
        b.brandTitle.text = brandVersion(requireContext())
        b.toolbar.menu.add(0, MENU_SETTINGS, 0, R.string.home_menu_settings)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        b.toolbar.inflateMenu(R.menu.feedback_overflow) // Report a bug / Request a feature (FBK-01)
        b.toolbar.setOnMenuItemClickListener {
            if (it.itemId == MENU_SETTINGS) { findNavController().navigate(R.id.settings); true }
            else handleFeedbackMenu(it, "Home")
        }

        b.pause.setOnClickListener {
            app.settings.listenerEnabled = false // first, so MainActivity.onResume doesn't restart it
            runCatching { BridgeService.pause(requireContext()) }
            requestRender()
        }
        b.resume.setOnClickListener {
            if (app.agents.count == 0) { findNavController().navigate(R.id.addAgent); return@setOnClickListener }
            app.settings.listenerEnabled = true
            runCatching { BridgeService.start(requireContext()) }
            requestRender()
        }
        b.address.setOnClickListener { copyAddress() }
        heroAccessibility()

        b.seeAll.setOnClickListener { goTab(R.id.agents) }
        b.addAgent.setOnClickListener { findNavController().navigate(R.id.addAgent) }
        b.today.setOnClickListener { goTab(R.id.activity) }
        buildQuickSwitches()
        b.maxPower.setOnClickListener {
            powers.allSwitchesOn()
            findNavController().navigate(R.id.powers, bundleOf(PowersFragment.ARG_AUTO to true))
        }
        buildTourCard()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.listener.collect { render() } }
                launch { app.tours.state.collect { renderTourCard() } }
                launch { dirty.collect { render(); delay(300) } }
                launch { app.log.latestId.collectLatest { delay(400); loadCounts() } }
                launch {
                    app.interactions.changed.collectLatest {
                        waiting = withContext(Dispatchers.IO) { runCatching { app.interactions.waitingQuestions() }.getOrDefault(emptyList()) }
                        requestRender()
                    }
                }
                launch { while (true) { tickUptime(); delay(1000) } }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.agents.addListener(onChange)
        app.settings.addListener(onChange)
    }

    override fun onResume() {
        super.onResume()
        render() // permissions or notification settings may have changed while away
    }

    override fun onStop() {
        app.agents.removeListener(onChange)
        app.settings.removeListener(onChange)
        super.onStop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        switchCards.clear()
        tourCard = null
        bannerKey = ""; agentsKey = ""; heroDescription = ""
        _b = null
    }

    private fun requestRender() = dirty.update { it + 1 }

    // ---- Hero card (UI-09) ----

    private fun render() {
        if (_b == null) return
        renderHero()
        renderBanners()
        renderAgents()
        renderToday()
        renderSwitches()
        renderMaxPower()
    }

    private fun tailscaleAddresses(): List<String> {
        val s = app.listener.value
        return s.boundTo.ifEmpty { BridgeService.tailscaleAddresses() }
    }

    private fun addressText(): String? {
        val a = app.address()
        val host = a.magicDns ?: a.ip ?: tailscaleAddresses().firstOrNull { !it.contains(':') } ?: return null
        return getString(R.string.home_address, host, a.port)
    }

    private fun renderHero() {
        val ctx = requireContext()
        val s = app.listener.value
        val st = app.settings
        val port = if (s.port > 0) s.port else st.port
        val paused = !s.running && !(st.listenerEnabled && app.agents.count > 0)
        val error = s.bindError != null
        val onCard = ctx.themeColor(if (error) M.attr.colorOnErrorContainer else M.attr.colorOnPrimaryContainer)

        b.hero.setCardBackgroundColor(ctx.themeColor(if (error) M.attr.colorErrorContainer else M.attr.colorPrimaryContainer))
        val (text, color, pulse) = when {
            error -> Triple(getString(R.string.home_state_bind_error, port), ctx.themeColor(M.attr.colorError), false)
            s.running && s.capturing != null -> Triple(s.capturing!!.ifBlank { getString(R.string.home_state_capturing) },ContextCompat.getColor(ctx, R.color.beacon_gold), true)
            s.running && s.boundTo.isEmpty() -> Triple(getString(R.string.home_state_tailscale_down), ctx.themeColor(M.attr.colorError), false)
            s.running -> Triple(getString(R.string.home_state_listening), ctx.themeColor(M.attr.colorPrimary), false)
            !paused -> Triple(getString(R.string.home_state_starting), ctx.themeColor(M.attr.colorOutline), false)
            else -> Triple(getString(R.string.home_state_paused), ctx.themeColor(M.attr.colorOutline), false)
        }
        b.beacon.set(color, pulse)
        if (b.state.text.toString() != text) b.state.text = text
        b.state.setTextColor(onCard)

        b.pause.isVisible = !paused
        b.resume.isVisible = paused
        b.pause.setTextColor(onCard)

        val addr = addressText()
        b.address.text = addr ?: getString(R.string.home_no_address)
        b.address.isClickable = addr != null
        b.address.setTextColor(onCard)
        b.address.alpha = if (paused) 0.6f else 1f

        val tsOn = tailscaleAddresses().isNotEmpty()
        b.chipTailscale.text = getString(if (tsOn) R.string.home_chip_tailscale else R.string.home_chip_tailscale_off)
        val check = if (tsOn) ContextCompat.getDrawable(ctx, R.drawable.ic_check)?.mutate()?.apply {
            setBounds(0, 0, ctx.resources.getDimensionPixelSize(R.dimen.home_chip_icon), ctx.resources.getDimensionPixelSize(R.dimen.home_chip_icon))
            setTint(onCard)
        } else null
        b.chipTailscale.setCompoundDrawablesRelative(check, null, null, null)
        val battery = batteryPercent()
        b.chipBattery.isVisible = battery != null
        battery?.let { b.chipBattery.text = getString(R.string.home_chip_battery, it) }
        listOf(b.chipUptime, b.chipTailscale, b.chipBattery).forEach { it.setTextColor(onCard) }
        tickUptime()

        // Combined description for the single focus stop; uptime is added at focus time
        // (see heroAccessibility) so the polite live region doesn't speak every minute.
        val desc = buildString {
            append(getString(R.string.home_cd_hero, text.replaceFirstChar { it.lowercase() }))
            addr?.let { append(' ').append(getString(R.string.home_cd_address, it)) }
            append(' ').append(getString(if (tsOn) R.string.home_cd_tailscale_on else R.string.home_cd_tailscale_off))
            battery?.let { append(' ').append(getString(R.string.home_cd_battery, it)) }
        }
        if (desc != heroDescription) {
            heroDescription = desc
            // Setting it on the live-region view itself makes the change event come from that view.
            b.heroInfo.contentDescription = desc
        }
    }

    private fun tickUptime() {
        if (_b == null) return
        val s = app.listener.value
        val up = s.running && s.startedAt > 0
        b.chipUptime.isVisible = up
        if (up) b.chipUptime.text = Fmt.uptime(System.currentTimeMillis() - s.startedAt)
    }

    private fun heroAccessibility() {
        ViewCompat.setAccessibilityDelegate(b.heroInfo, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                val up = _b?.chipUptime?.takeIf { it.isVisible }?.text?.let { " $it." } ?: ""
                info.contentDescription = heroDescription + up
                if (addressText() != null) info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    R.id.home_action_copy_address, getString(R.string.home_copy_address)))
            }
            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                if (action == R.id.home_action_copy_address) { copyAddress(); return true }
                return super.performAccessibilityAction(host, action, args)
            }
        })
    }

    private fun copyAddress() {
        val addr = addressText() ?: return
        UiActions.copy(requireContext(), "Bridgit address", addr)
        Snackbar.make(b.root, R.string.home_address_copied, Snackbar.LENGTH_SHORT).show()
    }

    private fun batteryPercent(): Int? = requireContext().getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }

    // ---- City tour (8.25): a card at the top while a tour runs ----

    /** Inserted in code above the hero card (fragment_home.xml belongs to the Home slice). */
    private fun buildTourCard() {
        val parent = b.hero.parent as ViewGroup
        val c = TourHomeCardBinding.inflate(layoutInflater, parent, false)
        parent.addView(c.root, parent.indexOfChild(b.hero))
        c.root.setOnClickListener { findNavController().navigate(R.id.tour) }
        c.tourOpen.setOnClickListener { findNavController().navigate(R.id.tour) }
        tourCard = c
        renderTourCard()
    }

    private fun renderTourCard() {
        val c = tourCard ?: return
        val t = app.tours.current()
        c.root.isVisible = t != null
        if (t == null) return
        var text = getString(R.string.tour_home_card, t.agent, t.kinds.joinToString(", ") { TourKinds.labels[it] ?: it })
        if (t.paused) text += " · " + getString(R.string.tour_home_paused)
        if (c.tourText.text.toString() != text) c.tourText.text = text
    }

    // ---- Banners (UI-10) ----

    private data class Banner(val key: String, val text: String, val action: String, val error: Boolean,
                              val icon: Int? = null, val onClick: () -> Unit)

    /** Only when action is needed. Order: questions waiting for the owner, nothing works without Tailscale, then what blocks captures. */
    private fun banners(): List<Banner> {
        val ctx = requireContext()
        val s = app.listener.value
        val out = mutableListOf<Banner>()
        waiting.singleOrNull()?.let { q ->
            out += Banner("ask1:${q.id}", getString(R.string.home_banner_question_one, InteractionUi.agentName(q), q.title),
                getString(R.string.home_banner_answer), false, R.drawable.ic_nav_interactions) {
                findNavController().navigate(R.id.interactionDetail, bundleOf("interactionId" to q.id))
            }
        }
        if (waiting.size > 1)
            out += Banner("askN:${waiting.size}", resources.getQuantityString(R.plurals.home_banner_questions, waiting.size, waiting.size),
                getString(R.string.home_banner_see_questions), false, R.drawable.ic_nav_interactions) { goTab(R.id.interactions) }
        if (!UiActions.isTailscaleInstalled(ctx))
            out += Banner("ts", getString(R.string.home_banner_tailscale_missing), getString(R.string.home_banner_install_tailscale), true) {
                UiActions.openTailscale(ctx)
            }
        else if (s.running && s.boundTo.isEmpty() && s.bindError == null)
            out += Banner("ts", getString(R.string.home_banner_tailscale), getString(R.string.home_banner_open_tailscale), true) {
                UiActions.openTailscale(ctx)
            }
        if (!app.captureNotificationsOn())
            out += Banner("notif", getString(R.string.home_banner_notifications), getString(R.string.home_banner_turn_on), false) {
                if (!ctx.granted(Manifest.permission.POST_NOTIFICATIONS)) perms.request(Manifest.permission.POST_NOTIFICATIONS)
                else UiActions.openAppNotificationSettings(ctx)
            }
        if (s.running && s.startedInBackground &&
            (ctx.granted(Manifest.permission.CAMERA) || ctx.granted(Manifest.permission.RECORD_AUDIO)))
            out += Banner("once", getString(R.string.home_banner_open_once), getString(R.string.home_banner_fix_now), false) {
                runCatching { BridgeService.start(ctx) }
            }
        for (m in app.registry.switchable()) {
            if (!m.enabled || m.blockedReason()?.startsWith("permission:") != true) continue
            val title = UiActions.permissionTitle(ctx, m.permissions.firstOrNull() ?: continue)
            out += Banner("perm:${m.id}", getString(R.string.home_banner_permission, title), getString(R.string.home_banner_grant), false) {
                perms.request(*UiActions.permissionsFor(m))
            }
        }
        if (UiActions.batteryOptimised(ctx))
            out += Banner("battery", getString(R.string.home_banner_battery), getString(R.string.home_banner_fix), false) {
                UiActions.requestBatteryExemption(ctx)
            }
        return out
    }

    private fun renderBanners() {
        val list = banners()
        val key = list.joinToString("|") { it.key }
        if (key == bannerKey) {
            // Same banners: just rebind click handlers (captured context may be new).
            list.forEachIndexed { i, bn -> b.banners.getChildAt(i)?.findViewById<View>(R.id.action)?.setOnClickListener { bn.onClick() } }
            return
        }
        bannerKey = key
        b.banners.removeAllViews()
        val ctx = requireContext()
        for (bn in list) {
            val v = HomeBannerBinding.inflate(layoutInflater, b.banners, false)
            val bg = if (bn.error) M.attr.colorErrorContainer else M.attr.colorSecondaryContainer
            val fg = ctx.themeColor(if (bn.error) M.attr.colorOnErrorContainer else M.attr.colorOnSecondaryContainer)
            v.root.setCardBackgroundColor(ctx.themeColor(bg))
            v.icon.setImageResource(bn.icon ?: if (bn.error) R.drawable.ic_error else R.drawable.ic_warning)
            v.icon.setColorFilter(fg)
            v.text.text = bn.text
            v.text.setTextColor(fg)
            v.action.text = bn.action
            v.action.setTextColor(fg)
            v.action.setOnClickListener { bn.onClick() }
            b.banners.addView(v.root)
        }
    }

    // ---- Agents (UI-11) ----

    private fun renderAgents() {
        val agents = app.agents.all().sortedWith(compareByDescending<cc.bridgit.app.core.Agent> { it.lastUsedAt }.thenBy { it.name.lowercase() }).take(3)
        b.noAgents.isVisible = agents.isEmpty()
        b.seeAll.isVisible = agents.isNotEmpty()
        val now = System.currentTimeMillis()
        val key = agents.joinToString("|") { a ->
            "${a.id}/${a.name}/${a.lastUsedAt / 60_000}/${lastEndpoint[a.id]}/${now - a.lastUsedAt < ACTIVE_MS}"
        }
        if (key == agentsKey) return
        agentsKey = key
        b.agentRows.removeAllViews()
        for (a in agents) {
            val r = HomeAgentRowBinding.inflate(layoutInflater, b.agentRows, false)
            r.avatar.setAgent(a.name)
            val whenText = if (a.lastUsedAt <= 0) getString(R.string.home_agent_not_used)
                else getString(R.string.home_agent_ago, Fmt.ago(a.lastUsedAt))
            r.name.text = SpannableStringBuilder(a.name).append(' ').also {
                val start = it.length
                it.append(whenText)
                it.setSpan(ForegroundColorSpan(requireContext().themeColor(M.attr.colorOnSurfaceVariant)), start, it.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val ep = lastEndpoint[a.id]
            r.endpoint.isVisible = ep != null
            r.endpoint.text = ep
            val active = a.lastUsedAt > 0 && now - a.lastUsedAt < ACTIVE_MS
            r.dot.setBackgroundResource(if (active) R.drawable.home_dot_active else R.drawable.home_dot_idle)
            r.root.contentDescription = listOfNotNull(a.name, whenText.removePrefix("· "), ep,
                getString(if (active) R.string.home_agent_active else R.string.home_agent_idle)).joinToString(", ")
            r.root.setOnClickListener { findNavController().navigate(R.id.agentDetail, bundleOf("agentId" to a.id)) }
            b.agentRows.addView(r.root)
        }
    }

    // ---- Today ----

    private suspend fun loadCounts() {
        val log = app.log
        val ids = app.agents.all().sortedByDescending { it.lastUsedAt }.take(3).map { it.id }
        val (t, eps) = withContext(Dispatchers.IO) {
            runCatching {
                val base = LogQuery(channels = listOf("audit"), kinds = listOf("request"), sinceTs = startOfToday())
                val t = Today(
                    requests = log.count(base),
                    photos = log.count(base.copy(endpointPrefix = "/v1/camera/snapshot", result = "ok")),
                    clips = log.count(base.copy(endpointPrefix = "/v1/mic/record", result = "ok")),
                    errors = log.count(base.copy(result = "error")),
                )
                val eps = ids.mapNotNull { id ->
                    log.query(LogQuery(agentIds = listOf(id), kinds = listOf("request"), limit = 1)).firstOrNull()
                        ?.endpoint?.let { id to it.removePrefix("/v1/") }
                }.toMap()
                t to eps
            }.getOrElse { null to emptyMap() }
        }
        today = t
        lastEndpoint = eps
        if (_b != null) { renderToday(); renderAgents() }
    }

    private fun renderToday() {
        val t = today
        b.today.isVisible = t != null
        if (t == null) return
        val ctx = requireContext()
        val res = resources
        val sb = SpannableStringBuilder()
        sb.append(getString(R.string.home_today), StyleSpan(android.graphics.Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(" · ").append(res.getQuantityString(R.plurals.home_requests, t.requests, t.requests))
        sb.append(" · ").append(res.getQuantityString(R.plurals.home_photos, t.photos, t.photos))
        sb.append(" · ").append(res.getQuantityString(R.plurals.home_clips, t.clips, t.clips))
        sb.append(" · ")
        val errText = res.getQuantityString(R.plurals.home_errors, t.errors, t.errors)
        if (t.errors > 0) sb.append(errText, ForegroundColorSpan(ctx.themeColor(M.attr.colorError)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        else sb.append(errText)
        b.todayText.text = sb
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ---- Quick switches (UI-11) ----

    /** The switchable modules in this build (Camera, Microphone, Gallery); Location joins in P3 (Q-D4). */
    private fun buildQuickSwitches() {
        b.quickGrid.removeAllViews()
        switchCards.clear()
        val modules = app.registry.switchable().take(4)
        b.quickHeader.isVisible = modules.isNotEmpty()
        for (m in modules) {
            val c = HomeSwitchCardBinding.inflate(layoutInflater, b.quickGrid, false)
            c.label.text = m.title
            c.root.contentDescription = m.title
            UiActions.asSwitch(c.root) { m.enabled }
            c.root.setOnClickListener { toggle(m) }
            b.quickGrid.addView(c.root)
            switchCards += m to c
        }
    }

    private fun renderSwitches() {
        for ((m, c) in switchCards) if (c.toggle.isChecked != m.enabled) c.toggle.isChecked = m.enabled
    }

    /** Max power: on once every switch and every Android step this build declares is on. */
    private fun renderMaxPower() {
        val on = powers.everySwitchOn() && PowerPlan.status(powerSteps, powers::granted, emptySet()).values.all { it == PowerPlan.Status.ON }
        b.maxPower.isEnabled = !on
        b.maxPower.setText(if (on) R.string.power_max_on else R.string.power_max)
        b.maxPower.setIconResource(if (on) R.drawable.ic_check else R.drawable.ic_bolt)
        b.maxPower.contentDescription = if (on) getString(R.string.power_max_on) else getString(R.string.power_max) + ", " + getString(R.string.power_max_desc)
    }

    private fun toggle(m: Capability) {
        val on = !m.enabled
        UiActions.setSwitch(app, m, on)
        if (on && m.blockedReason()?.startsWith("permission:") == true) perms.request(*UiActions.permissionsFor(m))
        render()
    }

    private fun goTab(id: Int) {
        val nav = findNavController()
        nav.navigate(id, null, NavOptions.Builder().setLaunchSingleTop(true).setRestoreState(true)
            .setPopUpTo(nav.graph.startDestinationId, inclusive = false, saveState = true).build())
    }

    companion object {
        private const val MENU_SETTINGS = 1
        private const val ACTIVE_MS = 10 * 60 * 1000L
    }
}
