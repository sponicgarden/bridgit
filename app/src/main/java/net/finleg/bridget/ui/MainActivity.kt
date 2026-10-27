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
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import net.finleg.bridget.BridgeApp
import net.finleg.bridget.BridgeService
import net.finleg.bridget.R
import net.finleg.bridget.databinding.ActivityMainBinding

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
        if (savedInstanceState == null) { route(); openReportFrom(intent) }
    }

    /** The feedback notification (FBK-11) opens its report. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openReportFrom(intent)
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
        val TOP_LEVEL = setOf(R.id.home, R.id.agents, R.id.activity, R.id.more)
    }
}
