package io.nekohasekai.sagernet.widget.glass

import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.database.DataStore

/** A shared, quiet backdrop. Every surface samples the same window coordinates. */
internal class GlassScene(private val view: View) : Drawable() {
    private val dark = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val base = view.context.getColorAttr(R.attr.colorSurface)
    private val reduced = DataStore.glassReduceTransparency
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val location = IntArray(2)
    private val origin = IntArray(2)
    private var size = 0L
    private var glows = emptyList<Shader>()

    override fun draw(canvas: Canvas) {
        if (reduced) { canvas.drawColor(base); return }
        val root = view.rootView
        val w = root.width.coerceAtLeast(1).toFloat()
        val h = root.height.coerceAtLeast(1).toFloat()
        val key = (w.toLong() shl 32) or h.toLong()
        if (key != size) {
            size = key
            val colors = if (dark) intArrayOf(0xB0396179.toInt(), 0x703E3868, 0x70336465)
                else intArrayOf(0xB0A4DCEB.toInt(), 0x80C8C2EF.toInt(), 0x70B7E5E1)
            glows = listOf(
                RadialGradient(w * .88f, h * .12f, w * .95f, colors[0], Color.TRANSPARENT, Shader.TileMode.CLAMP),
                RadialGradient(w * .04f, h * .52f, w * .9f, colors[1], Color.TRANSPARENT, Shader.TileMode.CLAMP),
                RadialGradient(w * .95f, h * .9f, w, colors[2], Color.TRANSPARENT, Shader.TileMode.CLAMP),
            )
        }
        view.getLocationOnScreen(location)
        root.getLocationOnScreen(origin)
        val save = canvas.save()
        canvas.translate((origin[0] - location[0]).toFloat(), (origin[1] - location[1]).toFloat())
        paint.shader = null
        paint.color = base
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.color = Color.WHITE
        glows.forEach { paint.shader = it; canvas.drawRect(0f, 0f, w, h, paint) }
        paint.shader = null
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.OPAQUE
}
