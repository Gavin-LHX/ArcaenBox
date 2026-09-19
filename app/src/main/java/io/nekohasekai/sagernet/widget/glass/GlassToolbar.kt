package io.nekohasekai.sagernet.widget.glass

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import com.google.android.material.appbar.MaterialToolbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore

/** Same toolbar actions and touch targets in both styles. */
class GlassToolbar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : MaterialToolbar(context, attrs) {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (DataStore.interfaceStyle != "liquid_glass") return
        (parent as? View)?.takeIf { it.id == R.id.appbar }?.setBackgroundColor(Color.TRANSPARENT)
        background = GlassDrawable(this, radiusDp = 28f, insetDp = 10f)
        elevation = 0f
    }
}
