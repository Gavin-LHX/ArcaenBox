package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import android.view.WindowInsets
import androidx.coordinatorlayout.widget.CoordinatorLayout

/** Keeps this content subtree's consumed insets from reaching sibling bars on older Android. */
class MainContentLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : CoordinatorLayout(context, attrs) {
    override fun dispatchApplyWindowInsets(insets: WindowInsets): WindowInsets {
        super.dispatchApplyWindowInsets(insets)
        // Pre-Android 11 ViewGroups can otherwise pass the modified result to the
        // following sibling, depriving the bottom bar and drawer of their safe area.
        return insets
    }
}
