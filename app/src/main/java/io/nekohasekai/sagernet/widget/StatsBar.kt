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
        TooltipCompat.setTooltipText(this, text)
    }

    fun changeState(state: BaseService.State) {
        val activity = context.findActivity() as MainActivity
        stateJob?.cancel()
        exitJob?.cancel()
        generation++
        exitIpText.visibility = View.GONE
        isEnabled = true
        if ((state == BaseService.State.Connected).also { hideOnScroll = it }) {
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

    fun refreshExitIp() {
        exitJob?.cancel()
        isEnabled = true
        val epoch = ++generation
        if (!DataStore.showExitIp || !DataStore.serviceState.connected) {
            exitIpText.visibility = View.GONE
            return
        }
        exitIpText.visibility = View.VISIBLE
        exitIpText.setText(R.string.exit_ip_querying)
        val activity = context.findActivity() as MainActivity
        exitJob = activity.lifecycleScope.launch {
            try {
                val port = withContext(Dispatchers.IO) { activity.connection.service?.exitProbePort ?: 0 }
                val ip = ExitIpLookup.query(port, DataStore.exitIpURL)
                if (epoch == generation && DataStore.serviceState.connected) {
                    exitIpText.text = context.getString(R.string.exit_ip_value, ip)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w("Exit IP lookup failed: ${e.javaClass.simpleName}")
                if (epoch == generation && DataStore.serviceState.connected) {
                    exitIpText.setText(R.string.exit_ip_failed)
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateSpeed(txRate: Long, rxRate: Long) {
        txText.text = "▲  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, txRate)
            )
        }"
        rxText.text = "▼  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, rxRate)
            )
        }"
    }

    fun testConnection() {
        val activity = context.findActivity() as MainActivity
        refreshExitIp()
        val epoch = generation
        isEnabled = false
        setStatus(app.getText(R.string.connection_test_testing))
        runOnDefaultDispatcher {
            try {
                val elapsed = activity.urlTest()
                onMainDispatcher {
                    if (epoch != generation || !DataStore.serviceState.connected) return@onMainDispatcher
                    isEnabled = true
                    setStatus(
                        app.getString(
                            if (DataStore.connectionTestURL.startsWith("https://")) {
                                R.string.connection_test_available
                            } else {
                                R.string.connection_test_available_http
                            }, elapsed
                        )
                    )
                }

            } catch (e: Exception) {
                Logs.w(e.toString())
                onMainDispatcher {
                    if (epoch != generation || !DataStore.serviceState.connected) return@onMainDispatcher
                    isEnabled = true
                    setStatus(app.getText(R.string.vpn_connected))

                    activity.snackbar(
                        app.getString(
                            R.string.connection_test_error, e.readableMessage
                        )
                    ).show()
                }
            }
        }
    }

}
