package io.nekohasekai.sagernet.widget.glass

import android.content.Context
import android.util.AttributeSet
import android.view.WindowInsets
import androidx.coordinatorlayout.widget.CoordinatorLayout

/** Coordinates floating controls separately from the content-only backdrop. */
class GlassShellLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : CoordinatorLayout(context, attrs) {
    override fun dispatchApplyWindowInsets(insets: WindowInsets): WindowInsets {
        super.dispatchApplyWindowInsets(insets)
        // Older Android versions propagate consumed insets to following siblings.
        // Keep the navigation bar's safe area even when the page consumes its own.
        return insets
    }
}
