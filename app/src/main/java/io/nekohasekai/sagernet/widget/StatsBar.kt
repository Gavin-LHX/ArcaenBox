package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenStarted
import com.google.android.material.bottomappbar.BottomAppBar
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
    private var stateJob: Job? = null
    private var exitJob: Job? = null
    private var generation = 0
    private lateinit var behavior: YourBehavior

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
        isEnabled = true
        hideOnScroll = false
        if (state == BaseService.State.Connected) {
            stateJob = activity.lifecycleScope.launch {
                delay(100L)
                if (allowShow) performShow()
                setStatus(app.getText(R.string.vpn_connected))
                refreshExitIp()
            }
        } else {
            // Material 3 anchors the connect button inside this bar. Hiding it
            // while disconnected also moves the only connect control off-screen.
            if (allowShow) performShow() else performHide()
            updateSpeed(0, 0)
            setStatus(
                context.getText(
                    when (state) {
                        BaseService.State.Connecting -> R.string.connecting
                        BaseService.State.Stopping -> R.string.stopping
                        else -> R.string.not_connected
                    }
                )
            )
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

    override fun onDetachedFromWindow() {
        stateJob?.cancel(); exitJob?.cancel(); generation++
        super.onDetachedFromWindow()
    }
}
