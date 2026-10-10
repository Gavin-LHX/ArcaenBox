package io.nekohasekai.sagernet.ktx

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.MainActivity

class FixedLinearLayoutManager(val recyclerView: RecyclerView) :
    LinearLayoutManager(recyclerView.context, RecyclerView.VERTICAL, false) {

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        val activity = recyclerView.context.findActivity() as? MainActivity
        if (activity != null) {
            // Let rows scroll through the transparent idle anchor, with enough
            // trailing scroll space to bring the last control above the FAB.
            val padding = if (activity.binding.stats.allowShow && !DataStore.serviceState.connected)
                (80 * recyclerView.resources.displayMetrics.density).toInt() else 0
            if (recyclerView.paddingBottom != padding) {
                recyclerView.setPadding(recyclerView.paddingLeft, recyclerView.paddingTop, recyclerView.paddingRight, padding)
            }
            recyclerView.clipToPadding = false
        }
        try {
            super.onLayoutChildren(recycler, state)
        } catch (ignored: IndexOutOfBoundsException) {
        }
    }

}
