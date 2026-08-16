package com.letta.mobile.desktop.runtime

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlin.test.Test
import kotlin.test.assertSame
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DesktopLocalAppServerClientRegistryTest {
    @Test
    fun `repository generation waits for next client and pins it`() = runTest {
        val baseline = DesktopLocalAppServerClientRegistry.generation()
        val first = FakeClient()
        val second = FakeClient()
        val binding = DesktopLocalAppServerClientBinding {
            DesktopLocalAppServerClientRegistry.awaitClientAfter(baseline)
        }
        val waiting = async { binding.client() }
        runCurrent()

        val firstLease = DesktopLocalAppServerClientRegistry.install(first)
        assertSame(first, waiting.await())
        val secondLease = DesktopLocalAppServerClientRegistry.install(second)
        firstLease.close()

        assertSame(first, binding.client())
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
