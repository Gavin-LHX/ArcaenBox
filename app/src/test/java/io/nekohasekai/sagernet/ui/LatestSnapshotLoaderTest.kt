package io.nekohasekai.sagernet.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestSnapshotLoaderTest {
    private data class Profile(val id: Long, val name: String, val port: Int)

    @Test fun firstImportCannotBeHiddenByAnEarlierEmptyGroupSnapshot() = runBlocking {
        withTimeout(5_000) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val emptyReadStarted = CompletableDeferred<Unit>()
            val finishEmptyRead = CompletableDeferred<Unit>()
            val displayed = mutableListOf<List<Long>>()
            var groupHasProfiles = false
            val loader = LatestSnapshotLoader(scope, load = {
                val groups = if (groupHasProfiles) listOf(1L) else emptyList()
                if (groups.isEmpty()) {
                    emptyReadStarted.complete(Unit)
                    withContext(NonCancellable) { finishEmptyRead.await() }
                }
                groups
            }, apply = { displayed.add(it) })
            try {
                val emptyRequest = loader.reload()
                emptyReadStarted.await()
                groupHasProfiles = true
                loader.reload().join()
                assertEquals(listOf(1L), displayed.single())
                finishEmptyRead.complete(Unit)
                emptyRequest.join()
                assertEquals(listOf(listOf(1L)), displayed)
            } finally {
                finishEmptyRead.complete(Unit)
                scope.cancel()
            }
        }
    }

    @Test fun lateSnapshotCannotHideAnImportedProfileWithTheSameName() = runBlocking {
        withTimeout(5_000) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val oldReadStarted = CompletableDeferred<Unit>()
            val finishOldRead = CompletableDeferred<Unit>()
            val stored = mutableListOf(Profile(1, "same name", 18001))
            val displayed = mutableListOf<List<Profile>>()
            var reads = 0
            val loader = LatestSnapshotLoader(scope, load = {
                val snapshot = stored.toList()
                if (++reads == 1) {
                    oldReadStarted.complete(Unit)
                    // Model a database read that has already captured its rows
                    // and cannot be interrupted before returning.
                    withContext(NonCancellable) { finishOldRead.await() }
                }
                snapshot
            }, apply = { displayed.add(it) })
            try {
                val oldRequest = loader.reload()
                oldReadStarted.await()
                stored.add(Profile(2, "same name", 18002))
                loader.reload().join()
                assertEquals(listOf(1L, 2L), displayed.single().map { it.id })
                finishOldRead.complete(Unit)
                oldRequest.join()
                assertEquals(1, displayed.size)
                assertEquals(listOf(18001, 18002), displayed.single().map { it.port })
            } finally {
                finishOldRead.complete(Unit)
                scope.cancel()
            }
        }
    }

    @Test fun lateSnapshotCannotUndoAnEditOrResurrectADeletedProfile() = runBlocking {
        withTimeout(5_000) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val oldReadStarted = CompletableDeferred<Unit>()
            val finishOldRead = CompletableDeferred<Unit>()
            var stored = listOf(Profile(1, "before edit", 18001), Profile(2, "deleted", 18002),
                Profile(3, "keep", 18003))
            val displayed = mutableListOf<List<Profile>>()
            var reads = 0
            val loader = LatestSnapshotLoader(scope, load = {
                val snapshot = stored
                if (++reads == 1) {
                    oldReadStarted.complete(Unit)
                    withContext(NonCancellable) { finishOldRead.await() }
                }
                snapshot
            }, apply = { displayed.add(it) })
            try {
                val oldRequest = loader.reload()
                oldReadStarted.await()
                stored = listOf(Profile(1, "after edit", 18001), Profile(3, "keep", 18003))
                loader.reload().join()
                finishOldRead.complete(Unit)
                oldRequest.join()
                assertEquals(listOf(stored), displayed)
                assertEquals(listOf(1L, 3L), displayed.single().map { it.id })
            } finally {
                finishOldRead.complete(Unit)
                scope.cancel()
            }
        }
    }

    @Test fun destroyedViewCannotReceiveAnInFlightOrLaterSnapshot() = runBlocking {
        withTimeout(5_000) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val readStarted = CompletableDeferred<Unit>()
            val finishRead = CompletableDeferred<Unit>()
            val displayed = mutableListOf<List<Long>>()
            var reads = 0
            val loader = LatestSnapshotLoader(scope, load = {
                reads++
                readStarted.complete(Unit)
                withContext(NonCancellable) { finishRead.await() }
                listOf(1L)
            }, apply = { displayed.add(it) })
            try {
                val inFlight = loader.reload()
                readStarted.await()
                scope.cancel()
                finishRead.complete(Unit)
                inFlight.join()
                loader.reload().join()
                assertTrue(displayed.isEmpty())
                assertEquals(1, reads)
            } finally {
                finishRead.complete(Unit)
                scope.cancel()
            }
        }
    }
}
