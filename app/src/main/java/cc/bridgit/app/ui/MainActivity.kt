package cc.bridgit.app.ui

import android.app.KeyguardManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.R
import cc.bridgit.app.databinding.ActivityMainBinding
import cc.bridgit.app.interact.IKind
import cc.bridgit.app.interact.IStatus
import cc.bridgit.app.interact.Interaction
import cc.bridgit.app.interact.InteractionNotifier
import cc.bridgit.app.interact.MainActivityState
import cc.bridgit.app.ui.interactions.InteractionUi
import cc.bridgit.app.ui.interactions.TextPanel
import cc.bridgit.app.update.UpdateDialog

/**
 * The single Activity (ARC-06). Each screen is a Fragment in nav_main.xml.
 * Top-level tabs show the bottom bar; full-screen flows (Add agent, first run) hide it.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val app get() = application as BridgeApp
    lateinit var nav: NavController; private set
    private lateinit var panel: TextPanel
    /** D26: the text panel the owner picked (list or notification); null = the newest open one, if any. */
    private var panelChosen: Long? = null
    private val panelSeen = HashSet<Long>()
    private var navInset = 0

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
        panel = TextPanel(b.textPanel, ::closePanel)
        nav.addOnDestinationChangedListener { _, dest, _ ->
            b.bottomNav.isVisible = dest.id in TOP_LEVEL
            applyPanelInset()
            lifecycleScope.launch { refreshPanel() }
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.bottomNav) { v, insets ->
            navInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.updatePadding(bottom = navInset); applyPanelInset(); insets
        }
        if (savedInstanceState == null) { route(); openReportFrom(intent); wakeFor(intent); openInteractionFrom(intent); openTourFrom(intent) }
        watchInteractionsBadge()
        watchTextPanel()
    }

    /**
     * D26: keeps the live text panel in step with the store. Throttled (render, then wait 300 ms) rather
     * than debounced, so a fast stream still shows about three updates a second.
     */
    private fun watchTextPanel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.interactions.changed.collect {
                    refreshPanel()
                    delay(300)
                }
            }
        }
    }

    private suspend fun refreshPanel() {
        if (!::panel.isInitialized) return
        // Not over first run or an unfinished setup prompt.
        if (nav.currentDestination?.id in SETUP) { panel.show(null); return }
        val chosen = panelChosen
        val store = app.interactions
        val i: Interaction? = withContext(Dispatchers.IO) {
            runCatching { (chosen?.let { store.get(it) } ?: store.latestOpenText())?.takeIf { it.kind == IKind.TEXT } }.getOrNull()
        }
        if (i == null && chosen != null) panelChosen = null
        panel.show(i)
        // Showing it is seeing it: the agent gets interaction.seen and the notification goes.
        if (i != null && i.status == IStatus.PENDING && panelSeen.add(i.id))
            withContext(Dispatchers.IO) { runCatching { store.markSeen(i.id) } }
    }

    /** Opens (or reopens) the live text panel for interaction [id] (Interactions list, notification). */
    fun showTextPanel(id: Long) {
        panelChosen = id
        lifecycleScope.launch { refreshPanel() }
    }

    /** Close = dismiss (the agent is told); one already closed is just hidden. */
    private fun closePanel(i: Interaction) {
        val store = app.interactions
        panelChosen = null
        lifecycleScope.launch {
            if (i.status.open) withContext(Dispatchers.IO) { runCatching { store.dismiss(i.id) } }
            else panel.show(null)
            if (i.status.open) com.google.android.material.snackbar.Snackbar.make(b.root,
                getString(R.string.interactions_closed_snack, InteractionUi.agentName(i)), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
            refreshPanel()
        }
    }

    private fun applyPanelInset() {
        if (::panel.isInitialized) panel.setBottomInset(if (b.bottomNav.isVisible) 0 else navInset)
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
        wakeFor(intent)
        openInteractionFrom(intent)
        openTourFrom(intent)
    }

    /**
     * CAP-56: an arriving interaction turns the screen on and asks Android to lift the lock screen. Never shown
     * over the lock screen, so nobody answers a card without unlocking. Lasts only for this launch.
     */
    private fun wakeFor(intent: Intent?) {
        if (intent?.getBooleanExtra(InteractionNotifier.EXTRA_ARRIVAL, false) != true) return
        intent.removeExtra(InteractionNotifier.EXTRA_ARRIVAL)
        setTurnScreenOn(true)
        getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
    }

    /** The City tour notification opens the tour screen. */
    private fun openTourFrom(intent: Intent?) {
        if (intent?.getBooleanExtra(cc.bridgit.app.tour.TourNotifier.EXTRA_OPEN_TOUR, false) != true) return
        intent.removeExtra(cc.bridgit.app.tour.TourNotifier.EXTRA_OPEN_TOUR)
        if (nav.currentDestination?.id in setOf(R.id.addAgent, R.id.pairCode, R.id.firstRun, R.id.tour)) return
        nav.navigate(R.id.tour)
    }

    /** A question or notice notification (CAP-33) opens its interaction. */
    private fun openInteractionFrom(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_INTERACTION_ID, -1L) ?: -1L
        if (id < 0) return
        intent?.removeExtra(EXTRA_INTERACTION_ID)
        // Not over an unfinished setup prompt or first run: those must finish first.
        if (nav.currentDestination?.id in SETUP) return
        // A text panel opens in the lower half (D26); everything else opens its detail.
        lifecycleScope.launch {
            val kind = withContext(Dispatchers.IO) { runCatching { app.interactions.get(id)?.kind }.getOrNull() }
            if (kind == IKind.TEXT) showTextPanel(id)
            else nav.navigate(R.id.interactionDetail, Bundle().apply { putLong("interactionId", id) })
        }
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
        if (hasFocus) { clearOurClip(); leftApp = false }
    }

    /** True once Bridgit went to the background; a copied callback secret is cleared only after that. */
    private var leftApp = false

    override fun onStop() {
        super.onStop()
        leftApp = true
        setTurnScreenOn(false)
    }

    override fun onPause() {
        super.onPause()
        MainActivityState.inFront = false
    }

    override fun onResume() {
        super.onResume()
        MainActivityState.inFront = true
        // A visible start lets Android grant the camera and microphone service types (ARC-03).
        if (app.settings.listenerEnabled && app.agents.count > 0) {
            val s = app.listener.value
            if (!s.running || s.startedInBackground) runCatching { BridgeService.start(this) }
        }
        UpdateDialog.resumeAfterPermission(this) // back from "Install unknown apps"
        UpdateDialog.autoCheckIfDue(this) // UPD-02: at most once a day, only when the app is opened.
    }

    /**
     * SEC-11: a copied prompt or key is cleared the next time Bridgit is in the
     * foreground. Only the clip's label is read, which doesn't show Android's paste toast.
     */
    private fun clearOurClip() {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        val label = runCatching { cm.primaryClipDescription?.label?.toString() }.getOrNull() ?: return
        if (label == CLIP_LABEL) runCatching { cm.clearPrimaryClip() }
        // The secret's Copy button closes a dialog, which refocuses this window at once: wait until the owner has been away.
        if (label == CLIP_LABEL_SECRET && leftApp) runCatching { cm.clearPrimaryClip() }
    }

    fun showBottomBar(show: Boolean) { b.bottomNav.visibility = if (show) View.VISIBLE else View.GONE }

    companion object {
        const val CLIP_LABEL = "Bridgit setup prompt"
        const val CLIP_LABEL_SECRET = "Bridgit callback secret"
        /** Long extra: open this report (from the feedback notification). */
        const val EXTRA_REPORT_ID = "report_id"
        /** Long extra: open this interaction (from a question or notice notification). */
        const val EXTRA_INTERACTION_ID = "interaction_id"
        /** D26: Activity is no longer a tab; it is opened from More and shows a back arrow. */
        val TOP_LEVEL = setOf(R.id.home, R.id.interactions, R.id.agents, R.id.more)
        /** Setup screens nothing may open over. */
        private val SETUP = setOf(R.id.addAgent, R.id.pairCode, R.id.firstRun)
    }
}
