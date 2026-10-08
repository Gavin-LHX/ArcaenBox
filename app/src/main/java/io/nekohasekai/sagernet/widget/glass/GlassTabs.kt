package io.nekohasekai.sagernet.widget.glass

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.database.DataStore

class GlassTabs @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : TabLayout(context, attrs) {
    private var touch: GlassTouch? = null
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (DataStore.interfaceStyle == "liquid_glass") {
            val glass = GlassDrawable(this, radiusDp = 24f, insetDp = 10f)
            background = glass
            touch = GlassTouch(this, glass)
            // Keep the moving pill inside the inset outer rail, including the end tabs.
            setSelectedTabIndicator(GlassDrawable(this, radiusDp = 20f, insetDp = 14f))
            setSelectedTabIndicatorHeight((44 * resources.displayMetrics.density).toInt())
            setSelectedTabIndicatorGravity(INDICATOR_GRAVITY_CENTER)
            tabIndicatorAnimationMode = INDICATOR_ANIMATION_MODE_ELASTIC
            isTabIndicatorFullWidth = true
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        touch?.onTouch(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        touch?.reset()
        super.onDetachedFromWindow()
    }
}
