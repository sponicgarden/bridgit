package net.finleg.bridget.ui.more

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import net.finleg.bridget.R
import net.finleg.bridget.databinding.FragmentWhatCanBinding
import net.finleg.bridget.databinding.WhatcanCardBinding
import net.finleg.bridget.databinding.WhatcanHeaderBinding
import net.finleg.bridget.databinding.WhatcanSectionBinding
import net.finleg.bridget.ui.common.themeColor
import com.google.android.material.R as M

/** More → What can Bridget do? One card per feature with a use case, grouped by when it arrives. */
class WhatCanFragment : Fragment() {
    private var _b: FragmentWhatCanBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentWhatCanBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(b.toolbar) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top); insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.list) { v, insets ->
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom + v.context.resources.getDimensionPixelSize(R.dimen.whatcan_gap)); insets
        }
        b.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        val abilities = WhatCanData.all.filter { it.kind == WhatCanData.Kind.ABILITY }
        b.toolbar.subtitle = getString(R.string.whatcan_subtitle, abilities.count { it.stage == WhatCanData.Stage.NOW }, abilities.size)
        // Two parts: what agents can do with the phone, then how Bridget works (owner request).
        val rows = mutableListOf<Any>()
        for ((kind, title, intro) in listOf(
            Triple(WhatCanData.Kind.ABILITY, R.string.whatcan_section_abilities, R.string.whatcan_section_abilities_intro),
            Triple(WhatCanData.Kind.MECHANIC, R.string.whatcan_section_mechanics, R.string.whatcan_section_mechanics_intro))) {
            rows += Section(getString(title), getString(intro))
            var last: String? = null
            for (f in WhatCanData.all.filter { it.kind == kind }) {
                val group = groupOf(f.stage)
                if (group != last) { rows += group; last = group }
                rows += f
            }
        }
        b.list.adapter = Adapter(rows)
    }

    private fun groupOf(s: WhatCanData.Stage) = getString(when (s) {
        WhatCanData.Stage.NOW -> R.string.whatcan_group_now
        WhatCanData.Stage.P2 -> R.string.whatcan_group_next
        WhatCanData.Stage.LATER, WhatCanData.Stage.IDEA -> R.string.whatcan_group_ideas
        else -> R.string.whatcan_group_planned
    })

    private data class Section(val title: String, val intro: String)

    private class Adapter(val rows: List<Any>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        class H(val b: WhatcanHeaderBinding) : RecyclerView.ViewHolder(b.root)
        class C(val b: WhatcanCardBinding) : RecyclerView.ViewHolder(b.root)
        class S(val b: WhatcanSectionBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemViewType(position: Int) = when (rows[position]) { is String -> 0; is Section -> 2; else -> 1 }
        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> H(WhatcanHeaderBinding.inflate(inf, parent, false))
                2 -> S(WhatcanSectionBinding.inflate(inf, parent, false))
                else -> C(WhatcanCardBinding.inflate(inf, parent, false))
            }
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            if (h is H) { h.b.root.text = row as String; return }
            if (h is S) { val sec = row as Section; h.b.title.text = sec.title; h.b.intro.text = sec.intro; return }
            val f = row as WhatCanData.Feature
            val b = (h as C).b
            val ctx = b.root.context
            b.title.text = f.title
            b.what.text = f.what
            b.useCase.text = f.useCase
            b.stage.text = f.stage.label
            b.ref.text = ctx.getString(R.string.whatcan_ref, f.ref)
            // Spec references are for the technical part only; the use cases stay plain.
            b.ref.isVisible = f.ref.isNotEmpty() && f.kind == WhatCanData.Kind.MECHANIC
            val (bg, fg) = when (f.stage) {
                WhatCanData.Stage.NOW -> R.attr.colorSuccessContainer to R.attr.colorOnSuccessContainer
                WhatCanData.Stage.P2 -> M.attr.colorPrimaryContainer to M.attr.colorOnPrimaryContainer
                WhatCanData.Stage.IDEA -> M.attr.colorSecondaryContainer to M.attr.colorOnSecondaryContainer
                else -> M.attr.colorSurfaceContainerHighest to M.attr.colorOnSurfaceVariant
            }
            b.stage.background.mutate().setTint(ctx.themeColor(bg))
            b.stage.setTextColor(ctx.themeColor(fg))
            b.root.contentDescription = "${f.title}. ${f.stage.label}. ${f.what} For example: ${f.useCase}"
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
