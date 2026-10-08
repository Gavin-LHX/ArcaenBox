package io.nekohasekai.sagernet.widget.glass

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.getColorAttr

class GlassButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : MaterialButton(context, attrs) {
    private val glass = if (DataStore.interfaceStyle == "liquid_glass") GlassDrawable(this, radiusDp = 28f) else null
    private val touch = glass?.let { GlassTouch(this, it, transform = true) }
    private val focusPaint = glass?.let {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * resources.displayMetrics.density
            color = context.getColorAttr(R.attr.colorPrimary)
        }
    }
    private val focusBounds = RectF()

    init {
        if (glass != null) {
            glass.callback = this
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            rippleColor = ColorStateList.valueOf(Color.TRANSPARENT)
            strokeWidth = 0
            elevation = 0f
            val foreground = context.getColorAttr(R.attr.colorOnSurface)
            setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf((foreground and 0xFFFFFF) or 0x66000000, foreground)))
            iconTint = textColors
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (glass != null && parent is MaterialButtonToggleGroup) {
            (parent as MaterialButtonToggleGroup).let { group ->
                if (group.background !is GlassDrawable) group.background = GlassDrawable(group, radiusDp = 28f)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (parent !is MaterialButtonToggleGroup || isChecked || isPressed) {
            glass?.apply {
                val vertical = (6 * resources.displayMetrics.density).toInt()
                setBounds(1, vertical, width-1, height-vertical)
                alpha = if (isEnabled) 255 else 100
                draw(canvas)
            }
        }
        super.onDraw(canvas)
        // The glass is drawn above MaterialButton's background ripple. Keep a
        // visible focus/hover ring above it for keyboard and pointer navigation.
        if (isEnabled && (isFocused || isHovered)) focusPaint?.let { paint ->
            val density = resources.displayMetrics.density
            val inset = paint.strokeWidth / 2
            focusBounds.set(1f + inset, 6 * density + inset, width - 1f - inset, height - 6 * density - inset)
            if (!focusBounds.isEmpty) {
                paint.alpha = if (isFocused) 255 else 160
                val radius = minOf(28 * density, focusBounds.width() / 2, focusBounds.height() / 2)
                canvas.drawRoundRect(focusBounds, radius, radius, paint)
            }
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if (glass != null) invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        touch?.onTouch(event)
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        touch?.reset()
        super.onDetachedFromWindow()
    }
}
