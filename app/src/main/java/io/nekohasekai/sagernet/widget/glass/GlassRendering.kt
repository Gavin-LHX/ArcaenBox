package io.nekohasekai.sagernet.widget.glass

import android.graphics.*
import android.os.Build
import androidx.annotation.RequiresApi

/** Owned by the content subtree; glass siblings can sample it without recursion or readback. */
@RequiresApi(31)
class ContentBackdrop {
    internal val node = RenderNode("ArcaenBox content backdrop")

    fun draw(canvas: Canvas, width: Int, height: Int, content: (Canvas) -> Unit) {
        node.setPosition(0, 0, width, height)
        val recording = node.beginRecording(width, height)
        try { content(recording) } finally { node.endRecording() }
        canvas.drawRenderNode(node)
    }

    fun release() = node.discardDisplayList()
}

@RequiresApi(31)
internal class GlassRendering(private val lens: Boolean, private val blur: Float) {
    private val node = RenderNode("ArcaenBox glass surface")
    private val refraction = if (lens && Build.VERSION.SDK_INT >= 33) Refraction() else null
    private var effectKey = ""

    fun draw(canvas: Canvas, width: Int, height: Int, radius: Float, press: Float, content: (Canvas) -> Unit) {
        node.setPosition(0, 0, width, height)
        val recording = node.beginRecording(width, height)
        try { content(recording) } finally { node.endRecording() }
        val key = "$width:$height:$radius:$press"
        if (key != effectKey) {
            effectKey = key
            val frost = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
            node.setRenderEffect(if (Build.VERSION.SDK_INT >= 33 && refraction != null)
                RenderEffect.createChainEffect(refraction.effect(width, height, radius, press), frost)
                else frost)
        }
        canvas.drawRenderNode(node)
    }
}

@RequiresApi(33)
private class Refraction {
    // Rounded-rectangle SDF bends the captured backdrop at the lens rim, not the icon/text.
    private val shader = RuntimeShader("""
        uniform shader backdrop;
        uniform float2 size;
        uniform float radius;
        uniform float strength;
        float distanceToEdge(float2 p) {
            float2 q = abs(p - size * 0.5) - (size * 0.5 - radius);
            return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
        }
        half4 main(float2 p) {
            float d = distanceToEdge(p);
            float2 gradient = float2(distanceToEdge(p + float2(1, 0)) - distanceToEdge(p - float2(1, 0)),
                                     distanceToEdge(p + float2(0, 1)) - distanceToEdge(p - float2(0, 1)));
            float2 normal = gradient / max(length(gradient), 0.001);
            float rim = 1.0 - smoothstep(0.0, max(radius * 0.7, 1.0), -d);
            float2 bent = clamp(p - normal * strength * rim * (1.0 - 0.4 * rim), float2(0.5), size - 0.5);
            half4 color = backdrop.eval(bent);
            return color;
        }
    """.trimIndent())

    fun effect(width: Int, height: Int, radius: Float, press: Float): RenderEffect {
        shader.setFloatUniform("size", width.toFloat(), height.toFloat())
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("strength", radius * (.28f + press * .18f))
        return RenderEffect.createRuntimeShaderEffect(shader, "backdrop")
    }
}
