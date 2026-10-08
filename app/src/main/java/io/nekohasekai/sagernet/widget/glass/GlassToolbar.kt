package io.nekohasekai.sagernet.widget.glass

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.view.MotionEvent
import com.google.android.material.appbar.MaterialToolbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore

/** Same toolbar actions and touch targets in both styles. */
class GlassToolbar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : MaterialToolbar(context, attrs) {
    private var glassTouch: GlassTouch? = null
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (DataStore.interfaceStyle != "liquid_glass") return
        (parent as? View)?.takeIf { it.id == R.id.appbar }?.setBackgroundColor(Color.TRANSPARENT)
        val glass = GlassDrawable(this, radiusDp = 28f, insetDp = 10f)
        background = glass
        glassTouch = GlassTouch(this, glass)
        elevation = 0f
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        glassTouch?.onTouch(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        glassTouch?.reset()
        super.onDetachedFromWindow()
    }
}
