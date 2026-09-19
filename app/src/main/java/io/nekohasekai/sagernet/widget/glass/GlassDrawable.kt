package io.nekohasekai.sagernet.widget.glass

import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import androidx.core.graphics.ColorUtils
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.widget.MainContentLayout

/** Native glass with separate backdrop, material, edge highlights and foreground layers. */
class GlassDrawable(
    private val view: View,
    private val radiusDp: Float = 26f,
    private val insetDp: Float = 0f,
    private val sampleContent: Boolean = false,
    private val lens: Boolean = false,
    heavy: Boolean = false,
) : Drawable() {
    private val density = view.resources.displayMetrics.density
    private val dark = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val reduced = DataStore.glassReduceTransparency
    private val surface = view.context.getColorAttr(R.attr.colorSurface)
    private val scene = GlassScene(view)
    private val renderer = if (!reduced && Build.VERSION.SDK_INT >= 31) GlassRendering(lens, (if (heavy) 16f else if (lens) 3f else 8f) * density) else null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private var highlight: Shader? = null
    private var wash: Shader? = null
    private var radius = 0f
    private var opacity = 255
    private val glow = RadialGradient(0f, 0f, 1f, intArrayOf(0xB3FFFFFF.toInt(), 0x30D5F7FF, Color.TRANSPARENT), floatArrayOf(0f, .42f, 1f), Shader.TileMode.CLAMP)
    private val lightMatrix = Matrix()
    var lightX = .3f
    var lightY = .2f
    var press = 0f
        set(value) { field = value; invalidateSelf() }

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
        rect.inset(insetDp * density, if (insetDp > 0) 6f * density else 0f)
        radius = minOf(radiusDp * density, rect.height() / 2, rect.width() / 2).coerceAtLeast(0f)
        path.reset(); path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        highlight = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
            if (dark) intArrayOf(0xAACFEFFF.toInt(), 0x22FFFFFF, 0x08FFFFFF, 0x60B2DDEC)
            else intArrayOf(Color.WHITE, 0x44FFFFFF, 0x18FFFFFF, 0xDDF1FFFF.toInt()),
            floatArrayOf(0f, .38f, .65f, 1f), Shader.TileMode.CLAMP)
        wash = LinearGradient(0f, rect.top, 0f, rect.bottom,
            if (dark) intArrayOf(0x92253341.toInt(), 0xAB162530.toInt())
            else intArrayOf(0xBFFFFFFF.toInt(), 0x73F1FAFF), null, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        val save = canvas.save()
        canvas.clipPath(path)
        if (reduced || renderer == null || !canvas.isHardwareAccelerated) {
            paint.shader = null
            paint.color = if (reduced) surface else ColorUtils.blendARGB(surface, if (dark) 0xFF294453.toInt() else Color.WHITE, .45f)
            paint.alpha = opacity
            canvas.drawPath(path, paint)
        } else if (Build.VERSION.SDK_INT >= 31) {
            canvas.translate(rect.left, rect.top)
            renderer.draw(canvas, rect.width().toInt().coerceAtLeast(1), rect.height().toInt().coerceAtLeast(1), radius, press) { backdrop ->
                backdrop.translate(-rect.left, -rect.top)
                scene.draw(backdrop)
                if (sampleContent) view.rootView.findViewById<MainContentLayout>(R.id.fragment_holder)?.drawGlassBackdrop(backdrop, view)
            }
            canvas.translate(-rect.left, -rect.top)
            paint.shader = wash; paint.alpha = opacity
            canvas.drawPath(path, paint)
        }
        if (press > 0f && !reduced) {
            // The reflected light follows the finger; foreground text/icons remain untouched.
            val spread = maxOf(rect.width(), rect.height()) * .85f
            lightMatrix.setScale(spread, spread)
            lightMatrix.postTranslate(rect.left + lightX * rect.width(), rect.top + lightY * rect.height())
            glow.setLocalMatrix(lightMatrix)
            paint.shader = glow
            paint.alpha = (opacity * press * (if (dark) .35f else .55f)).toInt()
            canvas.drawPath(path, paint)
        }
        canvas.restoreToCount(save)
        paint.shader = highlight
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density * (1f + press * .5f)
        paint.alpha = opacity
        val edge = RectF(rect).apply { inset(paint.strokeWidth / 2, paint.strokeWidth / 2) }
        canvas.drawRoundRect(edge, radius, radius, paint)
        paint.style = Paint.Style.FILL
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
