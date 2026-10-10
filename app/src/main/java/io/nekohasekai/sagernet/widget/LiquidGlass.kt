package io.nekohasekai.sagernet.widget

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.dp2pxf
import kotlin.math.PI
import kotlin.math.max

/** Whether this device can draw liquid glass at all. */
@get:ChecksSdkIntAtLeast(api = 31)
val isLiquidGlassSupported get() = Build.VERSION.SDK_INT >= 31

/** Whether the main screen draws its bottom bar and connect button as liquid glass. */
@get:ChecksSdkIntAtLeast(api = 31)
val isLiquidGlassEnabled get() = isLiquidGlassSupported && DataStore.liquidGlass

/**
 * Liquid glass surfaces for the View system.
 *
 * The rendering follows Backdrop (https://github.com/Kyant0/AndroidLiquidGlass, Apache-2.0),
 * which is Compose-only: the content behind the glass is recorded into a [RenderNode] and
 * passed through the same vibrancy → blur → lens refraction chain, then tinted and outlined
 * with a rim highlight. The AGSL shaders below are taken from that library.
 *
 * Lens refraction and the shaded highlight need Android 13. Android 12 gets the frosted part
 * (vibrancy and blur) with a plain rim, and older versions keep the solid Material surfaces.
 */
@RequiresApi(31)
class LiquidGlass(
    private val host: View,
    /** Views behind the glass, bottom to top. None of them may contain [host]. */
    private val sources: () -> List<View>,
) {

    /** Surface color drawn over the refracted backdrop; keep it translucent. */
    @ColorInt
    var tint = 0

    /** Opaque color under the backdrop, normally the window background. */
    @ColorInt
    var backdropColor = 0

    var cornerRadius = 0f
    var blurRadius = dp2pxf(8)
    var refractionHeight = dp2pxf(24)
    var refractionAmount = dp2pxf(24)

    /**
     * Moves glass edges outside the host. A bar along the bottom of the screen uses this so
     * only its top edge bends the content, while the screen edges stay flat.
     */
    val bleed = RectF()

    private val glassNode = RenderNode("LiquidGlass")
    private val backdropNode = RenderNode("LiquidGlassBackdrop")
    private val glassBounds = RectF()
    private val outline = Outline()
    private val hostLocation = IntArray(2)
    private val sourceLocation = IntArray(2)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp2pxf(1)
        blendMode = BlendMode.PLUS
    }
    private val tintPaint = Paint()
    private var effectKey: List<Float>? = null

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        // Anything behind may have moved; the host redraws in the same frame.
        if (host.isShown) host.invalidate()
        true
    }
    private var observer: ViewTreeObserver? = null

    fun attach() {
        if (observer != null) return
        observer = host.viewTreeObserver.also { it.addOnPreDrawListener(preDrawListener) }
    }

    fun detach() {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDrawListener)
        observer = null
    }

    /** Draws the glass over the host's bounds; returns false where it cannot be drawn. */
    fun draw(canvas: Canvas): Boolean {
        if (!canvas.isHardwareAccelerated) return false
        val width = host.width
        val height = host.height
        if (width <= 0 || height <= 0) return true

        glassBounds.set(-bleed.left, -bleed.top, width + bleed.right, height + bleed.bottom)
        val glassWidth = glassBounds.width()
        val glassHeight = glassBounds.height()
        val padding = max(blurRadius * 2f, refractionHeight)

        recordBackdrop(glassWidth, glassHeight, padding)
        updateEffect(glassWidth, glassHeight, padding)

        glassNode.setPosition(
            glassBounds.left.toInt(), glassBounds.top.toInt(),
            glassBounds.right.toInt(), glassBounds.bottom.toInt(),
        )
        outline.setRoundRect(0, 0, glassWidth.toInt(), glassHeight.toInt(), cornerRadius)
        glassNode.setOutline(outline)
        glassNode.clipToOutline = true
        val glass = glassNode.beginRecording()
        try {
            glass.save()
            glass.translate(-padding, -padding)
            glass.drawRenderNode(backdropNode)
            glass.restore()
            tintPaint.color = tint
            glass.drawRect(0f, 0f, glassWidth, glassHeight, tintPaint)
            val inset = highlightPaint.strokeWidth / 2f
            glass.drawRoundRect(
                inset, inset, glassWidth - inset, glassHeight - inset,
                cornerRadius, cornerRadius, highlightPaint,
            )
        } finally {
            glassNode.endRecording()
        }

        canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.drawRenderNode(glassNode)
        canvas.restore()
        return true
    }

    private fun recordBackdrop(glassWidth: Float, glassHeight: Float, padding: Float) {
        backdropNode.setPosition(0, 0, (glassWidth + padding * 2).toInt(), (glassHeight + padding * 2).toInt())
        val backdrop = backdropNode.beginRecording()
        try {
            backdrop.drawColor(backdropColor)
            host.getLocationInWindow(hostLocation)
            for (source in sources()) {
                if (!source.isShown || source.width == 0 || source.height == 0) continue
                source.getLocationInWindow(sourceLocation)
                backdrop.save()
                backdrop.translate(
                    padding - glassBounds.left - (hostLocation[0] - sourceLocation[0]),
                    padding - glassBounds.top - (hostLocation[1] - sourceLocation[1]),
                )
                source.draw(backdrop)
                backdrop.restore()
            }
        } finally {
            backdropNode.endRecording()
        }
    }

    private fun updateEffect(glassWidth: Float, glassHeight: Float, padding: Float) {
        val key = listOf(glassWidth, glassHeight, padding, cornerRadius, blurRadius, refractionHeight, refractionAmount)
        if (key == effectKey) return
        effectKey = key

        var effect = RenderEffect.createColorFilterEffect(VibrancyFilter)
        if (blurRadius > 0f) effect = RenderEffect.createChainEffect(
            RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP), effect
        )
        val radii = cornerRadii(glassWidth, glassHeight)
        if (Build.VERSION.SDK_INT >= 33) {
            if (refractionHeight > 0f && refractionAmount > 0f) {
                val lens = RuntimeShader(RefractionShader).apply {
                    setFloatUniform("size", glassWidth, glassHeight)
                    setFloatUniform("offset", -padding, -padding)
                    setFloatUniform("cornerRadii", radii)
                    setFloatUniform("refractionHeight", refractionHeight)
                    setFloatUniform("refractionAmount", -refractionAmount)
                    setFloatUniform("depthEffect", 0f)
                }
                effect = RenderEffect.createChainEffect(
                    RenderEffect.createRuntimeShaderEffect(lens, "content"), effect
                )
            }
            highlightPaint.shader = RuntimeShader(HighlightShader).apply {
                setFloatUniform("size", glassWidth, glassHeight)
                setFloatUniform("cornerRadii", radii)
                setColorUniform("color", android.graphics.Color.WHITE)
                setFloatUniform("angle", (45.0 * PI / 180.0).toFloat())
                setFloatUniform("falloff", 1f)
            }
            highlightPaint.alpha = 128
        } else {
            highlightPaint.shader = null
            highlightPaint.color = android.graphics.Color.WHITE
            highlightPaint.alpha = 97
        }
        backdropNode.setRenderEffect(effect)
    }

    private fun cornerRadii(width: Float, height: Float): FloatArray {
        val radius = minOf(cornerRadius, width / 2f, height / 2f)
        return floatArrayOf(radius, radius, radius, radius)
    }
}

private val VibrancyFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.5f) })

// The shaders below are from Backdrop by Kyant, licensed under the Apache License 2.0.

private const val RoundedRectSDF = """
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}"""

private const val RefractionShader = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;

$RoundedRectSDF

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));

    float2 refractedCoord = coord + d * grad;
    return content.eval(refractedCoord);
}"""

private const val HighlightShader = """
uniform float2 size;
uniform float4 cornerRadii;
layout(color) uniform half4 color;
uniform float angle;
uniform float falloff;

$RoundedRectSDF

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float radius = radiusAt(coord, cornerRadii);

    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float2 normal = float2(cos(angle), sin(angle));
    float d = dot(grad, normal);
    float intensity = pow(abs(d), falloff);
    return color * intensity;
}"""
