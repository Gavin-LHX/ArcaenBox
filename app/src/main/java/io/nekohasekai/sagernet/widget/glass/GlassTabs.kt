package io.nekohasekai.sagernet.widget.glass

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.database.DataStore

class GlassTabs @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : TabLayout(context, attrs) {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (DataStore.interfaceStyle == "liquid_glass") background = GlassDrawable(this, radiusDp = 24f, insetDp = 10f)
    }
}
