package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.WindowInsets
import androidx.coordinatorlayout.widget.CoordinatorLayout
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.widget.glass.ContentBackdrop
import io.nekohasekai.sagernet.widget.glass.GlassScene

/** Keeps this content subtree's consumed insets from reaching sibling bars on older Android. */
class MainContentLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : CoordinatorLayout(context, attrs) {
    private val glass = DataStore.interfaceStyle == "liquid_glass"
    private val backdrop = if (glass && !DataStore.glassReduceTransparency && Build.VERSION.SDK_INT >= 31) ContentBackdrop() else null
    private val sourcePosition = IntArray(2)
    private val targetPosition = IntArray(2)

    init { if (glass) background = GlassScene(this) }

    override fun draw(canvas: Canvas) {
        if (Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated && backdrop != null && width > 0 && height > 0) {
            backdrop.draw(canvas, width, height) { super.draw(it) }
        } else super.draw(canvas)
    }

    fun drawGlassBackdrop(canvas: Canvas, target: View) {
        if (Build.VERSION.SDK_INT < 31 || !canvas.isHardwareAccelerated || backdrop?.node?.hasDisplayList() != true) return
        getLocationOnScreen(sourcePosition); target.getLocationOnScreen(targetPosition)
        val save = canvas.save()
        canvas.translate((sourcePosition[0] - targetPosition[0]).toFloat(), (sourcePosition[1] - targetPosition[1]).toFloat())
        canvas.drawRenderNode(backdrop.node)
        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= 31) backdrop?.release()
        super.onDetachedFromWindow()
    }

    override fun dispatchApplyWindowInsets(insets: WindowInsets): WindowInsets {
        super.dispatchApplyWindowInsets(insets)
        // Pre-Android 11 ViewGroups can otherwise pass the modified result to the
        // following sibling, depriving the bottom bar and drawer of their safe area.
        return insets
    }
}
