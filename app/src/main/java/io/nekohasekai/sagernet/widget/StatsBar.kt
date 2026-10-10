package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomappbar.BottomAppBar
import com.google.android.material.shape.MaterialShapeDrawable
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.utils.ExitIpLookup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {
    private lateinit var statusText: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private lateinit var exitIpText: TextView
    private lateinit var statsContent: View
    private val connectedBackgroundTint = backgroundTint
    private val connectedElevation = (background as? MaterialShapeDrawable)?.elevation ?: elevation
    private var stateJob: Job? = null
    private var exitJob: Job? = null
    private var generation = 0
    private lateinit var behavior: YourBehavior

    private val glass = if (Build.VERSION.SDK_INT >= 31 && DataStore.liquidGlass) {
        LiquidGlass(this) {
            listOfNotNull((parent as? ViewGroup)?.findViewById(R.id.fragment_holder))
        }.apply {
            tint = ColorUtils.setAlphaComponent(
                connectedBackgroundTint?.defaultColor ?: context.getColorAttr(R.attr.colorSurfaceContainer), 140
            )
            backdropColor = context.getColorAttr(android.R.attr.colorBackground)
            // Only the top edge bends the list; the sides and bottom meet the screen edges.
            val hidden = refractionHeight + blurRadius
            bleed.set(hidden, 0f, hidden, hidden)
        }
    } else null
    private var glassVisible = false

    var allowShow = true

    override fun getBehavior(): YourBehavior {
        if (!this::behavior.isInitialized) behavior = YourBehavior { allowShow }
        return behavior
    }

    class YourBehavior(val getAllowShow: () -> Boolean) : Behavior() {

        override fun onNestedScroll(
            coordinatorLayout: CoordinatorLayout, child: BottomAppBar, target: View,
            dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
            type: Int, consumed: IntArray,
        ) {
            super.onNestedScroll(
                coordinatorLayout,
                child,
                target,
                dxConsumed,
                dyConsumed + dyUnconsumed,
                dxUnconsumed,
                0,
                type,
                consumed
            )
        }

        override fun slideUp(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideUp(child)
        }

        override fun slideDown(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideDown(child)
        }
    }


    override fun setOnClickListener(l: OnClickListener?) {
        statusText = findViewById(R.id.status)
        txText = findViewById(R.id.tx)
        rxText = findViewById(R.id.rx)
        exitIpText = findViewById(R.id.exit_ip)
        statsContent = findViewById(R.id.stats_content)
        super.setOnClickListener(l)
    }

    private fun setStatus(text: CharSequence) {
        statusText.text = text
        TooltipCompat.setTooltipText(this, "$text · ${context.getString(R.string.status_retest)}")
    }

    fun changeState(state: BaseService.State) {
        val activity = context.findActivity() as MainActivity
        stateJob?.cancel()
        exitJob?.cancel()
        generation++
        exitIpText.visibility = View.GONE
        val connected = state == BaseService.State.Connected
        // Keep the inset-aware anchor laid out so the A button stays in place.
        // Only a live connection needs a visible surface or a tappable status area.
        statsContent.visibility = if (connected) View.VISIBLE else View.INVISIBLE
        backgroundTint = if (connected && glass == null) connectedBackgroundTint else ColorStateList.valueOf(Color.TRANSPARENT)
        glassVisible = connected && glass != null
        invalidate()
        elevation = if (connected) connectedElevation else 0f
        isEnabled = connected
        isClickable = connected
        isFocusable = connected
        hideOnScroll = false
        if (allowShow) performShow() else performHide()
        if (connected) {
            stateJob = activity.lifecycleScope.launch {
                delay(100L)
                if (allowShow) performShow()
                setStatus(app.getText(R.string.vpn_connected))
                refreshExitIp()
            }
        } else {
            updateSpeed(0, 0)
            statusText.text = null
            TooltipCompat.setTooltipText(this, null)
        }
    }

    fun refreshExitIp() = testConnection()

    @SuppressLint("SetTextI18n")
    fun updateSpeed(txRate: Long, rxRate: Long) {
        txText.text = "↑ ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, txRate)
            )
        }"
        rxText.text = "↓ ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, rxRate)
            )
        }"
    }

    fun testConnection() {
        exitJob?.cancel()
        val epoch = ++generation
        if (!DataStore.serviceState.connected) return
        val activity = context.findActivity() as MainActivity
        isEnabled = true
        setStatus(context.getText(R.string.status_latency_pending))
        exitIpText.visibility = if (DataStore.showExitIp) View.VISIBLE else View.GONE
        exitIpText.setText(R.string.exit_ip_querying)
        exitJob = activity.lifecycleScope.launch {
            val port = withContext(Dispatchers.IO) { runCatching { activity.connection.service?.exitProbePort ?: 0 }.getOrDefault(0) }
            launch {
                try {
                    val elapsed = ExitIpLookup.latency(port, DataStore.connectionTestURL, DataStore.nodeTestTimeout)
                    if (epoch == generation && DataStore.serviceState.connected)
                        setStatus(context.getString(R.string.status_latency, elapsed))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (epoch == generation && DataStore.serviceState.connected)
                        setStatus(context.getText(R.string.status_latency_failed))
                }
            }
            if (DataStore.showExitIp) launch {
                try {
                    val ip = ExitIpLookup.query(port, DataStore.exitIpURL)
                    if (epoch == generation && DataStore.serviceState.connected)
                        exitIpText.text = context.getString(R.string.exit_ip_value, ip)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (epoch == generation && DataStore.serviceState.connected)
                        exitIpText.setText(R.string.exit_ip_failed)
                }
            }
        }
    }

    override fun draw(canvas: Canvas) {
        if (Build.VERSION.SDK_INT >= 31 && glassVisible && glass?.draw(canvas) == false) {
            // Software rendering cannot record the backdrop; keep the solid surface.
            canvas.drawColor(connectedBackgroundTint?.defaultColor ?: context.getColorAttr(R.attr.colorSurfaceContainer))
        }
        super.draw(canvas)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= 31) glass?.attach()
    }

    override fun onDetachedFromWindow() {
        stateJob?.cancel(); exitJob?.cancel(); generation++
        if (Build.VERSION.SDK_INT >= 31) glass?.detach()
        super.onDetachedFromWindow()
    }
}
