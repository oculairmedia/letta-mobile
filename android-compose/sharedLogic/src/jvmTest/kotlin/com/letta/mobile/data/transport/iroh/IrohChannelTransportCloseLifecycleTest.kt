package com.letta.mobile.data.transport.iroh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-by4vm.10: [IrohChannelTransport] owns exactly one coroutine job. close() cancels
 * it; a caller-supplied scope only bounds it (its completion cancels the transport) and is never
 * cancelled by it. disconnect() alone keeps the instance reusable for a later connect().
 */
class IrohChannelTransportCloseLifecycleTest {

    @Test
    fun closeCancelsOwnedJobWhenNoScopeIsSupplied() = runTest {
        val transport = IrohChannelTransport()
        assertTrue(transport.transportJob.isActive)

        transport.close()

        assertFalse(transport.transportJob.isActive)
    }

    @Test
    fun closeCancelsOwnJobButLeavesSuppliedScopeActive() = runTest {
        val transport = IrohChannelTransport(scope = this)

        transport.close()

        assertFalse(transport.transportJob.isActive)
        assertTrue(isActive, "close must not cancel the caller's scope")
    }

    @Test
    fun suppliedScopeCancellationCancelsTransportJob() = runTest {
        val callerScope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val transport = IrohChannelTransport(scope = callerScope)
        assertTrue(transport.transportJob.isActive)

        callerScope.cancel()

        assertFalse(transport.transportJob.isActive, "the caller's lifetime still bounds the transport")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun idleTransportDoesNotHoldTheCallersStructuredJoinOpen() = runTest {
        // runTest waits for every active child of the TestScope's job; a live transport job
        // among them would end the test in UncompletedCoroutinesError.
        val transport = IrohChannelTransport(scope = this)
        val callerJob = coroutineContext[Job]!!
        advanceUntilIdle()

        assertTrue(transport.transportJob.isActive, "the idle transport stays alive")
        assertFalse(transport.transportJob in callerJob.children.toList(), "transport job must not be a structured child")
        assertTrue(callerJob.children.none { it.isActive }, "nothing the transport started holds the caller's join open")
    }

    @Test
    fun disconnectAloneKeepsTheTransportReusable() = runTest {
        val transport = IrohChannelTransport(scope = this)

        transport.disconnect()

        assertTrue(transport.transportJob.isActive, "disconnect must leave the instance able to reconnect")
    }
}
