package net.finleg.bridget.ui.agents

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import net.finleg.bridget.databinding.FragmentAgentsBinding

/** Placeholder; replaced by the screen implementation. */
class AgentsFragment : Fragment() {
    private var _b: FragmentAgentsBinding? = null
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentAgentsBinding.inflate(inflater, container, false)
        return _b!!.root
    }
    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
