package net.finleg.bridget.ui.firstrun

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import net.finleg.bridget.databinding.FragmentFirstRunBinding

/** Placeholder; replaced by the screen implementation. */
class FirstRunFragment : Fragment() {
    private var _b: FragmentFirstRunBinding? = null
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentFirstRunBinding.inflate(inflater, container, false)
        return _b!!.root
    }
    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
