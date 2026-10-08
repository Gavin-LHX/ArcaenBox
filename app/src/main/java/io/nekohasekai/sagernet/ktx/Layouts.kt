package io.nekohasekai.sagernet.ktx

import android.graphics.Rect
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.MainHostActivity

class FixedLinearLayoutManager(val recyclerView: RecyclerView) :
    LinearLayoutManager(recyclerView.context, RecyclerView.VERTICAL, false) {

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        val host = recyclerView.context.findActivity() as? MainHostActivity
        if (host != null) {
            val padding = (host.listBottomPaddingDp * recyclerView.resources.displayMetrics.density).toInt()
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

    override fun scrollVerticallyBy(
        dx: Int, recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): Int {
        val host = recyclerView.context.findActivity() as? MainHostActivity
        if (!DataStore.showBottomBar || host?.fabFollowsListScroll != true) {
            return super.scrollVerticallyBy(dx, recycler, state)
        }

        // SagerNet Style
        val scrollRange = super.scrollVerticallyBy(dx, recycler, state)
        // A shell can temporarily withhold its FAB while another page is on top.
        // Re-check it on each scroll so returning to the node list restores this behavior.
        val fab = host?.fabView ?: return scrollRange

        val overscroll = dx - scrollRange
        if (overscroll > 0) {
            val view =
                (recyclerView.findViewHolderForAdapterPosition(findLastVisibleItemPosition())
                    ?: return scrollRange).itemView
            val itemLocation = Rect().also { view.getGlobalVisibleRect(it) }
            val fabLocation = Rect().also { fab.getGlobalVisibleRect(it) }
            if (!itemLocation.contains(fabLocation.left, fabLocation.top) && !itemLocation.contains(
                    fabLocation.right,
                    fabLocation.bottom
                )
            ) {
                return scrollRange
            }
            fab.apply {
                if (isShown) hide()
            }
        } else {
            fab.apply {
                if (!isShown) show()
            }
        }
        return scrollRange
    }

}
