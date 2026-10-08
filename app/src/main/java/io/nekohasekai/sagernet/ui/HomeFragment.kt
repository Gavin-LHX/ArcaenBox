package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutHomeBinding
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.utils.ExitIpLookup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Start page of the redesigned interface: connection control, live traffic and the current node. */
class HomeFragment : ToolbarFragment(R.layout.layout_home) {

    private var binding: LayoutHomeBinding? = null
    private var networkJob: Job? = null
    private var lastState: BaseService.State? = null

    private val host get() = requireActivity() as MainHostActivity

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = LayoutHomeBinding.bind(view)
        this.binding = binding
        toolbar.setTitle(R.string.app_name)

        binding.powerButton.setOnClickListener { host.toggleService() }
        binding.nodeCard.setOnClickListener { host.displayFragmentWithId(R.id.nav_configuration) }
        binding.retestButton.setOnClickListener { testNetwork() }
        binding.modeChip.setOnClickListener { host.displayFragmentWithId(R.id.nav_settings) }
        binding.actionClipboard.setOnClickListener { host.importFromClipboard() }
        binding.actionScan.setOnClickListener {
            startActivity(Intent(requireContext(), ScannerActivity::class.java))
        }
        binding.actionUpdate.setOnClickListener { host.confirmUpdateAllSubscriptions() }
        binding.actionApps.setOnClickListener {
            startActivity(Intent(requireContext(), AppManagerActivity::class.java))
        }

        // Keep the dashboard readable on tablets and in landscape.
        binding.homeScroll.addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left == oldRight - oldLeft) return@addOnLayoutChangeListener
            val maxWidth = resources.getDimensionPixelSize(R.dimen.shell_wide_content_width)
            val side = maxOf(resources.getDimensionPixelSize(R.dimen.shell_side_padding), (right - left - maxWidth) / 2)
            binding.homeContent.updatePadding(left = side, right = side)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { host.serviceState.collect { renderState(it) } }
                launch { host.speed.collect { (tx, rx) -> renderSpeed(tx, rx) } }
                launch {
                    host.selectedProxy.collect {
                        loadCurrentNode()
                        if (DataStore.serviceState.connected) testNetwork()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding?.modeChip?.text = getString(
            R.string.shell_mode, getString(
                if (DataStore.serviceMode == Key.MODE_VPN) R.string.service_mode_vpn else R.string.service_mode_proxy
            )
        )
        loadCurrentNode()
    }

    private fun renderState(state: BaseService.State) {
        val binding = binding ?: return
        val context = requireContext()
        val connected = state == BaseService.State.Connected
        val busy = state == BaseService.State.Connecting || state == BaseService.State.Stopping

        binding.statusTitle.setText(
            when (state) {
                BaseService.State.Connecting -> R.string.connecting
                BaseService.State.Connected -> R.string.vpn_connected_short
                BaseService.State.Stopping -> R.string.stopping
                else -> R.string.not_connected
            }
        )
        binding.statusSubtitle.setText(
            when (state) {
                BaseService.State.Connecting -> R.string.shell_status_connecting
                BaseService.State.Connected -> R.string.shell_status_connected
                BaseService.State.Stopping -> R.string.shell_status_stopping
                else -> R.string.shell_status_idle
            }
        )

        val cardColor = context.getColorAttr(
            if (connected) R.attr.colorPrimaryContainer
            else R.attr.colorSurfaceContainerHigh
        )
        val onCardColor = context.getColorAttr(
            if (connected) R.attr.colorOnPrimaryContainer
            else R.attr.colorOnSurface
        )
        binding.statusCard.setCardBackgroundColor(cardColor)
        binding.statusTitle.setTextColor(onCardColor)
        binding.statusSubtitle.setTextColor(onCardColor)

        // Keep the glass surface owned by GlassButton across service transitions.
        val glass = DataStore.interfaceStyle == "liquid_glass"
        if (!glass) {
            binding.powerButton.backgroundTintList = ColorStateList.valueOf(
                context.getColorAttr(
                    if (connected) R.attr.colorPrimary
                    else R.attr.colorSurfaceContainerHighest
                )
            )
        }
        binding.powerButton.iconTint = ColorStateList.valueOf(
            context.getColorAttr(
                if (connected && !glass) R.attr.colorOnPrimary
                else R.attr.colorPrimary
            )
        )
        binding.powerButton.isEnabled = state.canStop || state == BaseService.State.Stopped
        binding.powerButton.contentDescription = getText(if (state.canStop) R.string.stop else R.string.connect)
        binding.statusProgress.isVisible = busy

        if (lastState != state) {
            lastState = state
            if (connected) testNetwork() else resetNetwork()
        }
    }

    private fun renderSpeed(tx: Long, rx: Long) {
        val binding = binding ?: return
        val context = requireContext()
        binding.uploadSpeed.text = context.getString(R.string.speed, Formatter.formatFileSize(context, tx))
        binding.downloadSpeed.text = context.getString(R.string.speed, Formatter.formatFileSize(context, rx))
    }

    private fun loadCurrentNode() {
        val id = DataStore.selectedProxy
        viewLifecycleOwner.lifecycleScope.launch {
            val node = withContext(Dispatchers.IO) {
                val profile = ProfileManager.getProfile(id) ?: return@withContext null
                val group = SagerDatabase.groupDao.getById(profile.groupId)?.displayName()
                Triple(profile, profile.displayType(), group)
            }
            val binding = binding ?: return@launch
            if (node == null) {
                binding.nodeName.setText(R.string.shell_no_node)
                binding.nodeDetail.isVisible = false
                binding.nodeLatency.isVisible = false
                return@launch
            }
            val (profile, type, group) = node
            binding.nodeName.text = profile.displayName()
            binding.nodeDetail.text = listOfNotNull(type, group?.takeIf { it.isNotBlank() }).joinToString(" · ")
            binding.nodeDetail.isVisible = true
            when (profile.status) {
                1 -> {
                    binding.nodeLatency.text = getString(R.string.available, profile.ping)
                    binding.nodeLatency.setTextColor(requireContext().getColour(R.color.material_green_500))
                    binding.nodeLatency.isVisible = true
                }

                2, 3 -> {
                    binding.nodeLatency.setText(R.string.unavailable)
                    binding.nodeLatency.setTextColor(requireContext().getColour(R.color.material_red_500))
                    binding.nodeLatency.isVisible = true
                }

                else -> binding.nodeLatency.isVisible = false
            }
        }
    }

    private fun resetNetwork() {
        networkJob?.cancel()
        val binding = binding ?: return
        binding.latencyValue.setText(R.string.shell_latency_idle)
        binding.exitIpValue.setText(R.string.shell_exit_ip_idle)
        binding.retestButton.isEnabled = false
    }

    /** Measures latency and the exit IP through the running proxy, never directly. */
    private fun testNetwork() {
        networkJob?.cancel()
        val binding = binding ?: return
        if (!DataStore.serviceState.connected) return resetNetwork()
        binding.retestButton.isEnabled = true
        binding.latencyValue.setText(R.string.shell_latency_pending)
        binding.exitIpValue.setText(if (DataStore.showExitIp) R.string.exit_ip_querying else R.string.shell_exit_ip_disabled)
        networkJob = viewLifecycleOwner.lifecycleScope.launch {
            val port = withContext(Dispatchers.IO) {
                runCatching { host.connection.service?.exitProbePort ?: 0 }.getOrDefault(0)
            }
            launch {
                val text = try {
                    val elapsed = ExitIpLookup.latency(port, DataStore.connectionTestURL, DataStore.nodeTestTimeout)
                    getString(R.string.shell_latency_value, elapsed.toInt())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    getString(R.string.shell_latency_failed)
                }
                if (DataStore.serviceState.connected) this@HomeFragment.binding?.latencyValue?.text = text
            }
            if (DataStore.showExitIp) launch {
                val text = try {
                    getString(R.string.exit_ip_value, ExitIpLookup.query(port, DataStore.exitIpURL))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    getString(R.string.shell_exit_ip_failed)
                }
                if (DataStore.serviceState.connected) this@HomeFragment.binding?.exitIpValue?.text = text
            }
        }
    }

    override fun onDestroyView() {
        networkJob?.cancel()
        lastState = null
        binding = null
        super.onDestroyView()
    }
}
