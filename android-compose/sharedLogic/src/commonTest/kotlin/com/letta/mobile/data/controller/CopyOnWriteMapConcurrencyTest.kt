package com.letta.mobile.data.controller

import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * letta-mobile-by4vm.8: the controller's permission-mode maps are read lock-free by the turn
 * engine's provider from parallel per-key leases while mode changes and runtime churn write them.
 * Every read a starting turn makes must see the old or the new mode, never a missing entry (which
 * would silently fall through to the default).
 */
class CopyOnWriteMapConcurrencyTest {
    private val runtimeModes = CopyOnWriteMap<String, AppServerPermissionMode>()
    private val pinnedModes = CopyOnWriteMap<String, AppServerPermissionMode>()

    private fun providerFor(key: String): AppServerPermissionMode =
        runtimeModes[key] ?: pinnedModes[key] ?: AppServerPermissionMode.Strict

    @Test
    fun parallelLeasesObserveOldOrNewModeWhileModesChange() = runTest {
        withContext(Dispatchers.Default) {
            repeat(REPEATS) { round -> assertRoundSettles(round) }
        }
    }

    private suspend fun assertRoundSettles(round: Int) = coroutineScope {
        val key = "agent:conv-$round"
        runtimeModes[key] = AppServerPermissionMode.Standard
        val churn = launch { churnOtherRuntimes(round) }
        val flips = launch { flipMode(key) }
        val observed = (1..LEASES).map {
            async { List(READS_PER_LEASE) { providerFor(key) } }
        }.awaitAll().flatten()
        flips.join()
        churn.join()
        val allowed = setOf(AppServerPermissionMode.Standard, AppServerPermissionMode.AcceptEdits)
        assertTrue(observed.all { it in allowed }, "round $round saw ${observed.toSet() - allowed}")
        assertEquals(AppServerPermissionMode.AcceptEdits, providerFor(key))
    }

    private fun flipMode(key: String) {
        repeat(FLIPS) { i ->
            val mode = if (i % 2 == 0) AppServerPermissionMode.AcceptEdits else AppServerPermissionMode.Standard
            runtimeModes[key] = mode
        }
        runtimeModes[key] = AppServerPermissionMode.AcceptEdits
    }

    private fun churnOtherRuntimes(round: Int) {
        val others = (0 until CHURN_KEYS).map { "other-$round-$it" }
        others.forEach { runtimeModes[it] = AppServerPermissionMode.Strict }
        others.forEach { runtimeModes.remove(it) }
    }

    @Test
    fun clearAndRemoveDropEntries() {
        runtimeModes["a"] = AppServerPermissionMode.Standard
        runtimeModes["b"] = AppServerPermissionMode.AcceptEdits
        runtimeModes.remove("a")
        assertTrue("a" !in runtimeModes)
        assertTrue("b" in runtimeModes)
        runtimeModes.clear()
        assertTrue("b" !in runtimeModes)
    }

    private companion object {
        const val REPEATS = 20
        const val LEASES = 16
        const val READS_PER_LEASE = 200
        const val FLIPS = 500
        const val CHURN_KEYS = 300
    }
}
