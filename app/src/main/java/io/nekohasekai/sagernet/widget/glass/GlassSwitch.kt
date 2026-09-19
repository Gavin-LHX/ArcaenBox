package io.nekohasekai.sagernet.widget.glass

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import com.google.android.material.materialswitch.MaterialSwitch
import io.nekohasekai.sagernet.database.DataStore

/** Retains SwitchCompat's thumb dragging, RTL, keyboard and checked-state semantics. */
class GlassSwitch @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : MaterialSwitch(context, attrs) {
    private val glass = if (DataStore.interfaceStyle == "liquid_glass") GlassDrawable(this, radiusDp = 20f, lens = true) else null
    private val touch = glass?.let { GlassTouch(this, it) }

    init {
        glass?.let { material ->
            val density = resources.displayMetrics.density
            thumbIconDrawable = null
            trackDecorationDrawable = null
            thumbTintList = null
            trackTintList = null
            splitTrack = false
            switchMinWidth = (52 * density).toInt()
            trackDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * density
                setSize((52 * density).toInt(), (32 * density).toInt())
                color = ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(0x33787880, 0xFF34C759.toInt(), 0x55787880),
                )
            }
            material.callback = this
            material.underlay = { canvas -> trackDrawable?.draw(canvas) }
            thumbDrawable = object : Drawable() {
                override fun getIntrinsicWidth() = (28 * density).toInt()
                override fun getIntrinsicHeight() = (28 * density).toInt()
                override fun draw(canvas: Canvas) {
                    // Native switch bounds determine the thumb position throughout a drag.
                    val cx = bounds.exactCenterX(); val cy = bounds.exactCenterY()
                    val half = 14 * density
                    material.setBounds((cx-half).toInt(), (cy-half).toInt(), (cx+half).toInt(), (cy+half).toInt())
                    material.alpha = if (isEnabled) 255 else 110
                    val p = if (GlassTouch.motionEnabled(this@GlassSwitch)) material.press else 0f
                    val save = canvas.save()
                    canvas.scale(1f + p * .16f, 1f + p * .24f, cx, cy)
                    material.draw(canvas)
                    canvas.restoreToCount(save)
                }
                override fun setAlpha(alpha: Int) = Unit
                override fun setColorFilter(colorFilter: ColorFilter?) = Unit
                @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
            }
        }
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
