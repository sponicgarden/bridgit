package net.finleg.bridget.ui.firstrun

import android.Manifest
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
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
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.R as M
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.finleg.bridget.BridgeApp
import net.finleg.bridget.BridgeService
import net.finleg.bridget.R
import net.finleg.bridget.capability.granted
import net.finleg.bridget.databinding.FirstrunPageBinding
import net.finleg.bridget.databinding.FragmentFirstRunBinding
import net.finleg.bridget.ui.common.themeColor
import net.finleg.bridget.ui.home.UiActions

/**
 * First run (UI-08, ui-spec 5.1): three pages, a live Tailscale check and the
 * notifications request on page 2, then Add agent or Later.
 */
class FirstRunFragment : Fragment() {
    private var _b: FragmentFirstRunBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp

    /** null = still checking; empty = not connected. */
    private var tsAddresses: List<String>? = null
    private var notifDenied = false
    private var page2: FirstrunPageBinding? = null

    private val notifRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifDenied = !granted
        bindNotifications()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentFirstRunBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom); insets
        }
        b.pager.adapter = Pages()
        TabLayoutMediator(b.dots, b.pager) { tab, _ -> tab.view.isClickable = false }.attach()
        b.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (_b == null) return
                b.skip.visibility = if (position < PAGES - 1) View.VISIBLE else View.INVISIBLE
                b.next.isVisible = position < PAGES - 1
                @Suppress("DEPRECATION")
                b.pager.announceForAccessibility(getString(R.string.firstrun_page, position + 1, PAGES))
            }
        })
        b.skip.setOnClickListener { b.pager.currentItem = PAGES - 1 }
        b.next.setOnClickListener { b.pager.currentItem = (b.pager.currentItem + 1).coerceAtMost(PAGES - 1) }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val p = _b?.pager ?: return
                if (p.currentItem > 0) p.currentItem = p.currentItem - 1 else requireActivity().finish()
            }
        })

        // Live Tailscale check while the screen is visible.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val found = withContext(Dispatchers.IO) { BridgeService.tailscaleAddresses() }
                    if (found != tsAddresses) { tsAddresses = found; bindTailscale() }
                    delay(3000)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        bindNotifications() // the user may have changed it in Android settings
    }

    override fun onDestroyView() {
        super.onDestroyView()
        page2 = null
        _b = null
    }

    private fun finish(addAgent: Boolean) {
        app.settings.firstRunDone = true
        val nav = findNavController()
        if (addAgent) nav.navigate(R.id.addAgent, null, NavOptions.Builder().setPopUpTo(R.id.firstRun, true).build())
        else if (!nav.popBackStack()) nav.navigate(R.id.home)
    }

    private fun bindTailscale() {
        val p = page2 ?: return
        val ctx = requireContext()
        val ts = tsAddresses
        p.tsProgress.isVisible = ts == null
        p.tsIcon.isVisible = ts != null
        p.openTailscale.isVisible = ts != null && ts.isEmpty()
        when {
            ts == null -> p.tsText.text = getString(R.string.firstrun_ts_checking)
            ts.isEmpty() -> {
                p.tsText.text = getString(R.string.firstrun_ts_missing)
                p.tsIcon.setImageResource(R.drawable.ic_warning)
                p.tsIcon.setColorFilter(ctx.themeColor(M.attr.colorError))
            }
            else -> {
                val ip = ts.firstOrNull { !it.contains(':') } ?: ts.first()
                val text = getString(R.string.firstrun_ts_connected, ip)
                val start = text.indexOf(ip)
                p.tsText.text = SpannableString(text).apply {
                    if (start >= 0) setSpan(TypefaceSpan("monospace"), start, start + ip.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                p.tsIcon.setImageResource(R.drawable.ic_check)
                p.tsIcon.setColorFilter(ctx.themeColor(R.attr.colorSuccess))
            }
        }
    }

    private fun bindNotifications() {
        val p = page2 ?: return
        val ctx = context ?: return
        val granted = ctx.granted(Manifest.permission.POST_NOTIFICATIONS) && app.captureNotificationsOn()
        p.allowNotif.isVisible = !granted && !notifDenied
        p.notifOn.isVisible = granted
        if (granted) {
            val check = ContextCompat.getDrawable(ctx, R.drawable.ic_check)?.mutate()?.apply { setTint(ctx.themeColor(R.attr.colorSuccess)) }
            p.notifOn.setCompoundDrawablesRelativeWithIntrinsicBounds(check, null, null, null)
        }
        // Denied: the line becomes a warning banner with Open settings; Next stays enabled.
        val warn = !granted && notifDenied
        p.notifSettings.isVisible = warn
        p.notifCard.setCardBackgroundColor(if (warn) ctx.themeColor(M.attr.colorSecondaryContainer) else 0)
        p.notifInner.setPadding(if (warn) ctx.resources.getDimensionPixelSize(R.dimen.firstrun_banner_pad) else 0, if (warn) ctx.resources.getDimensionPixelSize(R.dimen.firstrun_banner_pad) else 0,
            if (warn) ctx.resources.getDimensionPixelSize(R.dimen.firstrun_banner_pad) else 0, 0)
        p.notifLine.setTextColor(ctx.themeColor(if (warn) M.attr.colorOnSecondaryContainer else M.attr.colorOnSurface))
    }

    private inner class Holder(val p: FirstrunPageBinding) : RecyclerView.ViewHolder(p.root)

    private inner class Pages : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = PAGES
        override fun getItemViewType(position: Int) = position
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(FirstrunPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val p = h.p
            p.title.setText(TITLES[position])
            p.body.setText(BODIES[position])
            p.root.contentDescription = null
            p.tsCard.isVisible = position == 1
            p.notifBlock.isVisible = position == 1
            p.finishBlock.isVisible = position == 2
            if (position == 1) {
                page2 = p
                p.openTailscale.setOnClickListener { UiActions.openTailscale(requireContext()) }
                p.allowNotif.setOnClickListener { notifRequest.launch(Manifest.permission.POST_NOTIFICATIONS) }
                p.notifSettings.setOnClickListener { UiActions.openAppNotificationSettings(requireContext()) }
                bindTailscale()
                bindNotifications()
            }
            if (position == 2) {
                p.addFirst.setOnClickListener { finish(addAgent = true) }
                p.later.setOnClickListener { finish(addAgent = false) }
            }
        }
    }

    companion object {
        private const val PAGES = 3
        private val TITLES = intArrayOf(R.string.firstrun_p1_title, R.string.firstrun_p2_title, R.string.firstrun_p3_title)
        private val BODIES = intArrayOf(R.string.firstrun_p1_body, R.string.firstrun_p2_body, R.string.firstrun_p3_body)
    }
}
