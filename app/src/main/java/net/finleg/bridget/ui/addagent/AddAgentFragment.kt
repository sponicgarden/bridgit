package net.finleg.bridget.ui.addagent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import net.finleg.bridget.databinding.FragmentAddAgentBinding

/** Placeholder; replaced by the screen implementation. */
class AddAgentFragment : Fragment() {
    private var _b: FragmentAddAgentBinding? = null
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentAddAgentBinding.inflate(inflater, container, false)
        return _b!!.root
    }
    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
