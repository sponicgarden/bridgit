package net.finleg.bridget.ui.addagent

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.PersistableBundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.launch
import net.finleg.bridget.BridgeApp
import net.finleg.bridget.BridgeService
import net.finleg.bridget.R
import net.finleg.bridget.capability.address
import net.finleg.bridget.core.AgentRegistry
import net.finleg.bridget.core.Connection
import net.finleg.bridget.databinding.AddagentCheckRowBinding
import net.finleg.bridget.databinding.AddagentQrSheetBinding
import net.finleg.bridget.databinding.AddagentTextSheetBinding
import net.finleg.bridget.databinding.FragmentAddAgentBinding
import net.finleg.bridget.ui.MainActivity
import net.finleg.bridget.ui.common.dp
import net.finleg.bridget.ui.common.themeColor
import com.google.android.material.R as M

/**
 * Add agent (6.2, UI-13, ui-spec 5.4): Name → Connection → Check → Setup prompt,
 * then waiting for the first call on the same screen. Opened with `agentId` it
 * shows the prompt for an existing agent (after Rotate, or after a crash, AGT-05a).
 */
class AddAgentFragment : Fragment() {
    private var _b: FragmentAddAgentBinding? = null
    private val b get() = _b!!
    private val vm: AddAgentViewModel by viewModels()
    private val app get() = requireActivity().application as BridgeApp
    private var pulse: AnimatorSet? = null
    private var bindingName = false
    private var sheet: BottomSheetDialog? = null

    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = onBack()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentAddAgentBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        vm.init(arguments?.getString("agentId"), arguments?.getBoolean("fromRotate") == true)
        if (vm.step.value == 4 && vm.agentId != null && !vm.agentExists) { findNavController().popBackStack(); return }

        // Step 0 (AGT-03): opening Add agent turns the listener on.
        if (!vm.step0Done) {
            vm.step0Done = true
            if (!app.settings.listenerEnabled) app.settings.listenerEnabled = true
            if (BridgeService.running == null) runCatching { BridgeService.start(requireContext()) }
        }

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            b.toolbar.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            v.updatePadding(bottom = bars.bottom)
            insets
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)
        b.toolbar.setNavigationOnClickListener { onClose() }
        b.toolbar.inflateMenu(R.menu.addagent_menu)
        b.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.addagent_copy_key -> { copy(vm.key ?: return@setOnMenuItemClickListener true, true); true }
                R.id.addagent_pair -> { openPairing(); true }
                else -> false
            }
        }

        setupName()
        setupConnection()
        setupCheckRows()
        setupPrompt()

        b.back.setOnClickListener { vm.step.value = (vm.step.value - 1).coerceAtLeast(1) }
        b.next.setOnClickListener { next() }
        b.doneOutlined.setOnClickListener { done() }
        b.doneFilled.setOnClickListener { done() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.step.collect { render(it) } }
                launch { vm.checks.collect { renderChecks(it) } }
                launch { vm.wait.collect { renderWait(it) } }
                launch { vm.keyGone.collect { renderPrompt() } }
            }
        }

        vm.resumedMinutes?.let { m ->
            vm.resumedMinutes = null
            snack(getString(R.string.addagent_resumed, m))
        }
    }

    override fun onResume() {
        super.onResume()
        if (vm.step.value == 3) vm.runChecks()
        if (vm.step.value == 4) setSecure(true)
        // A key fetched with a pairing code counts as acknowledged.
        val redeemed = runCatching {
            findNavController().currentBackStackEntry?.savedStateHandle?.get<Boolean>(PairCodeFragment.KEY_REDEEMED)
        }.getOrNull() == true
        if ((redeemed || vm.pairWindow?.redeemed == true) && !vm.acknowledged) vm.acknowledge()
    }

    override fun onStop() {
        super.onStop()
        // Keep FLAG_SECURE while this flow or Pair with code is on screen; clear it when leaving.
        val dest = runCatching { findNavController().currentDestination?.id }.getOrNull()
        if (dest != R.id.addAgent && dest != R.id.pairCode) setSecure(false)
    }

    override fun onDestroyView() {
        pulse?.cancel(); pulse = null
        sheet?.dismiss(); sheet = null
        super.onDestroyView()
        _b = null
    }

    // ---- Rendering

    private fun render(step: Int) {
        val title = if (step == 1 || vm.name.isBlank()) getString(R.string.addagent_title)
        else getString(R.string.addagent_title_named, vm.name.trim())
        b.toolbar.title = title
        b.toolbar.menu.findItem(R.id.addagent_copy_key).isVisible = step == 4 && vm.key != null
        b.toolbar.menu.findItem(R.id.addagent_pair).isVisible = step == 4 && vm.key != null

        val segs = listOf(b.seg1, b.seg2, b.seg3, b.seg4)
        val labs = listOf(b.lab1, b.lab2, b.lab3, b.lab4)
        val primary = requireContext().themeColor(M.attr.colorPrimary)
        val track = requireContext().themeColor(M.attr.colorSurfaceContainerHighest)
        val variant = requireContext().themeColor(M.attr.colorOnSurfaceVariant)
        segs.forEachIndexed { i, v -> v.background.mutate().setTint(if (i < step) primary else track) }
        labs.forEachIndexed { i, t ->
            t.setTextColor(if (i == step - 1) primary else variant)
            t.paint.isFakeBoldText = i == step - 1
            t.invalidate()
        }
        b.progress.contentDescription = getString(R.string.addagent_progress_desc, step, labs[step - 1].text)

        b.stepName.isVisible = step == 1
        b.stepConnection.isVisible = step == 2
        b.stepCheck.isVisible = step == 3
        b.stepPrompt.isVisible = step == 4

        b.back.visibility = when (step) { 1 -> View.INVISIBLE; 4 -> View.GONE; else -> View.VISIBLE }
        b.next.isVisible = step in 1..3
        when (step) {
            1 -> { b.next.text = getString(R.string.addagent_next); updateNextForName() }
            2 -> {
                b.next.text = getString(R.string.addagent_next); b.next.isEnabled = true
                b.connHeading.text = getString(R.string.addagent_conn_heading, vm.name.trim())
                renderConnection()
            }
            3 -> {
                b.checkHeading.text = getString(R.string.addagent_check_heading, vm.name.trim())
                renderChecks(vm.checks.value)
                vm.runChecks()
            }
            4 -> {
                setSecure(true)
                renderPrompt()
                renderWait(vm.wait.value)
            }
        }
        if (step != 4) {
            setSecure(false)
            b.doneOutlined.isVisible = false; b.doneFilled.isVisible = false
        }
        b.scroll.scrollTo(0, 0)
    }

    // ---- Step 1: Name

    private fun setupName() {
        bindingName = true
        b.nameInput.setText(vm.name)
        bindingName = false
        b.nameInput.doAfterTextChanged {
            if (bindingName) return@doAfterTextChanged
            vm.name = it?.toString() ?: ""
            vm.nameError = null
            b.nameLayout.error = null
            updateNextForName()
        }
        b.nameInput.setOnEditorActionListener { _, _, _ -> if (b.next.isEnabled) next(); true }
        val used = app.agents.all().map { it.name.lowercase() }.toSet()
        b.suggestions.removeAllViews()
        AgentRegistry.SUGGESTIONS.filter { it.lowercase() !in used }.forEach { s ->
            b.suggestions.addView(Chip(requireContext()).apply {
                text = s
                setOnClickListener { b.nameInput.setText(s); b.nameInput.setSelection(s.length) }
            })
        }
        b.suggestions.isVisible = b.suggestions.childCount > 0
        vm.nameError?.let { b.nameLayout.error = it }
    }

    private fun updateNextForName() {
        if (vm.step.value == 1) b.next.isEnabled = vm.name.isNotBlank()
    }

    // ---- Step 2: Connection

    private fun setupConnection() {
        val cards = listOf(b.cardDirect to Connection.DIRECT, b.cardUserspace to Connection.USERSPACE)
        cards.forEachIndexed { i, (card, conn) ->
            card.setOnClickListener { vm.connection = conn; renderConnection() }
            ViewCompat.setAccessibilityDelegate(card, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = RadioButton::class.java.name
                    info.isCheckable = true
                    info.isChecked = vm.connection == conn
                    info.setCollectionItemInfo(AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(
                        i, 1, 0, 1, false, vm.connection == conn))
                }
            })
        }
        ViewCompat.setAccessibilityDelegate(b.connGroup, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = RadioGroup::class.java.name
                info.setCollectionInfo(AccessibilityNodeInfoCompat.CollectionInfoCompat.obtain(
                    cards.size, 1, false, AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE))
            }
        })
    }

    private fun renderConnection() {
        val ctx = requireContext()
        listOf(b.cardDirect to Connection.DIRECT, b.cardUserspace to Connection.USERSPACE).forEach { (card, conn) ->
            val on = vm.connection == conn
            card.isChecked = on
            card.strokeWidth = ctx.dp(if (on) 2 else 1)
            card.strokeColor = ctx.themeColor(if (on) M.attr.colorPrimary else M.attr.colorOutlineVariant)
            card.setCardBackgroundColor(if (on) ctx.themeColor(M.attr.colorPrimaryContainer)
                else ctx.themeColor(M.attr.colorSurface))
        }
    }

    // ---- Step 3: Check

    private fun setupCheckRows() {
        b.checkListener.fix.setOnClickListener {
            app.settings.listenerEnabled = true
            runCatching { BridgeService.start(requireContext()) }
            vm.runChecks()
        }
        b.checkTailscale.fix.setOnClickListener { openTailscale() }
        b.checkPort.fix.setOnClickListener { openTailscale() }
    }

    private fun openTailscale() {
        val intent = requireContext().packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
        if (intent != null) startActivity(intent) else snack(getString(R.string.addagent_no_tailscale_app))
    }

    private fun renderChecks(c: AddAgentViewModel.Checks) {
        if (_b == null) return
        row(b.checkListener, getString(R.string.addagent_check_listener), c.listener,
            getString(R.string.addagent_check_listener_fail), fix = true)
        row(b.checkTailscale, c.ip?.let { getString(R.string.addagent_check_tailscale, it) }
            ?: getString(R.string.addagent_check_tailscale_none), c.tailscale,
            getString(R.string.addagent_check_tailscale_fail), fix = true)
        val portLabel = getString(R.string.addagent_check_port, c.portNumber)
        when {
            c.port == AddAgentViewModel.Check.FAIL ->
                row(b.checkPort, portLabel, c.port, getString(R.string.addagent_check_port_blocked), fix = false)
            else -> row(b.checkPort, portLabel, c.port, getString(R.string.addagent_check_port_fail), fix = false)
        }
        if (vm.step.value == 3) {
            b.next.isEnabled = c.canContinue
            b.next.text = getString(if (c.port == AddAgentViewModel.Check.WARN) R.string.addagent_continue_anyway
                else R.string.addagent_next)
        }
    }

    private fun row(r: AddagentCheckRowBinding, label: String, state: AddAgentViewModel.Check, reason: String, fix: Boolean) {
        val ctx = requireContext()
        r.label.text = label
        r.spinner.isVisible = state == AddAgentViewModel.Check.PENDING
        r.icon.isVisible = state != AddAgentViewModel.Check.PENDING
        val (icon, tint) = when (state) {
            AddAgentViewModel.Check.OK -> R.drawable.ic_check to ctx.themeColor(R.attr.colorSuccess)
            AddAgentViewModel.Check.FAIL -> R.drawable.ic_error to ctx.themeColor(M.attr.colorError)
            AddAgentViewModel.Check.WARN -> R.drawable.ic_warning to ctx.themeColor(M.attr.colorOnSecondaryContainer)
            AddAgentViewModel.Check.PENDING -> R.drawable.ic_check to ctx.themeColor(M.attr.colorOnSurfaceVariant)
        }
        r.icon.setImageResource(icon)
        r.icon.imageTintList = android.content.res.ColorStateList.valueOf(tint)
        val showReason = state == AddAgentViewModel.Check.FAIL || state == AddAgentViewModel.Check.WARN
        r.reason.isVisible = showReason
        r.reason.text = reason
        r.fix.isVisible = fix && state == AddAgentViewModel.Check.FAIL
        if (state == AddAgentViewModel.Check.WARN) {
            r.root.background = GradientDrawable().apply {
                cornerRadius = ctx.dp(12).toFloat()
                setColor(ctx.themeColor(M.attr.colorSecondaryContainer))
            }
            r.label.setTextColor(ctx.themeColor(M.attr.colorOnSecondaryContainer))
            r.reason.setTextColor(ctx.themeColor(M.attr.colorOnSecondaryContainer))
        } else {
            r.root.background = null
            r.label.setTextColor(ctx.themeColor(M.attr.colorOnSurface))
            r.reason.setTextColor(ctx.themeColor(M.attr.colorOnSurfaceVariant))
        }
        val stateWord = getString(when (state) {
            AddAgentViewModel.Check.PENDING -> R.string.addagent_state_pending
            AddAgentViewModel.Check.OK -> R.string.addagent_state_ok
            AddAgentViewModel.Check.FAIL -> R.string.addagent_state_fail
            AddAgentViewModel.Check.WARN -> R.string.addagent_state_warn
        })
        r.texts.contentDescription = "$label, $stateWord" + (if (showReason) ". $reason" else "")
    }

    // ---- Navigation between steps

    private fun next() {
        when (vm.step.value) {
            1 -> {
                if (!vm.validateName()) { b.nameLayout.error = vm.nameError; return }
                b.nameLayout.error = null
                hideKeyboard()
                vm.step.value = 2
            }
            2 -> vm.step.value = 3
            3 -> {
                if (!vm.checks.value.canContinue) return
                if (!vm.createAgent()) {
                    // Name taken meanwhile, or 16 agents: back to the name with the reason.
                    vm.step.value = 1
                    b.nameLayout.error = vm.nameError
                    return
                }
                vm.step.value = 4
            }
        }
    }

    private fun onBack() {
        when (vm.step.value) {
            2, 3 -> vm.step.value -= 1
            1 -> leave()
            else -> onClose()
        }
    }

    /** ✕, system back or Done on the prompt step: ask first if the key hasn't been taken (AGT-05). */
    private fun onClose() {
        if (vm.step.value == 4 && vm.key != null && !vm.acknowledged) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.addagent_leave_title)
                .setMessage(R.string.addagent_leave_body)
                .setPositiveButton(R.string.addagent_leave_copy) { _, _ -> copy(vm.prompt ?: "", false, quiet = true); leave() }
                .setNeutralButton(R.string.addagent_leave_stay, null)
                .setNegativeButton(R.string.addagent_leave) { _, _ -> leave() }
                .show()
        } else leave()
    }

    private fun done() = onClose()

    private fun leave() {
        if (vm.step.value == 4) vm.acknowledge()
        hideKeyboard()
        setSecure(false)
        back.isEnabled = false
        if (!findNavController().popBackStack()) requireActivity().finish()
    }

    // ---- Step 4: Setup prompt

    private fun setupPrompt() {
        // The card scrolls inside itself, up to ~45 % of the screen (ui-spec 5.4).
        b.promptScroll.layoutParams = b.promptScroll.layoutParams.apply {
            height = (resources.displayMetrics.heightPixels * 0.45f).toInt()
        }
        b.copyPrompt.setOnClickListener { vm.prompt?.let { copy(it, false) } }
        b.share.setOnClickListener { share() }
        b.qr.setOnClickListener { showQr() }
        b.troubleshoot.setOnClickListener { showTroubleshooting() }
        b.keyGoneRotate.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(R.string.agents_rotate_body, vm.name))
                .setPositiveButton(R.string.agents_rotate_confirm) { _, _ -> vm.rotate(); render(4) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun renderPrompt() {
        if (_b == null || vm.step.value != 4) return
        val gone = vm.keyGone.value || vm.key == null
        b.keyGone.isVisible = gone
        b.promptSection.isVisible = !gone
        b.waitCard.isVisible = vm.agentId != null
        b.toolbar.menu.findItem(R.id.addagent_copy_key).isVisible = !gone
        b.toolbar.menu.findItem(R.id.addagent_pair).isVisible = !gone
        if (gone) return
        val n = vm.name.trim()
        b.promptIntro.text = getString(R.string.addagent_prompt_intro, n)
        b.qr.isVisible = vm.connection != Connection.USERSPACE
        // The address may only be known now (Tailscale came up during the check).
        vm.refreshPrompt()
        val text = vm.prompt ?: return
        val span = SpannableString(text)
        vm.key?.let { k ->
            val i = text.indexOf(k)
            if (i >= 0) {
                val start = text.lastIndexOf('\n', i).let { if (it < 0) 0 else it + 1 }
                val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                val ctx = requireContext()
                span.setSpan(BackgroundColorSpan(ctx.themeColor(M.attr.colorSecondaryContainer)), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                span.setSpan(ForegroundColorSpan(ctx.themeColor(M.attr.colorOnSecondaryContainer)), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        b.promptText.text = span
    }

    private fun renderWait(w: AddAgentViewModel.Wait) {
        if (_b == null || vm.step.value != 4) return
        val ctx = requireContext()
        val n = vm.name.trim()
        val connected = w.kind == AddAgentViewModel.WaitKind.CONNECTED
        b.doneOutlined.isVisible = !connected
        b.doneFilled.isVisible = connected
        when (w.kind) {
            AddAgentViewModel.WaitKind.WAITING -> {
                b.waitCard.setCardBackgroundColor(ctx.themeColor(M.attr.colorSecondaryContainer))
                val on = ctx.themeColor(M.attr.colorOnSecondaryContainer)
                b.waitTitle.setTextColor(on); b.waitSub.setTextColor(on)
                b.waitTitle.text = getString(R.string.addagent_waiting, n)
                b.waitSub.text = getString(R.string.addagent_waiting_sub)
                b.waitSub.setTextAppearance(M.style.TextAppearance_Material3_BodyMedium)
                b.waitSub.setTextColor(on)
                b.pulseDot.isVisible = true; b.pulseRing.isVisible = true; b.waitIcon.isVisible = false
                b.troubleshoot.isVisible = false
                startPulse()
            }
            AddAgentViewModel.WaitKind.CONNECTED -> {
                stopPulse()
                b.waitCard.setCardBackgroundColor(ctx.themeColor(R.attr.colorSuccessContainer))
                val on = ctx.themeColor(R.attr.colorOnSuccessContainer)
                b.waitTitle.setTextColor(on); b.waitSub.setTextColor(on)
                b.waitTitle.text = getString(R.string.addagent_connected, n)
                val e = w.entry
                b.waitSub.isVisible = e != null
                if (e != null) {
                    b.waitSub.text = listOfNotNull("${e.method ?: ""} ${e.endpoint ?: ""}".trim().ifEmpty { null },
                        e.durationMs?.let { "$it ms" }, e.sourceIp).joinToString(" · ")
                    b.waitSub.setTextAppearance(R.style.TextAppearance_Bridget_Mono)
                    b.waitSub.setTextColor(on)
                }
                b.pulseDot.isVisible = false; b.pulseRing.isVisible = false
                b.waitIcon.isVisible = true
                b.waitIcon.setImageResource(R.drawable.ic_check)
                b.waitIcon.imageTintList = android.content.res.ColorStateList.valueOf(on)
                b.troubleshoot.isVisible = false
            }
            AddAgentViewModel.WaitKind.NO_CALL -> {
                stopPulse()
                b.waitCard.setCardBackgroundColor(ctx.themeColor(M.attr.colorSurfaceContainer))
                b.waitTitle.setTextColor(ctx.themeColor(M.attr.colorOnSurface))
                b.waitTitle.text = getString(R.string.addagent_no_call)
                b.waitSub.isVisible = false
                b.pulseDot.isVisible = false; b.pulseRing.isVisible = false
                b.waitIcon.isVisible = true
                b.waitIcon.setImageResource(R.drawable.ic_info)
                b.waitIcon.imageTintList = android.content.res.ColorStateList.valueOf(ctx.themeColor(M.attr.colorOnSurfaceVariant))
                b.troubleshoot.isVisible = true
            }
        }
        if (w.kind == AddAgentViewModel.WaitKind.WAITING) b.waitSub.isVisible = true
    }

    /** Beacon pulse (ui-spec 2.5): ring grows and fades, 1.6 s; a static dot with Remove animations on. */
    private fun startPulse() {
        if (pulse != null) return
        if (!ValueAnimator.areAnimatorsEnabled()) { b.pulseRing.isVisible = false; return }
        val ring = b.pulseRing
        val sx = ObjectAnimator.ofFloat(ring, View.SCALE_X, 1f, 2f)
        val sy = ObjectAnimator.ofFloat(ring, View.SCALE_Y, 1f, 2f)
        val a = ObjectAnimator.ofFloat(ring, View.ALPHA, 0.75f, 0f)
        listOf(sx, sy, a).forEach { it.repeatCount = ValueAnimator.INFINITE; it.duration = 1600 }
        pulse = AnimatorSet().apply {
            playTogether(sx, sy, a)
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopPulse() { pulse?.cancel(); pulse = null }

    private fun copy(text: String, keyOnly: Boolean, quiet: Boolean = false) {
        if (text.isEmpty()) return
        val cm = requireContext().getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(MainActivity.CLIP_LABEL, text)
        // SEC-11: Android hides the preview of a sensitive clip.
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        cm.setPrimaryClip(clip)
        vm.acknowledge()
        if (!quiet) snack(getString(R.string.addagent_copied, vm.name.trim()))
    }

    private fun share() {
        val text = vm.prompt ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        vm.acknowledge()
        startActivity(Intent.createChooser(send, getString(R.string.addagent_share_title)))
    }

    private fun showQr() {
        val payload = vm.qrPayload ?: return
        val ctx = requireContext()
        val sb = AddagentQrSheetBinding.inflate(layoutInflater)
        val size = ctx.dp(264)
        sb.qr.setImageBitmap(qrBitmap(payload, size))
        sb.qr.contentDescription = getString(R.string.addagent_qr_desc, vm.name.trim())
        showSheet(sb.root)
    }

    private fun qrBitmap(payload: String, size: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val m = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, hints)
        val dark = ContextCompat.getColor(requireContext(), R.color.qr_dark)
        val light = ContextCompat.getColor(requireContext(), R.color.qr_light)
        val px = IntArray(m.width * m.height)
        for (y in 0 until m.height) for (x in 0 until m.width) px[y * m.width + x] = if (m.get(x, y)) dark else light
        return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
    }

    private fun showTroubleshooting() {
        val sb = AddagentTextSheetBinding.inflate(layoutInflater)
        sb.title.text = getString(R.string.addagent_troubleshooting)
        sb.body.text = if (vm.connection == Connection.USERSPACE) getString(R.string.addagent_trouble_userspace)
            else getString(R.string.addagent_trouble_direct, app.address().baseUrl)
        showSheet(sb.root)
    }

    private fun showSheet(content: View) {
        sheet?.dismiss()
        sheet = BottomSheetDialog(requireContext()).apply {
            setContentView(content)
            // The sheet is its own window: it needs FLAG_SECURE too (AGT-08).
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            show()
        }
    }

    // ---- Pair with code (AGT-13)

    private fun openPairing() {
        val id = vm.agentId ?: return
        val k = vm.key ?: return
        vm.pairWindow = app.pairing.open(id, vm.name.trim(), k)
        findNavController().navigate(R.id.pairCode)
    }

    // ---- Helpers

    private fun setSecure(on: Boolean) {
        val w = activity?.window ?: return
        if (on) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun snack(text: String) {
        Snackbar.make(b.root, text, Snackbar.LENGTH_LONG).setAnchorView(b.bottomBar).show()
    }

    private fun hideKeyboard() {
        val v = view ?: return
        ViewCompat.getWindowInsetsController(v)?.hide(WindowInsetsCompat.Type.ime())
    }
}
