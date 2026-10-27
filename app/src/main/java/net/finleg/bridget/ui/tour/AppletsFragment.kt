package net.finleg.bridget.ui.tour

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch
import net.finleg.bridget.BridgeApp
import net.finleg.bridget.R
import net.finleg.bridget.api.ApiDocs
import net.finleg.bridget.databinding.FragmentAppletsBinding
import net.finleg.bridget.ui.feedback.handleFeedbackMenu

/**
 * Applets tab (owner, 27 Oct 2026; replaces the Agents tab): ready-made sessions with an agent.
 * One card per applet. Later applets (Museum guide, Run coach; TOUR-05) get their cards here.
 */
class AppletsFragment : Fragment() {
    private var _b: FragmentAppletsBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentAppletsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(b.toolbar) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top); insets
        }
        b.toolbar.inflateMenu(R.menu.feedback_overflow) // Report a bug / Request a feature (FBK-01)
        b.toolbar.setOnMenuItemClickListener { handleFeedbackMenu(it, "Applets") }
        b.cityTour.setOnClickListener { findNavController().navigate(R.id.tour) }
        val guide = ApiDocs.link(ApiDocs.APPLET_TITLE)
        b.buildAppletOpen.setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(guide))) }
        }
        b.buildAppletShare.setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Bridget: ${getString(R.string.applets_build_title)}")
                .putExtra(Intent.EXTRA_TEXT, getString(R.string.applets_build_share_text, guide))
            startActivity(Intent.createChooser(send, getString(R.string.applets_build_share_title)))
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.tours.state.collect { t ->
                    val chip = _b?.cityTourRunning ?: return@collect
                    chip.isVisible = t != null
                    if (t != null) chip.text = getString(if (t.paused) R.string.applets_paused_with else R.string.applets_running_with, t.agent)
                    b.cityTour.contentDescription = listOfNotNull(getString(R.string.tour_title), getString(R.string.applets_city_tour_desc),
                        t?.let { chip.text }).joinToString(". ")
                }
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
