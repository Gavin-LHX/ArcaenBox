package io.nekohasekai.sagernet.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jakewharton.processphoenix.ProcessPhoenix
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutCoreUpdatesBinding
import io.nekohasekai.sagernet.update.*
import kotlinx.coroutines.*
import libcore.Libcore

class CoreUpdatesFragment : ToolbarFragment(R.layout.layout_core_updates) {
    private var binding: LayoutCoreUpdatesBinding? = null
    private var channel = "stable"
    private var update: CoreUpdate? = null
    private var busy = false
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view,savedInstanceState)
        toolbar.setTitle(R.string.core_manager)
        val ui = LayoutCoreUpdatesBinding.bind(view); binding = ui
        channel = savedInstanceState?.getString("channel") ?: CoreRuntime.active.channel
        ui.coreChannels.check(if (channel == "preview") R.id.core_preview else R.id.core_stable)
        ui.coreChannels.addOnButtonCheckedListener { _,id,checked -> if (checked) {
            channel = if (id == R.id.core_preview) "preview" else "stable"
            update = null; ui.coreStatus.text = ""; refresh()
        } }
        ui.coreCheck.setOnClickListener { check() }
        ui.coreDownload.setOnClickListener { download() }
        ui.coreApply.setOnClickListener { restart(false) }
        ui.coreRestore.setOnClickListener { restart(true) }
        if (CoreRuntime.recovered) ui.coreStatus.setText(R.string.core_recovered)
        refresh()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("channel",channel); super.onSaveInstanceState(outState) }
    override fun onDestroyView() { binding = null; super.onDestroyView() }
    private fun label(choice: CoreChoice) = getString(R.string.core_status,getString(if (choice.channel == "preview") R.string.core_preview else R.string.core_stable),choice.version,choice.revision,getString(if (choice.installed) R.string.core_downloaded else R.string.core_builtin))
    private fun refresh() {
        val ui = binding ?: return
        ui.coreRunning.text = label(CoreRuntime.active)
        ui.coreDetails.text = Libcore.versionBox()
        ui.coreDownload.isVisible = update != null
        ui.coreCheck.isEnabled = !busy; ui.coreDownload.isEnabled = !busy
        ui.coreStable.isEnabled = !busy; ui.corePreview.isEnabled = !busy
        ui.coreApply.isEnabled = !busy; ui.coreRestore.isEnabled = !busy
        viewLifecycleOwner.lifecycleScope.launch {
            val choice = withContext(Dispatchers.IO) { CoreRuntime.choice(channel) }
            binding?.coreSelected?.text = label(choice)
        }
    }
    private fun check() {
        if (busy) return
        busy = true; refresh(); binding?.coreStatus?.setText(R.string.update_checking)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                update = withContext(Dispatchers.IO) { CoreRuntime.check(channel) }
                val current = withContext(Dispatchers.IO) { CoreRuntime.choice(channel) }
                val same = update?.let { it.manifest.version == current.version && it.manifest.revision == current.revision } == true
                binding?.coreStatus?.text = if (same) getString(R.string.core_current) else update?.let { getString(R.string.core_available,it.manifest.displayVersion) } ?: getString(R.string.core_latest)
                binding?.coreDownload?.setText(if (same) R.string.core_reinstall else R.string.core_download)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { binding?.coreStatus?.setText(UpdateMessages.resource(e)) }
            finally { busy = false; if (binding != null) refresh() }
        }
    }
    private fun download() {
        val candidate = update ?: return
        if (busy) return
        busy = true; refresh(); binding?.coreProgress?.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { CoreRuntime.install(candidate) { percent ->
                    activity?.runOnUiThread { binding?.let { ui -> ui.coreProgress.setProgressCompat(percent,true); ui.coreStatus.text = getString(R.string.update_downloading,percent) } }
                } }
                update = null; binding?.coreStatus?.setText(R.string.core_ready)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { binding?.coreStatus?.setText(UpdateMessages.resource(e)) }
            finally { busy = false; binding?.coreProgress?.isVisible = false; if (binding != null) refresh() }
        }
    }
    private fun restart(restore: Boolean) {
        if (busy) return
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.core_restart_title)
            .setMessage(getString(R.string.core_restart_message,getString(if (channel == "preview") R.string.core_preview else R.string.core_stable)))
            .setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(R.string.core_switch) { _,_ ->
                val context = requireContext().applicationContext
                val target = channel
                busy = true; refresh()
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        if (DataStore.serviceState.canStop) SagerNet.stopService()
                        withTimeout(15000) {
                            while (DataStore.serviceState != BaseService.State.Stopped) delay(100)
                        }
                        withContext(Dispatchers.IO) { CoreRuntime.select(target,restore) }
                        // The background Go runtime must exit too; the next process reads the new selection.
                        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                        manager.runningAppProcesses?.filter { it.uid == Process.myUid() && it.processName == "${context.packageName}:bg" }?.forEach { Process.killProcess(it.pid) }
                        ProcessPhoenix.triggerRebirth(context,Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: TimeoutCancellationException) { binding?.coreStatus?.setText(R.string.update_stop_failed) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { binding?.coreStatus?.setText(UpdateMessages.resource(e)) }
                    finally { busy = false; if (binding != null) refresh() }
                }
            }.show()
    }
}
