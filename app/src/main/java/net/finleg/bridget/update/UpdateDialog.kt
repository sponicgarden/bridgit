package net.finleg.bridget.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.finleg.bridget.BridgeApp
import net.finleg.bridget.BuildConfig
import net.finleg.bridget.R
import net.finleg.bridget.capability.appVersion
import net.finleg.bridget.ui.common.Fmt
import net.finleg.bridget.ui.common.dp
import net.finleg.bridget.ui.common.themeColor
import net.finleg.bridget.update.UpdateLogic.Problem

/**
 * The update dialogs (UPD-02, UPD-05): Checking · Up to date · Newer (Download / Not now) ·
 * Downloading (progress, Cancel) · Ready (Install) · Error (reason, OK). One flow at a time;
 * it is tied to the activity, so closing the activity cancels a check or download (and the
 * installer deletes the partial file). Rotation mid-download also cancels it.
 */
object UpdateDialog {
    private const val PREFS = "update"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_RESUME_AT = "resume_after_permission_at"

    private var active: Flow? = null

    val isDebugBuild: Boolean get() = BuildConfig.APPLICATION_ID.endsWith(".debug")

    /** More → Check for updates and Settings → About (UPD-02). */
    fun checkForUpdates(activity: FragmentActivity, manual: Boolean) {
        if (isDebugBuild) {
            if (manual) MaterialAlertDialogBuilder(activity).setTitle(R.string.update_title)
                .setMessage(R.string.update_debug).setPositiveButton(R.string.ok, null).show()
            return // UPD-06
        }
        if (!manual && active != null) return
        active?.close()
        prefs(activity).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        Flow(activity, manual).also { active = it }.start()
    }

    /** At most once a day when the app is opened; silent unless a newer version exists. */
    fun autoCheckIfDue(activity: FragmentActivity) {
        if (isDebugBuild || active != null) return
        val last = prefs(activity).getLong(KEY_LAST_CHECK, 0L)
        if (!UpdateLogic.isAutoCheckDue(System.currentTimeMillis(), last)) return
        checkForUpdates(activity, manual = false)
    }

    /**
     * Turning on "Install unknown apps" can restart Bridgit, which loses the Ready dialog.
     * If the owner went to that page from the dialog in the last 30 minutes and installs are now
     * allowed, run the check again (the download is verified again before Install).
     */
    fun resumeAfterPermission(activity: FragmentActivity) {
        if (isDebugBuild || active != null) return
        val at = prefs(activity).getLong(KEY_RESUME_AT, 0L)
        if (at == 0L) return
        if (System.currentTimeMillis() - at > 30 * 60_000L) { prefs(activity).edit().remove(KEY_RESUME_AT).apply(); return }
        if (!UpdateInstaller.canRequestInstalls(activity)) return
        prefs(activity).edit().remove(KEY_RESUME_AT).apply()
        checkForUpdates(activity, manual = true)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private class Flow(val activity: FragmentActivity, val manual: Boolean) : DefaultLifecycleObserver {
        private val app = activity.application as BridgeApp
        private var dialog: AlertDialog? = null
        private var job: Job? = null
        private var closed = false

        fun start() {
            activity.lifecycle.addObserver(this)
            UpdateInstaller.cleanStale(activity)
            if (manual) showProgress(activity.getString(R.string.update_checking), indeterminate = true) { close() }
            job = activity.lifecycleScope.launch {
                when (val r = UpdateInstaller.check(app)) {
                    is UpdateInstaller.Check.Newer -> showNewer(r.manifest)
                    is UpdateInstaller.Check.UpToDate ->
                        if (manual) message(R.string.update_title, activity.getString(R.string.update_current, app.appVersion()))
                        else close()
                    is UpdateInstaller.Check.LocalAhead ->
                        if (manual) message(R.string.update_title,
                            activity.getString(R.string.update_local_ahead, app.appVersion(), r.manifest.versionName))
                        else close()
                    is UpdateInstaller.Check.Refused -> if (manual) showError(r.problem) else close()
                }
            }
        }

        fun close() {
            if (closed) return
            closed = true
            job?.cancel()
            dismissDialog()
            activity.lifecycle.removeObserver(this)
            if (active === this) active = null
        }

        override fun onDestroy(owner: LifecycleOwner) = close()

        private fun dismissDialog() {
            dialog?.setOnCancelListener(null)
            dialog?.setOnDismissListener(null)
            dialog?.dismiss()
            dialog = null
        }

        private fun show(b: MaterialAlertDialogBuilder, onCancel: () -> Unit = { close() }) {
            if (closed || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED) || activity.isFinishing) {
                close(); return
            }
            dismissDialog()
            dialog = b.setOnCancelListener { onCancel() }.create().also { it.show() }
        }

        private fun message(title: Int, text: String) = show(
            MaterialAlertDialogBuilder(activity).setTitle(title).setMessage(text)
                .setPositiveButton(R.string.ok) { _, _ -> close() })

        private fun showError(p: Problem) = show(
            MaterialAlertDialogBuilder(activity).setTitle(R.string.update_error_title).setMessage(reason(p))
                .setPositiveButton(R.string.ok) { _, _ -> close() })

        private fun showNewer(m: UpdateLogic.Manifest) {
            val text = buildString {
                append(activity.getString(R.string.update_available, m.versionName, app.appVersion()))
                m.notes?.let { append("\n\n").append(activity.getString(R.string.update_notes, it)) }
                append("\n\n").append(activity.getString(R.string.update_size, Fmt.bytes(m.sizeBytes)))
            }
            show(MaterialAlertDialogBuilder(activity).setTitle(R.string.update_available_title).setMessage(text)
                .setPositiveButton(R.string.update_download) { _, _ -> download(m) }
                .setNegativeButton(R.string.update_not_now) { _, _ -> close() })
        }

        private var progressText: TextView? = null
        private var progressBar: LinearProgressIndicator? = null

        /** A dialog with a line of text, a progress bar, an optional hint and [Cancel]. */
        private fun showProgress(text: String, indeterminate: Boolean, hint: String? = null, onCancel: () -> Unit) {
            val ctx = activity
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ctx.dp(24), ctx.dp(16), ctx.dp(24), ctx.dp(8))
            }
            val label = TextView(ctx).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(ctx.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                this.text = text
            }
            val bar = LinearProgressIndicator(ctx).apply {
                isIndeterminate = indeterminate
                max = 100
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(16) }
            }
            val hintView = TextView(ctx).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setTextColor(ctx.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                isVisible = hint != null
                this.text = hint
                setPadding(0, ctx.dp(12), 0, 0)
            }
            box.addView(label); box.addView(bar); box.addView(hintView)
            progressText = label; progressBar = bar
            show(MaterialAlertDialogBuilder(activity).setTitle(R.string.update_title).setView(box)
                .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }, onCancel)
            dialog?.setCanceledOnTouchOutside(false)
        }

        private fun download(m: UpdateLogic.Manifest) {
            job?.cancel()
            showProgress(activity.getString(R.string.update_downloading, m.versionName), indeterminate = false,
                hint = activity.getString(R.string.update_downloading_hint)) { close() }
            job = activity.lifecycleScope.launch {
                val r = UpdateInstaller.download(app, m) { pct ->
                    progressBar?.post {
                        if (closed) return@post
                        progressBar?.setProgressCompat(pct.coerceAtLeast(0), true)
                        progressText?.text = activity.getString(R.string.update_downloading_pct, m.versionName, pct)
                    }
                }
                when (r) {
                    is UpdateInstaller.Download.Ready -> showReady(r)
                    is UpdateInstaller.Download.Refused -> showError(r.problem)
                }
            }
        }

        private fun showReady(r: UpdateInstaller.Download.Ready) {
            progressText = null; progressBar = null
            show(MaterialAlertDialogBuilder(activity).setTitle(R.string.update_ready_title)
                .setMessage(activity.getString(R.string.update_ready, r.manifest.versionName))
                .setPositiveButton(R.string.update_install, null)
                .setNegativeButton(R.string.update_not_now) { _, _ -> r.file.delete(); close() }) {
                r.file.delete(); close()
            }
            // Keep the dialog open on Install, so a first tap that only opens the permission page can be repeated.
            dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { install(r) }
        }

        private fun install(r: UpdateInstaller.Download.Ready) {
            if (!UpdateInstaller.canRequestInstalls(activity)) {
                UpdateInstaller.logInstallBlocked(app)
                MaterialAlertDialogBuilder(activity).setTitle(R.string.update_allow_title).setMessage(R.string.update_allow)
                    .setPositiveButton(R.string.update_open_settings) { _, _ ->
                        prefs(activity).edit().putLong(KEY_RESUME_AT, System.currentTimeMillis()).apply()
                        runCatching { activity.startActivity(UpdateInstaller.unknownSourcesIntent(activity)) }
                    }
                    .setNegativeButton(R.string.update_not_now, null)
                    .show()
                return
            }
            if (!r.file.exists()) { showError(Problem.STORAGE); return }
            try {
                activity.startActivity(UpdateInstaller.installIntent(activity, r.file))
                UpdateInstaller.logInstallHandoff(app, r.manifest)
                // The file stays for the installer to read; it's removed after an hour (UPD-05).
                dismissDialog(); close()
            } catch (e: ActivityNotFoundException) {
                message(R.string.update_error_title, activity.getString(R.string.update_no_installer))
            } catch (e: IllegalArgumentException) {
                showError(Problem.STORAGE)
            }
        }

        private fun reason(p: Problem): String = activity.getString(when (p) {
            Problem.NETWORK -> R.string.update_err_network
            Problem.HTTP_STATUS -> R.string.update_err_http
            Problem.FORBIDDEN -> R.string.update_err_forbidden
            Problem.REDIRECT -> R.string.update_err_redirect
            Problem.BAD_MANIFEST, Problem.WRONG_PACKAGE, Problem.UNSAFE_URL, Problem.BAD_SHA256,
            Problem.BAD_SIZE -> R.string.update_err_manifest
            Problem.TOO_LARGE -> R.string.update_err_too_large
            Problem.SIZE_MISMATCH, Problem.CHECKSUM -> R.string.update_err_mismatch
            Problem.NOT_AN_APK -> R.string.update_err_not_apk
            Problem.ARCHIVE_PACKAGE, Problem.ARCHIVE_VERSION -> R.string.update_err_wrong_build
            Problem.NOT_NEWER -> R.string.update_err_not_newer
            Problem.UNSIGNED, Problem.CERT_MISMATCH -> R.string.update_err_signature
            Problem.STORAGE -> R.string.update_err_storage
        })
    }
}
