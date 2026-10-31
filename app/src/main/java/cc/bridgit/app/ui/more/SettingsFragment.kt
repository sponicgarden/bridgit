package cc.bridgit.app.ui.more

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
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.BuildConfig
import cc.bridgit.app.R
import cc.bridgit.app.alerts.AlertFrequency
import cc.bridgit.app.alerts.AlertMessage
import cc.bridgit.app.alerts.AlertRelay
import cc.bridgit.app.alerts.AlertSender
import cc.bridgit.app.alerts.Alerts
import cc.bridgit.app.capability.appVersion
import cc.bridgit.app.databinding.FragmentSettingsBinding
import cc.bridgit.app.databinding.SettingsDialogFieldBinding
import cc.bridgit.app.databinding.SettingsRowBinding
import cc.bridgit.app.databinding.SettingsSwitchRowBinding
import cc.bridgit.app.interact.InteractionNotifier
import cc.bridgit.app.log.LogQuery
import cc.bridgit.app.ui.common.themeColor
import cc.bridgit.app.ui.home.UiActions
import cc.bridgit.app.update.UpdateDialog
import java.security.MessageDigest
import java.text.NumberFormat

/**
 * Settings (UI-19, ui-spec 5.10), built from our own rows. Sections:
 * Listener, Agent powers (Turn everything on / off), Privacy (log visibility, lock-screen question text, urgent read-aloud in DND),
 * Log, Notifications, Appearance, About.
 * Allow internet callbacks (EVT-05) sits under Listener. Email alerts sit after Notifications.
 * Send crash reports (D32) sits under Privacy. About links the privacy policy and terms; Reset deletes all Bridgit data.
 * Not shown yet (later phases): Screenshots, Feedback,
 * precise locations, address lookup, Licences, and the Files / Feedback notification channels.
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
        prefSwitch(b.internetCallbacks, R.string.settings_internet_callbacks, R.string.settings_internet_callbacks_desc,
            cc.bridgit.app.core.Settings.PREF_INTERNET_CALLBACKS, R.string.settings_changed_internet_callbacks)

        // Agent powers: the same Maximum powers pass as Add agent, and its undo.
        row(b.everythingOn, R.string.settings_everything_on) { cc.bridgit.app.ui.power.PowersFragment.confirmMaximum(this, null) }
        b.everythingOn.value.setText(R.string.settings_everything_on_desc)
        row(b.everythingOff, R.string.settings_everything_off) {
            cc.bridgit.app.ui.power.PowersFragment.confirmAllOff(this) { Snackbar.make(b.root, R.string.power_off_done, Snackbar.LENGTH_SHORT).show() }
        }
        b.everythingOff.value.setText(R.string.settings_everything_off_desc)

        // Privacy
        row(b.logVisibility, R.string.settings_log_visibility) { askVisibility() }
        prefSwitch(b.lockscreenText, R.string.settings_lockscreen_text, R.string.settings_lockscreen_text_desc,
            InteractionNotifier.PREF_LOCKSCREEN_TEXT, R.string.settings_changed_lockscreen)
        prefSwitch(b.speakUrgent, R.string.settings_speak_urgent, R.string.settings_speak_urgent_desc,
            InteractionNotifier.PREF_SPEAK_URGENT_IN_DND, R.string.settings_changed_speak_urgent)
        crashSwitch()

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
        row(b.notifQuestions, R.string.channel_questions) { UiActions.openChannelSettings(requireContext(), InteractionNotifier.CHANNEL_QUESTIONS) }
        b.notifQuestions.value.setText(R.string.channel_questions_desc)
        row(b.notifNotices, R.string.channel_notices) { UiActions.openChannelSettings(requireContext(), InteractionNotifier.CHANNEL_NOTICES) }
        b.notifNotices.value.setText(R.string.channel_notices_desc)
        openOnArrivalSwitch()

        // Email alerts: one confirmed address for every agent, sent through bridgit.cc.
        row(b.alertFrequency, R.string.alerts_frequency) { askAlertFrequency() }
        row(b.alertRecipient, R.string.alerts_recipient) { onAlertRecipient() }
        row(b.alertTest, R.string.alerts_test) { sendTestAlert() }
        row(b.alertWhat, R.string.alerts_what, onClick = null)
        b.alertWhat.value.setText(R.string.alerts_what_desc)

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
                UiActions.copy(requireContext(), "Bridgit certificate", cert)
                Snackbar.make(b.root, R.string.settings_cert_copied, Snackbar.LENGTH_SHORT).show()
            }
        }
        b.cert.value.text = cert ?: "?"
        row(b.spec, R.string.settings_spec, onClick = null)
        b.spec.value.setText(R.string.settings_spec_value)
        row(b.privacyPolicy, R.string.settings_privacy_policy) { openUrl(R.string.settings_privacy_url) }
        b.privacyPolicy.value.setText(R.string.settings_privacy_url)
        row(b.terms, R.string.settings_terms) { openUrl(R.string.settings_terms_url) }
        b.terms.value.setText(R.string.settings_terms_url)

        // Reset
        row(b.deleteAll, R.string.settings_delete_all) { askDeleteAll() }
        b.deleteAll.value.setText(R.string.settings_delete_all_desc)

        bind()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { app.listener.collect { bind() } }
        }
    }

    override fun onStart() {
        super.onStart()
        app.settings.addListener(onSettings)
        if (app.settings.alertEmail != null && app.settings.alertState == Alerts.STATE_PENDING) checkAlertStatus(manual = false)
    }
    override fun onStop() { app.settings.removeListener(onSettings); super.onStop() }
    /** Back from Android's "Display over other apps" page (CAP-56). */
    override fun onResume() { super.onResume(); bind() }
    override fun onDestroyView() { super.onDestroyView(); _b = null }

    private fun row(r: SettingsRowBinding, title: Int, mono: Boolean = false, onClick: (() -> Unit)?) {
        r.title.setText(title)
        if (mono) {
            r.value.setTextAppearance(R.style.TextAppearance_Bridgit_Mono)
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
        b.lockscreenText.toggle.isChecked = prefs().getBoolean(InteractionNotifier.PREF_LOCKSCREEN_TEXT, false)
        b.speakUrgent.toggle.isChecked = prefs().getBoolean(InteractionNotifier.PREF_SPEAK_URGENT_IN_DND, false)
        val overlay = android.provider.Settings.canDrawOverlays(requireContext())
        b.openOnArrival.toggle.isChecked = overlay && prefs().getBoolean(InteractionNotifier.PREF_OPEN_ON_ARRIVAL, false)
        b.openOnArrival.value.setText(if (overlay) R.string.settings_open_on_arrival_desc else R.string.settings_open_on_arrival_needs)
        b.openOnArrival.root.contentDescription = "${b.openOnArrival.title.text}, ${b.openOnArrival.value.text}"
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
        val freq = AlertFrequency.of(st.alertFrequency)
        val confirmed = st.alertState == Alerts.STATE_CONFIRMED
        b.alertFrequency.value.text = getString(frequencyLabel(freq)).let {
            if (freq != AlertFrequency.NONE && !confirmed) getString(R.string.alerts_needs_address, it) else it
        }
        val email = st.alertEmail
        b.alertRecipient.value.text = when {
            email == null || st.alertState == Alerts.STATE_NONE -> getString(R.string.alerts_recipient_none)
            confirmed -> getString(R.string.alerts_recipient_confirmed, email)
            else -> getString(R.string.alerts_recipient_pending, email)
        }
        b.alertTest.root.isEnabled = confirmed
        b.alertTest.root.alpha = if (confirmed) 1f else 0.5f
        b.alertTest.value.setText(if (confirmed) R.string.alerts_test_ready else R.string.alerts_test_desc)
        listOf(b.port, b.bind, b.logVisibility, b.keep, b.theme, b.alertFrequency, b.alertRecipient, b.alertTest, b.alertWhat, b.version, b.checkUpdates, b.commit, b.cert, b.spec, b.privacyPolicy, b.terms, b.deleteAll, b.export, b.clear,
            b.notifListener, b.notifCapture, b.notifQuestions, b.notifNotices).forEach { it.root.contentDescription = "${it.title.text}, ${it.value.text}" }
    }

    private fun audit(message: String) = app.log.system("audit", "setting", message)

    private fun prefs() = requireContext().getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    /** A switch row for a plain boolean in the "settings" prefs (default off); every change is audited (UI-18). */
    private fun prefSwitch(r: SettingsSwitchRowBinding, title: Int, desc: Int, key: String, changed: Int) {
        r.title.setText(title)
        r.value.setText(desc)
        r.root.contentDescription = getString(title) + ", " + getString(desc)
        UiActions.asSwitch(r.root) { prefs().getBoolean(key, false) }
        r.root.setOnClickListener {
            val on = !prefs().getBoolean(key, false)
            prefs().edit().putBoolean(key, on).apply()
            r.toggle.isChecked = on
            audit(getString(changed, getString(if (on) R.string.home_on else R.string.home_off)))
        }
    }

    /**
     * CAP-56: on only when the pref is set and "Display over other apps" is granted. Turning it on without
     * the grant sets the pref and opens Android's page for Bridgit; [onResume] shows the result.
     */
    private fun openOnArrivalSwitch() {
        val r = b.openOnArrival
        val key = InteractionNotifier.PREF_OPEN_ON_ARRIVAL
        r.title.setText(R.string.settings_open_on_arrival)
        UiActions.asSwitch(r.root) { android.provider.Settings.canDrawOverlays(requireContext()) && prefs().getBoolean(key, false) }
        r.root.setOnClickListener {
            val granted = android.provider.Settings.canDrawOverlays(requireContext())
            val on = !(granted && prefs().getBoolean(key, false))
            prefs().edit().putBoolean(key, on).apply()
            if (on && !granted) runCatching {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${requireContext().packageName}")))
            }
            bind()
            audit(getString(R.string.settings_changed_open_on_arrival, getString(if (on) R.string.home_on else R.string.home_off)))
        }
    }

    /** D32: crash reports are on unless turned off, so this switch reads the inverted pref. */
    private fun crashSwitch() {
        val r = b.crashReports
        if (!cc.bridgit.app.core.CrashReports.configured) { r.root.visibility = View.GONE; return }
        val key = cc.bridgit.app.core.CrashReports.PREF
        r.title.setText(R.string.settings_crash_reports)
        r.value.setText(R.string.settings_crash_reports_desc)
        r.root.contentDescription = getString(R.string.settings_crash_reports) + ", " + getString(R.string.settings_crash_reports_desc)
        UiActions.asSwitch(r.root) { !prefs().getBoolean(key, false) }
        r.root.setOnClickListener {
            val on = prefs().getBoolean(key, false) // currently off, so this turns it on
            prefs().edit().putBoolean(key, !on).apply()
            r.toggle.isChecked = on
            if (on) cc.bridgit.app.core.CrashReports.start(requireContext().applicationContext)
            else cc.bridgit.app.core.CrashReports.stop()
            audit(getString(R.string.settings_changed_crash_reports, getString(if (on) R.string.home_on else R.string.home_off)))
        }
    }

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

    private fun openUrl(url: Int) {
        val uri = getString(url)
        if (!UiActions.openUrl(requireContext(), uri)) Snackbar.make(b.root, getString(R.string.settings_no_browser, uri), Snackbar.LENGTH_LONG).show()
    }

    /**
     * Wipes every store at once with Android's own "Clear storage" (prefs, databases, files, cache and
     * Keystore keys), so no store can be missed; Android then kills the process and the next start is first run.
     * Before that: put back phone settings agents changed (their leases live in that storage) and cancel email alerts.
     */
    private fun askDeleteAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_delete_all_title)
            .setMessage(R.string.settings_delete_all_confirm)
            .setPositiveButton(R.string.settings_delete_all_action) { _, _ ->
                val ctx = requireContext().applicationContext
                b.deleteAll.root.isEnabled = false
                // NonCancellable: leaving or rotating mid-way must not skip the wipe after the restores ran.
                viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.NonCancellable) {
                    withContext(Dispatchers.IO) {
                        runCatching { app.hardware.stop(owner = true) }
                        if (app.settings.alertEmail != null) runCatching { app.alerts.relay.unsubscribe() }
                    }
                    val am = ctx.getSystemService(android.app.ActivityManager::class.java)
                    if (!am.clearApplicationUserData()) {
                        _b?.let { it.deleteAll.root.isEnabled = true; Snackbar.make(it.root, R.string.settings_delete_all_failed, Snackbar.LENGTH_LONG).show() }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

    // ---- Email alerts ----

    private fun frequencyLabel(f: AlertFrequency) = when (f) {
        AlertFrequency.EVERY_ACTION -> R.string.alerts_every_short
        AlertFrequency.HOURLY -> R.string.alerts_hourly
        AlertFrequency.DAILY -> R.string.alerts_daily
        AlertFrequency.WEEKLY -> R.string.alerts_weekly
        AlertFrequency.NONE -> R.string.alerts_none
    }

    private fun askAlertFrequency() {
        val values = AlertFrequency.values()
        val labels = values.map { getString(if (it == AlertFrequency.EVERY_ACTION) R.string.alerts_every else frequencyLabel(it)) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.alerts_frequency)
            .setSingleChoiceItems(labels, values.indexOf(AlertFrequency.of(app.settings.alertFrequency))) { d, which ->
                val v = values[which]
                d.dismiss()
                if (v.id != app.settings.alertFrequency) {
                    app.settings.alertFrequency = v.id
                    audit(getString(R.string.settings_changed_alerts_frequency, getString(frequencyLabel(v))))
                    background { app.alerts.poke() }
                }
                bind()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Pending: tapping checks whether the link was clicked. Otherwise: set, change or remove the address. */
    private fun onAlertRecipient() {
        if (app.settings.alertEmail != null && app.settings.alertState == Alerts.STATE_PENDING) { checkAlertStatus(manual = true); return }
        val f = SettingsDialogFieldBinding.inflate(layoutInflater)
        f.field1.hint = getString(R.string.alerts_recipient_hint)
        f.input1.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        f.input1.filters = arrayOf(android.text.InputFilter.LengthFilter(254))
        f.input1.setText(app.settings.alertEmail ?: "")
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.alerts_recipient)
            .setView(f.root)
            .setPositiveButton(R.string.alerts_send_confirm, null)
            .setNegativeButton(R.string.cancel, null)
        if (app.settings.alertEmail != null) builder.setNeutralButton(R.string.alerts_remove) { _, _ -> removeAlertAddress() }
        val d = builder.create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val email = f.input1.text?.toString()?.trim()?.lowercase().orEmpty()
                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) { f.field1.error = getString(R.string.alerts_recipient_invalid); return@setOnClickListener }
                d.dismiss()
                subscribeAlerts(email)
            }
        }
        d.show()
    }

    private fun subscribeAlerts(email: String) = viewLifecycleOwner.lifecycleScope.launch {
        val r = withContext(Dispatchers.IO) { app.alerts.relay.subscribe(email) }
        if (r == AlertRelay.Subscribe.PENDING) {
            app.settings.alertEmail = email
            app.settings.alertState = Alerts.STATE_PENDING
            audit(getString(R.string.settings_changed_alerts_email)) // not the address: agents can read the log
        }
        _b?.let { Snackbar.make(it.root, when (r) {
            AlertRelay.Subscribe.PENDING -> getString(R.string.alerts_confirm_sent, email)
            AlertRelay.Subscribe.BAD_EMAIL -> getString(R.string.alerts_recipient_invalid)
            AlertRelay.Subscribe.RATE_LIMITED -> getString(R.string.alerts_rate_limited)
            AlertRelay.Subscribe.FAILED -> getString(R.string.alerts_failed)
        }, Snackbar.LENGTH_LONG).show() }
        bind()
    }

    private fun removeAlertAddress() = viewLifecycleOwner.lifecycleScope.launch {
        if (!withContext(Dispatchers.IO) { app.alerts.relay.unsubscribe() }) {
            _b?.let { Snackbar.make(it.root, R.string.alerts_failed, Snackbar.LENGTH_LONG).show() }
            return@launch
        }
        app.settings.alertEmail = null
        app.settings.alertState = Alerts.STATE_NONE
        audit(getString(R.string.settings_changed_alerts_removed))
        _b?.let { Snackbar.make(it.root, R.string.alerts_removed, Snackbar.LENGTH_SHORT).show() }
        bind()
    }

    /** Asks bridgit.cc whether the confirmation link was clicked; called on start while pending, and on tap. */
    private fun checkAlertStatus(manual: Boolean) = viewLifecycleOwner.lifecycleScope.launch {
        val s = withContext(Dispatchers.IO) { app.alerts.relay.status() }
        when (s) {
            AlertRelay.State.CONFIRMED -> if (app.settings.alertState != Alerts.STATE_CONFIRMED) {
                app.settings.alertState = Alerts.STATE_CONFIRMED
                background { app.alerts.poke() }
                _b?.let { Snackbar.make(it.root, R.string.alerts_now_confirmed, Snackbar.LENGTH_SHORT).show() }
            }
            AlertRelay.State.NONE -> app.settings.alertState = Alerts.STATE_NONE
            AlertRelay.State.PENDING -> if (manual) _b?.let { Snackbar.make(it.root, R.string.alerts_still_pending, Snackbar.LENGTH_LONG).show() }
            AlertRelay.State.UNKNOWN -> if (manual) _b?.let { Snackbar.make(it.root, R.string.alerts_failed, Snackbar.LENGTH_LONG).show() }
        }
        bind()
    }

    private fun sendTestAlert() = viewLifecycleOwner.lifecycleScope.launch {
        val freq = getString(frequencyLabel(AlertFrequency.of(app.settings.alertFrequency)))
        val msg = AlertMessage(getString(R.string.alerts_test_subject),
            getString(R.string.alerts_test_text, android.os.Build.MODEL ?: "your phone", freq), "test-${System.currentTimeMillis()}")
        val r = withContext(Dispatchers.IO) { app.alerts.relay.send(msg) }
        if (r == AlertSender.Result.GONE) app.settings.alertState = Alerts.STATE_NONE
        _b?.let { Snackbar.make(it.root, if (r == AlertSender.Result.SENT) R.string.alerts_test_sent else R.string.alerts_failed, Snackbar.LENGTH_SHORT).show() }
        bind()
    }

    private fun background(block: () -> Unit) { viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { runCatching(block) } }

    /** SHA-256 of the current signing certificate, as colon-separated upper-case hex. */
    private fun certificateSha256(): String? = runCatching {
        val ctx = requireContext()
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val cert = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
        MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString(":") { "%02X".format(it) }
    }.getOrNull()
}
