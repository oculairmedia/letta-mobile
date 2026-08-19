package com.letta.mobile.desktop.runtime

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.TimeoutCancellationException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DesktopLocalAppServerClientRegistryTest {
    @Test
    fun `repository generation waits for each next client`() = runTest {
        val registry = DesktopLocalAppServerClientRegistry()
        val baseline = registry.generation()
        val first = FakeClient()
        val second = FakeClient()
        val waiting = async { registry.awaitClientAfter(baseline) }
        runCurrent()

        val firstLease = registry.install(first)
        assertSame(first, waiting.await())
        val firstGeneration = registry.generation()
        val waitingForSecond = async { registry.awaitClientAfter(firstGeneration) }
        runCurrent()
        val secondLease = registry.install(second)
        firstLease.close()

        assertSame(second, waitingForSecond.await())
        secondLease.close()
    }

    @Test
    fun `repository wait is bounded while a gateway is unavailable`() = runTest {
        val registry = DesktopLocalAppServerClientRegistry()
        val baseline = registry.generation()

        assertFailsWith<TimeoutCancellationException> {
            registry.awaitClientAfter(baseline, timeoutMs = 1)
        }
    }

    @Test
    fun `current client follows the latest installed generation`() = runTest {
        val registry = DesktopLocalAppServerClientRegistry()
        val first = FakeClient()
        val second = FakeClient()
        val firstLease = registry.install(first)

        assertSame(first, registry.currentClient())
        val secondLease = registry.install(second)
        firstLease.close()
        assertSame(second, registry.currentClient())

        secondLease.close()
    }

    private class FakeClient : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()
        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = unsupported()
        override suspend fun input(command: AppServerCommand.Input): Unit = unsupported()
        override suspend fun sync(command: AppServerCommand.Sync) = unsupported()
        override suspend fun abort(command: AppServerCommand.AbortMessage) = unsupported()
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = unsupported()
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse): Unit = unsupported()
        private fun unsupported(): Nothing = error("Unexpected App Server operation")
    }
}
