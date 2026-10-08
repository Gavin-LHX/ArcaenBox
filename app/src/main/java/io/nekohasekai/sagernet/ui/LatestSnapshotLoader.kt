package io.nekohasekai.sagernet.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Serializes requests and applies snapshots on the supplied UI scope. */
internal class LatestSnapshotLoader<T>(
    private val scope: CoroutineScope,
    private val load: suspend () -> T,
    private val apply: (T) -> Unit,
) {
    private var pending: Job? = null

    fun reload(): Job = scope.launch {
        pending?.cancel()
        pending = launch {
            val snapshot = load()
            // A blocking database read may finish after cancellation. Neither a
            // superseded read nor a destroyed view may publish that snapshot.
            ensureActive()
            apply(snapshot)
        }
    }
}
