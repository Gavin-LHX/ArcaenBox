package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutCoreUpdatesBinding
import io.nekohasekai.sagernet.update.*
import kotlinx.coroutines.*

class NativeUpdatesFragment : ToolbarFragment(R.layout.layout_core_updates) {
    companion object { fun create(id: String) = NativeUpdatesFragment().apply { arguments = Bundle().apply { putString("component", id) } } }
    private val id get() = requireArguments().getString("component")!!.also { require(it in NativeComponents.names) }
    private var binding: LayoutCoreUpdatesBinding? = null
    private var channel = "stable"
    private var update: NativeUpdate? = null
    private var busy = false
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.title = NativeComponents.names.getValue(id)
        val ui = LayoutCoreUpdatesBinding.bind(view); binding = ui
        ui.coreRunningLabel.setText(R.string.kernel_enabled)
        ui.coreChannelHelp.setText(R.string.kernel_channel_help)
        ui.corePreview.setText(R.string.kernel_preview)
        ui.coreRestore.setText(R.string.kernel_restore)
        ui.coreApply.setText(R.string.kernel_apply)
        channel = savedInstanceState?.getString("channel") ?: NativeComponents.channel(id)
        ui.coreChannels.check(if (channel == "preview") R.id.core_preview else R.id.core_stable)
        ui.coreChannels.addOnButtonCheckedListener { _, button, checked -> if (checked) {
            channel = if (button == R.id.core_preview) "preview" else "stable"
            update = null; ui.coreStatus.text = ""; refresh()
        } }
        ui.coreCheck.setOnClickListener { check() }
        ui.coreDownload.setOnClickListener { download() }
        ui.coreApply.setOnClickListener { apply(false) }
        ui.coreRestore.setOnClickListener { apply(true) }
        refresh()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("channel", channel); super.onSaveInstanceState(outState) }
    override fun onDestroyView() { binding = null; super.onDestroyView() }
    private fun label(choice: NativeChoice) = getString(R.string.core_status,
        getString(if (choice.channel == "preview") R.string.kernel_preview else R.string.core_stable), choice.version, choice.revision,
        getString(if (choice.downloaded) R.string.core_downloaded else R.string.core_builtin))
    private fun refresh() {
        val ui = binding ?: return
        ui.coreCheck.isEnabled = !busy; ui.coreDownload.isEnabled = !busy
        ui.coreStable.isEnabled = !busy; ui.corePreview.isEnabled = !busy
        ui.coreApply.isEnabled = false; ui.coreRestore.isEnabled = !busy
        ui.coreDownload.isVisible = update != null
        val target = channel
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (enabled, available) = withContext(Dispatchers.IO) { NativeComponents.selected(id) to NativeComponents.choice(id, target) }
                if (target != channel || binding !== ui) return@launch
                ui.coreRunning.text = enabled.version
                ui.coreSelected.text = available?.let(::label) ?: getString(R.string.kernel_no_local)
                ui.coreDetails.text = label(enabled)
                ui.coreDetails.setOnClickListener { MaterialAlertDialogBuilder(requireContext()).setTitle(NativeComponents.names.getValue(id)).setMessage(if (id == "snell") R.string.snell_version_help else R.string.kernel_download_help).setPositiveButton(android.R.string.ok, null).show() }
                ui.coreApply.isEnabled = !busy && available != null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { ui.coreStatus.setText(UpdateMessages.resource(e)) }
        }
    }
    private fun check() {
        if (busy) return
        busy = true; update = null; refresh(); binding?.coreStatus?.setText(R.string.update_checking)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                update = withContext(Dispatchers.IO) { NativeComponents.check(id, channel) }
                val current = withContext(Dispatchers.IO) { NativeComponents.choice(id, channel) }
                val same = update?.manifest?.revision == current?.revision && update != null
                binding?.coreStatus?.text = if (same) getString(R.string.core_current) else update?.let { getString(R.string.core_available, it.manifest.version) } ?: getString(R.string.core_latest)
                binding?.coreDownload?.setText(if (same) R.string.core_reinstall else R.string.core_download)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { binding?.coreStatus?.setText(UpdateMessages.resource(e)) }
            finally { busy = false; if (binding != null) refresh() }
        }
    }
    private fun download() {
        val candidate = update ?: return
        if (busy) return
        busy = true; refresh(); binding?.coreProgress?.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { NativeComponents.install(candidate) { percent -> activity?.runOnUiThread {
                    binding?.coreProgress?.setProgressCompat(percent, true)
                    binding?.coreStatus?.text = getString(R.string.update_downloading, percent)
                } } }
                update = null; binding?.coreStatus?.setText(R.string.kernel_ready)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { binding?.coreStatus?.setText(UpdateMessages.resource(e)) }
            finally { busy = false; binding?.coreProgress?.isVisible = false; if (binding != null) refresh() }
        }
    }
    private fun apply(restore: Boolean) {
        if (busy) return
        MaterialAlertDialogBuilder(requireContext()).setTitle(if (restore) R.string.kernel_restore else R.string.kernel_apply)
            .setMessage(R.string.kernel_apply_help).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                busy = true; refresh()
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        if (DataStore.serviceState.canStop) SagerNet.stopService()
                        withTimeout(15000) { while (DataStore.serviceState != BaseService.State.Stopped) delay(100) }
                        val target = if (restore) "stable" else channel
                        withContext(Dispatchers.IO) { NativeComponents.select(id, target, restore) }
                        channel = target; update = null
                        binding?.coreChannels?.check(if (target == "preview") R.id.core_preview else R.id.core_stable)
                        binding?.coreStatus?.setText(R.string.kernel_applied)
                    } catch (_: TimeoutCancellationException) { binding?.coreStatus?.setText(R.string.update_stop_failed) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { binding?.coreStatus?.setText(UpdateMessages.resource(e)) }
                    finally { busy = false; if (binding != null) refresh() }
                }
            }.show()
    }
}
