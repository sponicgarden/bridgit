package net.finleg.bridget.ui.more

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.finleg.bridget.BridgeApp
import net.finleg.bridget.BridgeService
import net.finleg.bridget.BuildConfig
import net.finleg.bridget.R
import net.finleg.bridget.capability.appVersion
import net.finleg.bridget.databinding.FragmentSettingsBinding
import net.finleg.bridget.databinding.SettingsDialogFieldBinding
import net.finleg.bridget.databinding.SettingsRowBinding
import net.finleg.bridget.log.LogQuery
import net.finleg.bridget.ui.common.themeColor
import net.finleg.bridget.ui.home.UiActions
import net.finleg.bridget.update.UpdateDialog
import java.security.MessageDigest
import java.text.NumberFormat

/**
 * Settings (UI-19, ui-spec 5.10), built from our own rows. P1a sections only:
 * Listener, Privacy (log visibility), Log, Notifications, Appearance, About.
 * Not shown yet (later phases): Allow internet callbacks, Screenshots, Feedback,
 * lock-screen question text, precise locations, address lookup, Licences, and the
 * Questions / Notices / Files / Feedback notification channels.
 */
class SettingsFragment : Fragment() {
    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp
    private val onSettings: () -> Unit = { view?.post { bind() } }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentSettingsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            b.toolbar.updatePadding(top = bars.top)
            b.scroll.updatePadding(bottom = bars.bottom); insets
        }
        b.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        // Listener
        b.alwaysOn.title.setText(R.string.settings_always_on)
        b.alwaysOn.value.setText(R.string.settings_always_on_desc)
        b.alwaysOn.root.contentDescription = getString(R.string.settings_always_on) + ", " + getString(R.string.settings_always_on_desc)
        UiActions.asSwitch(b.alwaysOn.root) { app.settings.listenerEnabled }
        b.alwaysOn.root.setOnClickListener { setListener(!app.settings.listenerEnabled) }
        row(b.port, R.string.settings_port, mono = true) { askPort() }
        row(b.bind, R.string.settings_bind, mono = true, onClick = null)

        // Privacy
        row(b.logVisibility, R.string.settings_log_visibility) { askVisibility() }

        // Log
        row(b.keep, R.string.settings_keep) { askKeep() }
        row(b.export, R.string.settings_export) {
            val nav = findNavController()
            nav.navigate(R.id.activity, null, NavOptions.Builder().setLaunchSingleTop(true).setRestoreState(true)
                .setPopUpTo(nav.graph.startDestinationId, inclusive = false, saveState = true).build())
        }
        b.export.value.setText(R.string.settings_export_desc)
        row(b.clear, R.string.settings_clear) { askClear() }
        b.clear.value.setText(R.string.settings_clear_desc)

        // Notifications: one row per channel P1a creates.
        row(b.notifListener, R.string.channel_listener) { UiActions.openChannelSettings(requireContext(), BridgeApp.CHANNEL_LISTENER) }
        b.notifListener.value.setText(R.string.channel_listener_desc)
        row(b.notifCapture, R.string.channel_capture) { UiActions.openChannelSettings(requireContext(), BridgeApp.CHANNEL_CAPTURE) }
        b.notifCapture.value.setText(R.string.channel_capture_desc)

        // Appearance
        row(b.theme, R.string.settings_theme) { askAppearance() }

        // About
        row(b.version, R.string.settings_version, onClick = null)
        b.version.value.text = getString(R.string.settings_version_value, app.appVersion(), BuildConfig.BUILD_TYPE)
        // UPD-02, UPD-06: debug builds don't check; the row says how to update them.
        row(b.checkUpdates, R.string.update_check) { UpdateDialog.checkForUpdates(requireActivity(), manual = true) }
        b.checkUpdates.value.text = if (UpdateDialog.isDebugBuild) getString(R.string.update_debug) else app.appVersion()
        row(b.commit, R.string.settings_commit, mono = true, onClick = null)
        b.commit.value.text = if (BuildConfig.DIRTY_BUILD) getString(R.string.more_dirty, BuildConfig.GIT_COMMIT) else BuildConfig.GIT_COMMIT
        val cert = certificateSha256()
        row(b.cert, R.string.settings_cert, mono = true) {
            if (cert != null) {
                UiActions.copy(requireContext(), "Bridget certificate", cert)
                Snackbar.make(b.root, R.string.settings_cert_copied, Snackbar.LENGTH_SHORT).show()
            }
        }
        b.cert.value.text = cert ?: "?"
        row(b.spec, R.string.settings_spec, onClick = null)
        b.spec.value.setText(R.string.settings_spec_value)

        bind()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { app.listener.collect { bind() } }
        }
    }

    override fun onStart() { super.onStart(); app.settings.addListener(onSettings) }
    override fun onStop() { app.settings.removeListener(onSettings); super.onStop() }
    override fun onDestroyView() { super.onDestroyView(); _b = null }

    private fun row(r: SettingsRowBinding, title: Int, mono: Boolean = false, onClick: (() -> Unit)?) {
        r.title.setText(title)
        if (mono) {
            r.value.setTextAppearance(R.style.TextAppearance_Bridget_Mono)
            r.value.setTextColor(requireContext().themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        if (onClick == null) { r.root.isClickable = false; r.root.isFocusable = true; r.root.background = null }
        else r.root.setOnClickListener { onClick() }
    }

    private fun bind() {
        if (_b == null) return
        val st = app.settings
        val s = app.listener.value
        if (b.alwaysOn.toggle.isChecked != st.listenerEnabled) b.alwaysOn.toggle.isChecked = st.listenerEnabled
        b.port.value.text = st.port.toString()
        b.bind.value.text = if (s.running && s.boundTo.isNotEmpty())
            s.boundTo.joinToString(", ") { if (it.contains(':')) "[$it]:${s.port}" else "$it:${s.port}" }
            else getString(R.string.settings_not_listening)
        b.logVisibility.value.setText(if (st.logVisibility == "own") R.string.settings_log_own else R.string.settings_log_all)
        b.keep.value.text = getString(R.string.settings_keep_value, NumberFormat.getIntegerInstance().format(st.retentionEntries), st.retentionDays)
        b.theme.value.setText(when (st.appearance) {
            "light" -> R.string.settings_theme_light
            "dark" -> R.string.settings_theme_dark
            else -> R.string.settings_theme_system
        })
        listOf(b.port, b.bind, b.logVisibility, b.keep, b.theme, b.version, b.checkUpdates, b.commit, b.cert, b.spec, b.export, b.clear,
            b.notifListener, b.notifCapture).forEach { it.root.contentDescription = "${it.title.text}, ${it.value.text}" }
    }

    private fun audit(message: String) = app.log.system("audit", "setting", message)

    private fun setListener(on: Boolean) {
        app.settings.listenerEnabled = on
        runCatching { if (on) BridgeService.start(requireContext()) else BridgeService.pause(requireContext()) }
        audit(getString(R.string.settings_changed_listener, getString(if (on) R.string.home_on else R.string.home_off)))
        bind()
    }

    // ---- Dialogs ----

    /** A dialog whose positive button only closes when [onSave] returns true. */
    private fun fieldDialog(title: Int, setup: (SettingsDialogFieldBinding) -> Unit, onSave: (SettingsDialogFieldBinding) -> Boolean) {
        val f = SettingsDialogFieldBinding.inflate(layoutInflater)
        setup(f)
        val d = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(f.root)
            .setPositiveButton(R.string.settings_save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { if (onSave(f)) d.dismiss() }
        }
        d.show()
    }

    private fun askPort() {
        fieldDialog(R.string.settings_port, { f ->
            f.field1.hint = getString(R.string.settings_port_hint)
            f.input1.setText(app.settings.port.toString())
        }) { f ->
            val n = f.input1.text?.toString()?.trim()?.toIntOrNull()
            if (n == null || n !in 1024..65535) { f.field1.error = getString(R.string.settings_port_invalid); return@fieldDialog false }
            if (n != app.settings.port) confirmPort(n)
            true
        }
    }

    private fun confirmPort(n: Int) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.settings_port_warning)
            .setPositiveButton(R.string.settings_change_port) { _, _ ->
                app.settings.port = n
                BridgeService.running?.poke("port changed")
                audit(getString(R.string.settings_changed_port, n))
                bind()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askVisibility() {
        val values = arrayOf("all", "own")
        val labels = arrayOf(getString(R.string.settings_log_all), getString(R.string.settings_log_own))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_log_visibility)
            .setSingleChoiceItems(labels, values.indexOf(app.settings.logVisibility).coerceAtLeast(0)) { d, which ->
                val v = values[which]
                if (v != app.settings.logVisibility) {
                    app.settings.logVisibility = v
                    audit(getString(R.string.settings_changed_visibility,
                        getString(if (v == "own") R.string.settings_visibility_own else R.string.settings_visibility_all)))
                }
                d.dismiss()
                bind()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askKeep() {
        fieldDialog(R.string.settings_keep, { f ->
            f.field1.hint = getString(R.string.settings_keep_entries)
            f.input1.setText(app.settings.retentionEntries.toString())
            f.field2.isVisible = true
            f.field2.hint = getString(R.string.settings_keep_days)
            f.input2.setText(app.settings.retentionDays.toString())
        }) { f ->
            val entries = f.input1.text?.toString()?.trim()?.replace(",", "")?.toIntOrNull()
            val days = f.input2.text?.toString()?.trim()?.toIntOrNull()
            val okEntries = entries != null && entries in 1_000..100_000
            val okDays = days != null && days in 7..365
            f.field1.error = if (okEntries) null else getString(R.string.settings_keep_entries_invalid)
            f.field2.error = if (okDays) null else getString(R.string.settings_keep_days_invalid)
            if (!okEntries || !okDays) return@fieldDialog false
            if (entries != app.settings.retentionEntries || days != app.settings.retentionDays) {
                app.settings.retentionEntries = entries!!
                app.settings.retentionDays = days!!
                audit(getString(R.string.settings_changed_keep, entries, days))
            }
            bind()
            true
        }
    }

    /** LOG-05: deletes the audit and agent channels; LogStore.clear writes the entry recording it. */
    private fun askClear() {
        viewLifecycleOwner.lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { app.log.count(LogQuery(channels = listOf("audit", "agent"))) }
            if (_b == null) return@launch
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(R.string.settings_clear_confirm, n))
                .setPositiveButton(R.string.settings_clear_action) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { app.log.clear("You") }
                        _b?.let { Snackbar.make(it.root, R.string.settings_cleared, Snackbar.LENGTH_SHORT).show() }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun askAppearance() {
        val values = arrayOf("system", "light", "dark")
        val labels = arrayOf(getString(R.string.settings_theme_system), getString(R.string.settings_theme_light), getString(R.string.settings_theme_dark))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_theme)
            .setSingleChoiceItems(labels, values.indexOf(app.settings.appearance).coerceAtLeast(0)) { d, which ->
                d.dismiss()
                if (values[which] != app.settings.appearance) {
                    app.settings.appearance = values[which]
                    app.applyAppearance()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** SHA-256 of the current signing certificate, as colon-separated upper-case hex. */
    private fun certificateSha256(): String? = runCatching {
        val ctx = requireContext()
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val cert = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
        MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString(":") { "%02X".format(it) }
    }.getOrNull()
}
