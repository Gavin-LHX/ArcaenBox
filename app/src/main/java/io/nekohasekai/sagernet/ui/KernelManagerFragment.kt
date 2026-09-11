package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.update.CoreRuntime
import io.nekohasekai.sagernet.update.NativeComponents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KernelManagerFragment : ToolbarFragment(R.layout.layout_kernel_manager) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.kernel_manager)
        val list = view.findViewById<LinearLayout>(R.id.kernel_list)
        val entries = linkedMapOf("sing-box" to "sing-box").apply { putAll(NativeComponents.names) }
        entries.forEach { (id, name) ->
            val button = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = name; isAllCaps = false; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                setPadding(32, 24, 32, 24)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 16 }
                setOnClickListener {
                    (requireActivity() as MainActivity).displayFragment(if (id == "sing-box") CoreUpdatesFragment() else NativeUpdatesFragment.create(id))
                }
            }
            list.addView(button)
            viewLifecycleOwner.lifecycleScope.launch {
                val status = withContext(Dispatchers.IO) {
                    if (id == "sing-box") CoreRuntime.active.let { it.version to it.channel }
                    else NativeComponents.selected(id).let { it.version to it.channel }
                }
                button.text = "$name\n${status.first} · ${getString(if (status.second == "preview") R.string.kernel_preview else R.string.core_stable)}"
            }
        }
    }
}
