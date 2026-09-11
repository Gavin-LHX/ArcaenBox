package io.nekohasekai.sagernet.ui

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.proto.NodeTestKind
import io.nekohasekai.sagernet.bg.proto.NodeTestSession
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.ktx.readableMessage
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

class NodeTestUi(private val fragment: Fragment) {
    companion object {
        fun title(kind: NodeTestKind) = when (kind) {
            NodeTestKind.TCP -> R.string.node_test_tcp
            NodeTestKind.URL -> R.string.node_test_url
            NodeTestKind.UDP -> R.string.node_test_udp
            NodeTestKind.SPEED -> R.string.node_test_speed
        }

        fun resultText(context: Context, result: NodeTestResult): String {
            val label = when (result.kind) { "TCP" -> "TCP"; "URL" -> context.getString(R.string.node_test_real_short)
                "UDP" -> "UDP"; else -> context.getString(R.string.node_test_speed_short) }
            return "$label: " + when {
                result.value < 0 -> context.getString(R.string.unavailable)
                result.kind == "SPEED" -> String.format(Locale.ROOT, "%.2f MiB/s", result.value / 1048576.0)
                else -> "${result.value} ms"
            }
        }
    }

    fun select(kind: NodeTestKind, groupId: Long) {
        if (DataStore.runningTest) return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val profiles = withContext(Dispatchers.IO) { SagerDatabase.proxyDao.getByGroup(groupId) }
            if (profiles.isEmpty()) {
                MaterialAlertDialogBuilder(fragment.requireContext()).setMessage(R.string.node_empty)
                    .setPositiveButton(android.R.string.ok, null).show()
                return@launch
            }
            val selected = BooleanArray(profiles.size) { true }
            val dialog = MaterialAlertDialogBuilder(fragment.requireContext()).setTitle(title(kind))
                .setMultiChoiceItems(profiles.map { "${it.displayName()} · ${it.displayType()}" }.toTypedArray(), selected) { _, index, checked -> selected[index] = checked }
                .setNeutralButton(R.string.node_test_toggle_all, null)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.node_test_start, null).create()
            dialog.setOnShowListener {
                val start = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                start.setOnClickListener {
                    val targets = profiles.filterIndexed { index, _ -> selected[index] }
                    if (targets.isNotEmpty()) {
                        dialog.dismiss()
                        if (kind == NodeTestKind.SPEED) {
                            MaterialAlertDialogBuilder(fragment.requireContext()).setTitle(title(kind))
                                .setMessage(fragment.getString(R.string.node_test_speed_help, targets.size,
                                    DataStore.speedTestLimitMiB, targets.size.toLong() * DataStore.speedTestLimitMiB,
                                    DataStore.nodeTestTimeout))
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(R.string.node_test_start) { _, _ -> run(kind, groupId, targets) }.show()
                        } else run(kind, groupId, targets)
                    }
                }
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    val check = selected.any { !it }
                    selected.indices.forEach { index -> selected[index] = check; dialog.listView.setItemChecked(index, check) }
                }
            }
            dialog.show()
        }
    }

    private fun run(kind: NodeTestKind, groupId: Long, profiles: List<ProxyEntity>) {
        if (DataStore.runningTest) return
        DataStore.runningTest = true
        val context = fragment.requireContext()
        val progress = MaterialAlertDialogBuilder(context).setTitle(title(kind))
            .setMessage("0 / ${profiles.size}")
            .setCancelable(false).setNegativeButton(android.R.string.cancel, null).create()
        var job: Job? = null
        progress.setOnShowListener {
            progress.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                it.isEnabled = false
                job?.cancel()
            }
        }
        progress.show()
        job = fragment.viewLifecycleOwner.lifecycleScope.launch {
            val queue = ConcurrentLinkedQueue(profiles)
            var completed = 0
            val recent = ArrayDeque<String>()
            try {
                coroutineScope {
                    // Downloads compete for bandwidth. Measure speed sequentially, latency with bounded concurrency.
                    repeat(if (kind == NodeTestKind.SPEED) 1 else minOf(profiles.size, DataStore.connectionTestConcurrent.coerceIn(1, 32))) {
                        launch {
                            while (isActive) {
                                val profile = queue.poll() ?: break
                                val result = try {
                                    NodeTestSession(profile).measure(kind)
                                } catch (e: CancellationException) {
                                    ensureActive()
                                    NodeTestResult(profile.id, kind.name, -1, System.currentTimeMillis(), e.cause?.readableMessage ?: e.readableMessage)
                                } catch (e: Exception) {
                                    NodeTestResult(profile.id, kind.name, -1, System.currentTimeMillis(), e.readableMessage.take(512))
                                }
                                ensureActive()
                                withContext(Dispatchers.IO) {
                                    // A removed profile must stay removed even if its test was already in flight.
                                    SagerDatabase.instance.runInTransaction {
                                        if (SagerDatabase.proxyDao.getById(profile.id) != null) {
                                            SagerDatabase.nodeTests.put(result)
                                            if (kind == NodeTestKind.TCP || kind == NodeTestKind.URL)
                                                SagerDatabase.proxyDao.updateTestStatus(profile.id, if (result.value >= 0) 1 else 3,
                                                    result.value.coerceAtLeast(0).toInt(), result.error)
                                        }
                                    }
                                }
                                completed++
                                recent.addLast("${profile.displayName()}\n${resultText(context, result)}" + if (result.error.isBlank()) "" else "\n${result.error}")
                                if (recent.size > 4) recent.removeFirst()
                                progress.setMessage("$completed / ${profiles.size}\n\n" + recent.joinToString("\n\n"))
                            }
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    DataStore.runningTest = false
                    progress.dismiss()
                    withContext(Dispatchers.IO) { GroupManager.postReload(groupId) }
                }
            }
        }
    }
}
