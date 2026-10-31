package net.finleg.bridget.ui.more

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import net.finleg.bridget.BridgeApp
import net.finleg.bridget.BridgeService
import net.finleg.bridget.R
import net.finleg.bridget.capability.Perms
import net.finleg.bridget.capability.granted
import net.finleg.bridget.databinding.AccessRowBinding
import net.finleg.bridget.databinding.AccessSheetHowBinding
import net.finleg.bridget.databinding.FragmentAccessBinding
import net.finleg.bridget.ui.common.dp
import net.finleg.bridget.ui.common.themeColor
import net.finleg.bridget.update.UpdateInstaller

/**
 * Access (UI-25 … UI-27, ui-spec 5.9, Appendix E): every Android permission or special access
 * Bridgit uses in this release, with its status and one button to the exact page.
 * Rows re-check in onResume. Rows for features that aren't built yet are not listed.
 */
class AccessFragment : Fragment() {
    private var _b: FragmentAccessBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp

    enum class Status(@StringRes val label: Int) {
        ON(R.string.access_status_on), OFF(R.string.access_status_off),
        PARTLY(R.string.access_status_partly),
        /**
         * UI-27 "Restricted setting": Android blocks some special access for apps installed from a file.
         * Android doesn't say whether the block is on, so the Screenshots row shows Off and its
         * Show me how steps cover allowing restricted settings.
         */
        BLOCKED(R.string.access_status_blocked),
    }

    /** One row. [extra] is a short note for Partly ("Only the photos you picked"). */
    data class Item(
        val key: String,
        @StringRes val title: Int,
        @StringRes val unlocks: Int,
        val status: Status,
        @StringRes val button: Int,
        @ArrayRes val steps: Int,
        @StringRes val extra: Int? = null,
        val action: () -> Unit,
    )

    private var lastStatus: Map<String, Status> = emptyMap()
    private var permFallback: (() -> Unit)? = null
    private var requestedAt = 0L

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val ctx = context ?: return@registerForActivityResult
        val granted = result.values.any { it }
        if (!granted && result.isNotEmpty()) {
            // No rationale after a denial means Android didn't ask (denied for good), so open the page where it can be changed.
            // The quick answer rules out a dismissed or just-denied dialog.
            val noDialog = android.os.SystemClock.elapsedRealtime() - requestedAt < NO_DIALOG_MS
            if (noDialog && result.keys.none { shouldShowRequestPermissionRationale(it) }) (permFallback ?: { openAppInfo() }).invoke()
        }
        if ((result[Manifest.permission.CAMERA] == true || result[Manifest.permission.RECORD_AUDIO] == true) &&
            app.settings.listenerEnabled) runCatching { BridgeService.start(ctx) }
        permFallback = null
        refresh()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentAccessBinding.inflate(inflater, container, false)
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
        b.showHow.setOnClickListener { HowSheet().show(childFragmentManager, "how") }
        b.appInfo.setOnClickListener { openAppInfo() }
        b.accessContent.addView(com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = "Device tools and additional access"
            setOnClickListener { startActivity(Intent(requireContext(), net.finleg.bridget.planned.OwnerToolsActivity::class.java)) }
        })
    }

    override fun onResume() {
        super.onResume()
        refresh() // UI-25: rows re-check on resume.
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }

    // ---- Status ----

    private fun granted(p: String) = requireContext().granted(p)

    /** The rows for this release, in display order. */
    fun items(): List<Item> {
        val ctx = requireContext()
        val notifGranted = granted(Manifest.permission.POST_NOTIFICATIONS)
        val notif = when {
            notifGranted && app.captureNotificationsOn() -> Status.ON
            notifGranted -> Status.PARTLY
            else -> Status.OFF
        }
        val photos = when {
            granted(Manifest.permission.READ_MEDIA_IMAGES) -> Status.ON
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Status.PARTLY
            else -> Status.OFF
        }
        val screenshots = if (net.finleg.bridget.capability.ScreenshotService.isEnabled(ctx)) Status.ON else Status.OFF
        val installs = if (UpdateInstaller.canRequestInstalls(ctx)) Status.ON else Status.OFF
        val battery = if (ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true)
            Status.ON else Status.OFF
        return listOf(
            Item("notifications", R.string.access_notifications, R.string.access_notifications_unlocks, notif,
                R.string.access_btn_turn_on, R.array.access_how_notifications,
                extra = if (notif == Status.PARTLY) R.string.access_notifications_partly else null) {
                if (!granted(Manifest.permission.POST_NOTIFICATIONS))
                    request(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) { openNotificationSettings() }
                else openNotificationSettings()
            },
            runtimeItem("camera", R.string.access_camera, R.string.access_camera_unlocks, Manifest.permission.CAMERA,
                R.array.access_how_camera),
            runtimeItem("mic", R.string.access_mic, R.string.access_mic_unlocks, Manifest.permission.RECORD_AUDIO,
                R.array.access_how_mic),
            Item("photos", R.string.access_photos, R.string.access_photos_unlocks, photos,
                if (photos == Status.PARTLY) R.string.access_btn_change else R.string.access_btn_grant, R.array.access_how_photos,
                extra = if (photos == Status.PARTLY) R.string.access_photos_partly else null) {
                if (photos == Status.PARTLY) openAppInfo()
                else request(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
            },
            // 8.10 (CAP-26c): sideloaded installs hit "Restricted setting" here; step 3 of Show me how covers it.
            Item("screenshots", R.string.access_screenshots, R.string.access_screenshots_unlocks, screenshots,
                R.string.access_btn_turn_on, R.array.access_how_screenshots) { openScreenshotService() },
            Item("battery", R.string.access_battery, R.string.access_battery_unlocks, battery,
                R.string.access_btn_turn_on, R.array.access_how_battery) {
                open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            },
        ) + listOfNotNull(
            // UPD-04: Android asks for this the first time Bridgit installs its own update.
            // Debug builds never update themselves (UPD-06), so they don't show the row.
            if (net.finleg.bridget.BuildConfig.DEBUG) null else
            Item("installs", R.string.access_installs, R.string.access_installs_unlocks, installs,
                R.string.access_btn_turn_on, R.array.access_how_installs) {
                open(UpdateInstaller.unknownSourcesIntent(ctx))
            },
        )
    }

    private fun runtimeItem(key: String, @StringRes title: Int, @StringRes unlocks: Int, perm: String, @ArrayRes steps: Int) =
        Item(key, title, unlocks, if (granted(perm)) Status.ON else Status.OFF, R.string.access_btn_grant, steps) {
            request(arrayOf(perm))
        }

    private fun refresh() {
        val bb = _b ?: return
        val all = items()
        val needs = all.filter { it.status != Status.ON }.sortedBy { if (it.status == Status.BLOCKED) 0 else 1 }
        val ok = all.filter { it.status == Status.ON }
        bb.needsHeader.isVisible = needs.isNotEmpty()
        bb.okHeader.isVisible = ok.isNotEmpty()
        fill(bb.needs, needs)
        fill(bb.ok, ok)
        // Announce only what changed since the last check (ui-spec 7: Access status after resume).
        val now = all.associate { it.key to it.status }
        if (lastStatus.isNotEmpty()) {
            all.filter { lastStatus[it.key] != null && lastStatus[it.key] != it.status }.forEach {
                bb.root.announceForAccessibility(getString(R.string.access_now, getString(it.title),
                    getString(it.status.label).lowercase()))
            }
        }
        lastStatus = now
    }

    private fun fill(parent: LinearLayout, items: List<Item>) {
        parent.removeAllViews()
        val ctx = parent.context
        for (it in items) {
            val r = AccessRowBinding.inflate(layoutInflater, parent, false)
            val title = getString(it.title)
            val unlocks = getString(it.unlocks)
            val status = getString(it.status.label)
            r.title.text = title
            r.unlocks.text = unlocks
            r.extra.isVisible = it.extra != null
            it.extra?.let { e -> r.extra.setText(e) }
            r.status.text = status
            val (bg, fg) = when (it.status) {
                Status.ON -> R.attr.colorSuccessContainer to R.attr.colorOnSuccessContainer
                Status.OFF -> com.google.android.material.R.attr.colorSurfaceContainerHigh to
                    com.google.android.material.R.attr.colorOnSurfaceVariant
                Status.PARTLY -> com.google.android.material.R.attr.colorSecondaryContainer to
                    com.google.android.material.R.attr.colorOnSecondaryContainer
                Status.BLOCKED -> com.google.android.material.R.attr.colorErrorContainer to
                    com.google.android.material.R.attr.colorOnErrorContainer
            }
            r.status.backgroundTintList = ColorStateList.valueOf(ctx.themeColor(bg))
            r.status.setTextColor(ctx.themeColor(fg))
            if (it.status == Status.ON) {
                val check = ContextCompat.getDrawable(ctx, R.drawable.ic_check)?.mutate()
                check?.setBounds(0, 0, ctx.dp(14), ctx.dp(14))
                check?.setTint(ctx.themeColor(fg))
                r.status.setCompoundDrawablesRelative(check, null, null, null)
            }
            val extra = it.extra?.let { e -> " " + getString(e) + "." } ?: ""
            if (it.status == Status.ON) {
                r.action.isVisible = false
                r.root.isClickable = false
                r.root.contentDescription = getString(R.string.access_row_cd, title, status.lowercase(), unlocks)
            } else {
                val label = getString(it.button)
                r.action.text = label
                r.action.setOnClickListener { _ -> it.action() }
                r.root.setOnClickListener { _ -> it.action() }
                r.root.contentDescription = getString(R.string.access_row_cd_button, title, status.lowercase(), unlocks + extra, label)
            }
            parent.addView(r.root)
        }
    }

    // ---- Actions ----

    /**
     * Runtime permission request. If Android has stopped asking (denied for good), [fallback]
     * opens the settings page instead (App info by default).
     */
    private fun request(perms: Array<String>, fallback: (() -> Unit)? = null) {
        permFallback = fallback
        requestedAt = android.os.SystemClock.elapsedRealtime()
        permLauncher.launch(Perms.forRequest(perms))
    }

    /** Bridgit's own page in Accessibility settings, else the Accessibility list. */
    private fun openScreenshotService() {
        val ctx = requireContext()
        val cn = android.content.ComponentName(ctx, net.finleg.bridget.capability.ScreenshotService::class.java)
        runCatching {
            startActivity(Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, cn.flattenToString()))
        }.onFailure { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun openNotificationSettings() =
        open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName))

    private fun openAppInfo() {
        val ctx = context ?: return
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        }
    }

    /** Opens a settings page; App info is the fallback when the exact page doesn't resolve (Appendix E). */
    private fun open(i: Intent) {
        runCatching { startActivity(i) }.onFailure { openAppInfo() }
    }

    /**
     * Show me how (UI-26): numbered steps for each row. The ui-spec's Pixel screenshots
     * (docs/img/access-*.png) are not in this build; the steps are text only.
     */
    class HowSheet : BottomSheetDialogFragment() {
        private var _b: AccessSheetHowBinding? = null

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
            _b = AccessSheetHowBinding.inflate(inflater, container, false)
            return _b!!.root
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            val parent = parentFragment as? AccessFragment ?: run { dismiss(); return }
            val ctx = requireContext()
            val box = _b!!.sections
            for (item in parent.items()) {
                box.addView(TextView(ctx).apply {
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                    setTextColor(ctx.themeColor(com.google.android.material.R.attr.colorPrimary))
                    text = getString(item.title)
                    isAccessibilityHeading = true
                    setPadding(0, ctx.dp(16), 0, ctx.dp(4))
                })
                val steps = resources.getStringArray(item.steps)
                steps.forEachIndexed { i, step ->
                    box.addView(TextView(ctx).apply {
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                        setTextColor(ctx.themeColor(com.google.android.material.R.attr.colorOnSurface))
                        text = getString(R.string.access_how_step, i + 1, step)
                        contentDescription = getString(R.string.access_how_step_cd, i + 1, steps.size, step)
                        setPadding(ctx.dp(8), ctx.dp(2), 0, ctx.dp(2))
                    })
                }
            }
        }

        override fun onDestroyView() { super.onDestroyView(); _b = null }
    }

    companion object {
        /** A denial faster than this came without a dialog. */
        private const val NO_DIALOG_MS = 600L
    }
}
