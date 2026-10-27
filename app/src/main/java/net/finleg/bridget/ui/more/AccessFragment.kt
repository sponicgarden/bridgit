package net.finleg.bridget.ui.more

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import net.finleg.bridget.databinding.FragmentAccessBinding

/** Placeholder; replaced by the screen implementation. */
class AccessFragment : Fragment() {
    private var _b: FragmentAccessBinding? = null
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentAccessBinding.inflate(inflater, container, false)
        return _b!!.root
    }
    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
