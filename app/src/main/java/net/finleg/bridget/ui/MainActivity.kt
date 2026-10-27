package net.finleg.bridget.ui

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.finleg.bridget.BridgeApp
import net.finleg.bridget.BridgeService
import net.finleg.bridget.R
import net.finleg.bridget.databinding.ActivityMainBinding
import net.finleg.bridget.update.UpdateDialog

/**
 * The single Activity (ARC-06). Each screen is a Fragment in nav_main.xml.
 * Top-level tabs show the bottom bar; full-screen flows (Add agent, first run) hide it.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val app get() = application as BridgeApp
    lateinit var nav: NavController; private set

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        nav = (supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment).navController
        b.bottomNav.setupWithNavController(nav)
        // UI-07: icons only at large font sizes, so the bar stays usable at 200 %.
        if (resources.configuration.fontScale >= 1.5f)
            b.bottomNav.labelVisibilityMode = com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_UNLABELED
        nav.addOnDestinationChangedListener { _, dest, _ ->
            b.bottomNav.isVisible = dest.id in TOP_LEVEL
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.bottomNav) { v, insets ->
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom); insets
        }
        if (savedInstanceState == null) { route(); openReportFrom(intent); openInteractionFrom(intent); openTourFrom(intent) }
        watchInteractionsBadge()
    }

    /** D26: the Interactions tab badge = questions waiting plus notices not yet seen ("99+" cap, hidden at 0). */
    private fun watchInteractionsBadge() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.interactions.changed.collectLatest {
                    val n = withContext(Dispatchers.IO) { runCatching { app.interactions.unreadCount() }.getOrDefault(0) }
                    val badge = b.bottomNav.getOrCreateBadge(R.id.interactions)
                    badge.maxCharacterCount = 3
                    badge.setContentDescriptionQuantityStringsResource(R.plurals.interactions_badge)
                    badge.number = n
                    badge.isVisible = n > 0
                }
            }
        }
    }

    /** The feedback notification (FBK-11) opens its report. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openReportFrom(intent)
        openInteractionFrom(intent)
        openTourFrom(intent)
    }

    /** The City tour notification opens the tour screen. */
    private fun openTourFrom(intent: Intent?) {
        if (intent?.getBooleanExtra(net.finleg.bridget.tour.TourNotifier.EXTRA_OPEN_TOUR, false) != true) return
        intent.removeExtra(net.finleg.bridget.tour.TourNotifier.EXTRA_OPEN_TOUR)
        if (nav.currentDestination?.id in setOf(R.id.addAgent, R.id.pairCode, R.id.firstRun, R.id.tour)) return
        nav.navigate(R.id.tour)
    }

    /** A question or notice notification (CAP-33) opens its interaction. */
    private fun openInteractionFrom(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_INTERACTION_ID, -1L) ?: -1L
        if (id < 0) return
        intent?.removeExtra(EXTRA_INTERACTION_ID)
        // Not over an unfinished setup prompt or first run: those must finish first.
        if (nav.currentDestination?.id in setOf(R.id.addAgent, R.id.pairCode, R.id.firstRun)) return
        nav.navigate(R.id.interactionDetail, Bundle().apply { putLong("interactionId", id) })
    }

    private fun openReportFrom(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_REPORT_ID, -1L) ?: -1L
        if (id < 0) return
        intent?.removeExtra(EXTRA_REPORT_ID)
        // Not over an unfinished setup prompt or first run: those must finish first.
        if (nav.currentDestination?.id in setOf(R.id.addAgent, R.id.pairCode, R.id.firstRun)) return
        nav.navigate(R.id.reportDetail, Bundle().apply { putLong("reportId", id) })
    }

    /** First run, or back to an unfinished setup prompt after a crash (AGT-05a). */
    private fun route() {
        val pending = app.vault.get()
        when {
            pending != null && app.agents.get(pending.agentId) != null ->
                nav.navigate(R.id.addAgent, Bundle().apply { putString("agentId", pending.agentId) })
            !app.settings.firstRunDone && app.agents.count == 0 -> nav.navigate(R.id.firstRun)
        }
    }

    /** Android lets an app see the clipboard only while its window has focus, so SEC-11 runs here, not in onResume. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) clearOurClip()
    }

    override fun onResume() {
        super.onResume()
        // A visible start lets Android grant the camera and microphone service types (ARC-03).
        if (app.settings.listenerEnabled && app.agents.count > 0) {
            val s = app.listener.value
            if (!s.running || s.startedInBackground) runCatching { BridgeService.start(this) }
        }
        UpdateDialog.resumeAfterPermission(this) // back from "Install unknown apps"
        UpdateDialog.autoCheckIfDue(this) // UPD-02: at most once a day, only when the app is opened.
    }

    /**
     * SEC-11: a copied prompt or key is cleared the next time Bridget is in the
     * foreground. Only the clip's label is read, which doesn't show Android's paste toast.
     */
    private fun clearOurClip() {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        val label = runCatching { cm.primaryClipDescription?.label?.toString() }.getOrNull() ?: return
        if (label == CLIP_LABEL) runCatching { cm.clearPrimaryClip() }
    }

    fun showBottomBar(show: Boolean) { b.bottomNav.visibility = if (show) View.VISIBLE else View.GONE }

    companion object {
        const val CLIP_LABEL = "Bridget setup prompt"
        /** Long extra: open this report (from the feedback notification). */
        const val EXTRA_REPORT_ID = "report_id"
        /** Long extra: open this interaction (from a question or notice notification). */
        const val EXTRA_INTERACTION_ID = "interaction_id"
        /** D26: Activity is no longer a tab; it is opened from More and shows a back arrow. */
        val TOP_LEVEL = setOf(R.id.home, R.id.interactions, R.id.applets, R.id.more)
    }
}
