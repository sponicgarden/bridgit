package net.finleg.bridget.ui.more

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.finleg.bridget.BridgeApp
import net.finleg.bridget.BuildConfig
import net.finleg.bridget.R
import net.finleg.bridget.capability.appVersion
import net.finleg.bridget.databinding.FragmentMoreBinding
import net.finleg.bridget.databinding.MoreRowBinding
import net.finleg.bridget.feedback.ReportKind
import net.finleg.bridget.ui.feedback.openReport
import net.finleg.bridget.update.UpdateDialog

/** More (UI-07, ui-spec 5.13): Activity (D26), Features, Access, Settings, the reporter (FBK-01), Check for updates (UPD-02), About. */
class MoreFragment : Fragment() {
    private var _b: FragmentMoreBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentMoreBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(b.toolbar) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top); insets
        }
        row(b.openAgents, R.drawable.ic_nav_agents, R.string.more_agents) { findNavController().navigate(R.id.agents) }
        row(b.openActivity, R.drawable.ic_nav_activity, R.string.more_activity) { findNavController().navigate(R.id.activity) }
        row(b.features, R.drawable.ic_toggle, R.string.more_features) { findNavController().navigate(R.id.features) }
        row(b.access, R.drawable.ic_shield, R.string.more_access) { findNavController().navigate(R.id.access) }
        row(b.relay, R.drawable.ic_shield, R.string.more_relay) { startActivity(android.content.Intent(requireContext(), net.finleg.bridget.relay.RelayActivity::class.java)) }
        row(b.settings, R.drawable.ic_settings, R.string.more_settings) { findNavController().navigate(R.id.settings) }
        row(b.reportBug, R.drawable.feedback_ic_bug, R.string.more_report_bug) {
            openReport(this, ReportKind.BUG, "More")
        }
        row(b.requestFeature, R.drawable.feedback_ic_lightbulb, R.string.more_request_feature) {
            openReport(this, ReportKind.FEATURE, "More")
        }
        row(b.myReports, R.drawable.ic_nav_activity, R.string.more_my_reports) { findNavController().navigate(R.id.myReports) }
        row(b.checkUpdates, R.drawable.ic_check, R.string.more_check_updates) {
            UpdateDialog.checkForUpdates(requireActivity(), manual = true)
        }
        row(b.about, R.drawable.ic_info, R.string.more_about) { showAbout() }
        row(b.whatCan, R.drawable.feedback_ic_lightbulb, R.string.more_what_can) { findNavController().navigate(R.id.whatCan) }
    }

    private fun row(r: MoreRowBinding, icon: Int, title: Int, onClick: () -> Unit) {
        r.icon.setImageResource(icon)
        r.title.setText(title)
        r.root.setOnClickListener { onClick() }
    }

    private fun showAbout() {
        val app = requireActivity().application as BridgeApp
        val commit = if (BuildConfig.DIRTY_BUILD) getString(R.string.more_dirty, BuildConfig.GIT_COMMIT) else BuildConfig.GIT_COMMIT
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.more_about_title)
            .setMessage(getString(R.string.more_about_body, app.appVersion(), BuildConfig.BUILD_TYPE, commit))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
