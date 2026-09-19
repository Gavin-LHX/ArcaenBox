package io.nekohasekai.sagernet.widget.glass

import android.animation.ValueAnimator
import android.os.Build
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs
import kotlin.math.tanh

/** Visual feedback only: the original control still owns clicks, scrolling and accessibility. */
class GlassTouch(
    private val view: View,
    private val glass: GlassDrawable,
    private val transform: Boolean = false,
) {
    private var pointer = -1
    private var downX = 0f
    private var downY = 0f
    private var progress = 0f
    private var dx = 0f
    private var dy = 0f
    private val density = view.resources.displayMetrics.density
    private val pressSpring = spring { progress = it; render() }
    private val xSpring = spring { dx = it; render() }
    private val ySpring = spring { dy = it; render() }

    private fun spring(update: (Float) -> Unit) = SpringAnimation(FloatValueHolder()).apply {
        spring = SpringForce(0f).setDampingRatio(.72f).setStiffness(520f)
        minimumVisibleChange = .002f
        addUpdateListener { _, value, _ -> update(value) }
    }

    fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!view.isEnabled) return
                pointer = event.getPointerId(0)
                downX = event.rawX; downY = event.rawY
                glass.lightX = (event.x / view.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                glass.lightY = (event.y / view.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                if (motionEnabled(view)) pressSpring.animateToFinalPosition(1f)
                else { progress = 1f; render() }
            }
            MotionEvent.ACTION_MOVE -> if (pointer != -1) {
                val index = event.findPointerIndex(pointer)
                if (index < 0) { release(); return }
                // Raw coordinates avoid feedback from our own translation/scale.
                val rawX = event.rawX + event.getX(index) - event.x
                val rawY = event.rawY + event.getY(index) - event.y
                glass.lightX = (event.getX(index) / view.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                glass.lightY = (event.getY(index) / view.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                if (transform && motionEnabled(view)) {
                    xSpring.animateToFinalPosition(tanh((rawX - downX) / (view.width.coerceAtLeast(1) * .65f)))
                    ySpring.animateToFinalPosition(tanh((rawY - downY) / (view.height.coerceAtLeast(1) * .65f)))
                }
                view.invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointer) release()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
        }
    }

    private fun release() {
        pointer = -1
        if (motionEnabled(view)) {
            pressSpring.animateToFinalPosition(0f)
            xSpring.animateToFinalPosition(0f)
            ySpring.animateToFinalPosition(0f)
        } else reset()
    }

    private fun render() {
        glass.press = progress.coerceIn(0f, 1f)
        if (transform) {
            val p = if (motionEnabled(view)) progress.coerceIn(-.15f, 1.15f) else 0f
            val sx = (4f * density / view.width.coerceAtLeast(1)).coerceAtMost(.09f)
            val sy = (4f * density / view.height.coerceAtLeast(1)).coerceAtMost(.09f)
            view.scaleX = 1f + p * sx + abs(dx) * sx * .6f
            view.scaleY = 1f + p * sy + abs(dy) * sy * .6f
            view.translationX = dx * 8f * density
            view.translationY = dy * 8f * density
        }
        view.invalidate()
    }

    fun reset() {
        pressSpring.cancel(); xSpring.cancel(); ySpring.cancel()
        pointer = -1; progress = 0f; dx = 0f; dy = 0f
        // Reset spring values as well, so a recycled control cannot revive an old press.
        pressSpring.setStartValue(0f); xSpring.setStartValue(0f); ySpring.setStartValue(0f)
        render()
    }

    companion object {
        fun motionEnabled(view: View): Boolean = if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled()
        else Settings.Global.getFloat(view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}
