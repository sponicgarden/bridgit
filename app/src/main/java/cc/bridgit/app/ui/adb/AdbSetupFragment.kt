package cc.bridgit.app.ui.adb

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.adb.AdbClient
import cc.bridgit.app.adb.AdbGrants
import cc.bridgit.app.adb.AdbPairing
import cc.bridgit.app.capability.granted
import cc.bridgit.app.databinding.AccessRowBinding
import cc.bridgit.app.databinding.FragmentAdbSetupBinding
import cc.bridgit.app.ui.common.dp
import cc.bridgit.app.ui.common.themeColor

/**
 * ADB setup (8.26): walks the owner from Developer options to a paired Bridgit, then grants every access
 * Bridgit can use in one tap, and lets developer agents run shell commands. Rows re-check every second
 * while the screen is open, so a change made in Settings shows as soon as the owner comes back.
 */
class AdbSetupFragment : Fragment() {
    private var _b: FragmentAdbSetupBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp
    private var granting = false
    private var lastSig: List<Boolean>? = null
    // The code goes into a notification, which Android drops silently without this permission.
    private val notifRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) Toast.makeText(requireContext(), "Without notifications, open Bridgit in split screen and type the code here.", Toast.LENGTH_LONG).show()
        pair()
    }

    private class Step(val title: String, val how: String, val done: Boolean, val button: String?, val action: () -> Unit)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentAdbSetupBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(b.toolbar) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top); insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.scroll) { v, insets ->
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom); insets
        }
        b.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        b.intro.text = "Bridgit can use ADB on this phone by itself, with no computer. Agents can then tap, type, open apps and " +
            "change settings for you, and Bridgit gets every permission it can use in one tap. Each step below shows when it's done."
        b.useCode.setOnClickListener { AdbPairing.start(requireContext()); AdbPairing.submit(requireContext(), b.code.text?.toString().orEmpty()) }
        b.grantAll.setOnClickListener { grant(null) }
        b.agentsSwitch.setOnCheckedChangeListener { _, on ->
            if (on != app.settings.isOn("adb")) {
                app.settings.setOn("adb", on)
                app.log.system("audit", "setting", "ADB control for agents switched ${if (on) "on" else "off"}")
            }
        }
        b.forget.setOnClickListener { app.adb.forget(); refresh() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { AdbPairing.state.collect { s -> _b?.pairState?.let { it.isVisible = s != null; it.text = s }; refresh() } }
                while (true) { refresh(); delay(1000) }
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null; lastSig = null }

    private fun refresh() {
        val bb = _b ?: return
        val ctx = requireContext()
        val dev = AdbClient.developerOptions(ctx)
        val wifi = AdbClient.wirelessDebugging(ctx)
        val paired = app.adb.paired
        val grants = AdbGrants.all.map { it.check(ctx) == true }
        // Rebuild only when something changed, so TalkBack focus and UI tests aren't disturbed every second.
        val sig = listOf(dev, wifi, paired, granting, app.settings.isOn("adb")) + grants
        if (sig == lastSig) return
        lastSig = sig
        fill(bb.steps, listOf(
            Step("1. Developer options", "Settings → About phone (Samsung: Software information) → tap Build number 7 times.",
                dev, "Open") { open(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) },
            Step("2. Wireless debugging", "Developer options → Wireless debugging → on. The phone must be on Wi-Fi.",
                wifi, "Open") { openWirelessDebugging() },
            Step("3. Pair Bridgit", "Tap Pair. In Wireless debugging tap Pair device with pairing code, then pull down Bridgit's notification, open it with its arrow and tap Enter code.",
                paired, "Pair") {
                    if (ctx.granted(Manifest.permission.POST_NOTIFICATIONS)) pair() else notifRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
        ))
        bb.codeRow.isVisible = !paired
        bb.grantAll.isEnabled = paired && !granting
        bb.forget.isVisible = paired
        fill(bb.grants, AdbGrants.all.mapIndexed { i, g ->
            Step(g.title, g.unlocks, grants[i], if (paired && !granting) "Grant" else null) { grant(setOf(g.key)) }
        })
        if (bb.agentsSwitch.isChecked != app.settings.isOn("adb")) bb.agentsSwitch.isChecked = app.settings.isOn("adb")
    }

    private fun grant(only: Set<String>?) {
        granting = true; refresh()
        b.grantOutput.isVisible = true
        b.grantOutput.text = "Working…"
        viewLifecycleOwner.lifecycleScope.launch {
            val lines = runCatching {
                AdbGrants.grantAll(app, only) { step -> launch(Dispatchers.Main) { _b?.grantOutput?.text = "Working: $step…" } }
            }.getOrElse { listOf("Couldn't reach ADB: ${it.message}") }
            granting = false
            _b?.grantOutput?.text = lines.joinToString("\n")
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    private fun open(i: Intent) { runCatching { startActivity(i) }.onFailure { Toast.makeText(requireContext(), "Settings didn't open", Toast.LENGTH_SHORT).show() } }

    /** Wireless debugging has no public intent; AOSP and One UI both have this activity. Falls back to Developer options. */
    private fun pair() = AdbPairing.connectOrPair(requireContext()) { if (isAdded) openWirelessDebugging() }

    private fun openWirelessDebugging() {
        val direct = Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$WirelessDebuggingActivity"))
        // One UI has no such activity: open Developer options with the Wireless debugging row highlighted instead.
        runCatching { startActivity(direct) }.onFailure {
            open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(":settings:fragment_args_key", "toggle_adb_wireless"))
        }
    }

    private fun fill(parent: LinearLayout, steps: List<Step>) {
        parent.removeAllViews()
        val ctx = parent.context
        for (s in steps) {
            val r = AccessRowBinding.inflate(layoutInflater, parent, false)
            r.title.text = s.title
            r.unlocks.text = s.how
            r.status.setText(if (s.done) R.string.access_status_on else R.string.access_status_off)
            val (bg, fg) = if (s.done) R.attr.colorSuccessContainer to R.attr.colorOnSuccessContainer
                else com.google.android.material.R.attr.colorSurfaceContainerHigh to com.google.android.material.R.attr.colorOnSurfaceVariant
            r.status.backgroundTintList = ColorStateList.valueOf(ctx.themeColor(bg))
            r.status.setTextColor(ctx.themeColor(fg))
            if (s.done) ContextCompat.getDrawable(ctx, R.drawable.ic_check)?.mutate()?.let { d ->
                d.setBounds(0, 0, ctx.dp(14), ctx.dp(14)); d.setTint(ctx.themeColor(fg)); r.status.setCompoundDrawablesRelative(d, null, null, null)
            }
            r.action.isVisible = s.button != null && !s.done
            r.action.text = s.button
            r.action.setOnClickListener { s.action() }
            parent.addView(r.root)
        }
    }
}
