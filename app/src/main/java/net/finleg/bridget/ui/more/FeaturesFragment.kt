package net.finleg.bridget.ui.more

import android.Manifest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.finleg.bridget.BridgeApp
import net.finleg.bridget.BridgeService
import net.finleg.bridget.R
import net.finleg.bridget.capability.Capability
import net.finleg.bridget.capability.granted
import net.finleg.bridget.databinding.FeaturesRowBinding
import net.finleg.bridget.databinding.FragmentFeaturesBinding
import net.finleg.bridget.ui.home.PermissionAsker
import net.finleg.bridget.ui.home.UiActions

/**
 * Features (UI-18, ui-spec 5.8): one MaterialSwitch per built capability,
 * grouped, with a readiness line when something is missing. Unbuilt
 * capabilities are simply absent from the registry, so they never show.
 */
class FeaturesFragment : Fragment() {
    private var _b: FragmentFeaturesBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp
    private val perms = PermissionAsker(this) { dirty.update { it + 1 } }
    private val dirty = MutableStateFlow(0)
    private val onChange: () -> Unit = { dirty.update { it + 1 } }
    private val rows = mutableListOf<Pair<Capability, FeaturesRowBinding>>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentFeaturesBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            b.toolbar.updatePadding(top = bars.top)
            b.scroll.updatePadding(bottom = bars.bottom); insets
        }
        b.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        build()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.listener.collect { bind() } }
                launch { dirty.collect { bind(); delay(200) } }
            }
        }
    }

    override fun onStart() { super.onStart(); app.settings.addListener(onChange) }
    override fun onResume() { super.onResume(); bind() }
    override fun onStop() { app.settings.removeListener(onChange); super.onStop() }
    override fun onDestroyView() { super.onDestroyView(); rows.clear(); _b = null }

    private fun build() {
        b.list.removeAllViews()
        rows.clear()
        val groups = app.registry.switchable().groupBy { it.group }
        for ((group, modules) in groups) {
            val header = layoutInflater.inflate(R.layout.features_header, b.list, false) as TextView
            header.text = groupTitle(group)
            b.list.addView(header)
            for (m in modules) {
                val r = FeaturesRowBinding.inflate(layoutInflater, b.list, false)
                r.title.text = m.title
                r.description.text = getString(m.descriptionRes)
                r.row.contentDescription = m.title + ", " + getString(m.descriptionRes)
                UiActions.asSwitch(r.row) { m.enabled }
                r.row.setOnClickListener { toggle(m) }
                b.list.addView(r.root)
                rows += m to r
            }
        }
    }

    private fun groupTitle(group: String): String = when (group) {
        "capture" -> getString(R.string.features_group_capture)
        "photos" -> getString(R.string.features_group_photos)
        "talk" -> getString(R.string.features_group_talk)
        "where" -> getString(R.string.features_group_where)
        else -> group.replaceFirstChar { it.uppercase() }
    }

    private fun bind() {
        if (_b == null) return
        val ctx = requireContext()
        for ((m, r) in rows) {
            if (r.toggle.isChecked != m.enabled) r.toggle.isChecked = m.enabled
            val reason = m.blockedReason()
            r.readiness.isVisible = reason != null
            if (reason == null) continue
            val (text, action, onClick) = when {
                reason.startsWith("permission:") -> Triple(
                    getString(R.string.features_needs_permission, UiActions.permissionWord(ctx, reason.removePrefix("permission:"))),
                    getString(R.string.features_grant)) { perms.request(*UiActions.permissionsFor(m)) }
                reason == "open_app_once" -> Triple(getString(R.string.features_open_once),
                    getString(R.string.home_banner_fix_now)) { runCatching { BridgeService.start(ctx) }; Unit }
                reason == "notifications_off" -> Triple(getString(R.string.features_notifications_off),
                    getString(R.string.features_turn_on)) {
                    if (!ctx.granted(Manifest.permission.POST_NOTIFICATIONS)) perms.request(Manifest.permission.POST_NOTIFICATIONS)
                    else UiActions.openAppNotificationSettings(ctx)
                }
                reason == "listener_off" -> Triple(getString(R.string.features_listener_off), null, null)
                else -> Triple(reason, null, null)
            }
            r.readinessText.text = text
            r.readinessAction.isVisible = action != null
            r.readinessAction.text = action
            r.readinessAction.setOnClickListener { onClick?.invoke() }
        }
    }

    private fun toggle(m: Capability) {
        val on = !m.enabled
        UiActions.setSwitch(app, m, on)
        if (on && m.blockedReason()?.startsWith("permission:") == true) perms.request(*UiActions.permissionsFor(m))
        bind()
    }
}
