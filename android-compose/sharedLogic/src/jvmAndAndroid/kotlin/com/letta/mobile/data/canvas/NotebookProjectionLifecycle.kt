package com.letta.mobile.data.canvas

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Poll this store's projections every [intervalMillis] only while [active] is true, until the
 * caller is cancelled. Android passes whether the app is in the foreground, so a backgrounded app
 * stops reading notebooks every second; the first poll after it returns is a full one, so edits
 * made meanwhile on either side are still synced. Starting and stopping never block, so [active]
 * may be collected on the main thread.
 */
suspend fun NotebookLocalStore.pollProjectionsWhile(active: Flow<Boolean>, intervalMillis: Long) {
    try {
        active.distinctUntilChanged().collect { polling -> if (polling) startPolling(intervalMillis) else stopPolling() }
    } finally {
        stopPolling()
    }
}
