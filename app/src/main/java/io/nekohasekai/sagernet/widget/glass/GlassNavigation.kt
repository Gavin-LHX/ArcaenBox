package io.nekohasekai.sagernet.widget.glass

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.navigationrail.NavigationRailView
import io.nekohasekai.sagernet.database.DataStore

private fun NavigationBarView.createGlassFeedback(): GlassTouch? {
    if (DataStore.interfaceStyle != "liquid_glass") return null
    // Only the surface changes. Native item ripples, selection, keyboard focus,
    // labels and accessibility remain above it and keep their normal behavior.
    val glass = GlassDrawable(this, radiusDp = 0f, sampleContent = true, heavy = true)
    background = glass
    elevation = 0f
    return GlassTouch(this, glass)
}

class GlassBottomNavigationView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : BottomNavigationView(context, attrs) {
    private val glassTouch = createGlassFeedback()

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        glassTouch?.onTouch(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        glassTouch?.reset()
        super.onDetachedFromWindow()
    }
}

class GlassNavigationRailView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : NavigationRailView(context, attrs) {
    private val glassTouch = createGlassFeedback()

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        glassTouch?.onTouch(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        glassTouch?.reset()
        super.onDetachedFromWindow()
    }
}
