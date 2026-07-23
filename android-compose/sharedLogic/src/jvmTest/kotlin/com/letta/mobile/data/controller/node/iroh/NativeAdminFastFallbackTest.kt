package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * lgns8.7 regression: the native-first admin path must fail FAST to the shim
 * proxy when the wrapped App Server doesn't answer the native command. Before
 * this, `attempt` had no bound of its own and rode the client's 120s request
 * timeout, so an unanswered native call blocked for 120s — long past the remote
 * admin_rpc client's 30s window — and every large read (agent.list,
 * conversation.list, agent.get) surfaced as "admin_rpc timed out" even though
 * the proxy fallback was milliseconds away.
 */
class NativeAdminFastFallbackTest {

    @BeforeTest
    fun clearBreaker() = NativeAdmin.resetCircuitForTest()

    @Test
    fun aHangingNativeAttemptFallsBackFastInsteadOfBlockingForTheFullRequestTimeout() = runTest {
        val result = NativeAdmin.attempt(FakeClient, "agent.list") {
            delay(120_000) // the App Server never answers — the 120s hang this fixes
            "unreachable"
        }

        assertNull(result, "an unanswered native attempt must fall back (null)")
        val advanced = testScheduler.currentTime
        assertTrue(
            advanced in 1..10_000,
            "must abandon the native attempt in a few seconds, not the full 120s; advanced=${advanced}ms",
        )
    }

    @Test
    fun aSuccessfulNativeAttemptReturnsItsResult() = runTest {
        assertEquals("ok", NativeAdmin.attempt(FakeClient, "agent.list") { "ok" })
    }

    @Test
    fun aNullClientSkipsNativeAndReturnsNull() = runTest {
        assertNull(NativeAdmin.attempt<String>(null, "agent.list") { "x" })
    }

    @Test
    fun afterANativeTimeoutTheBreakerSkipsNativeForSubsequentOpsInsteadOfProbingAgain() = runTest {
        // First op hangs → trips the breaker (this one still pays the ~2s probe).
        var firstProbed = false
        assertNull(
            NativeAdmin.attempt(FakeClient, "agent.list") { firstProbed = true; delay(120_000); "x" },
        )
        assertTrue(firstProbed, "the first op probes native")

        // Subsequent ops must NOT probe native — straight to the proxy (null),
        // so a page-heavy read doesn't multiply the probe into seconds of wait.
        var secondProbed = false
        assertNull(
            NativeAdmin.attempt(FakeClient, "conversation.list") { secondProbed = true; "y" },
            "breaker open → skip native → proxy fallback",
        )
        assertFalse(secondProbed, "native must be skipped while the breaker is open")
    }

    private object FakeClient : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()
        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = error("unused")
        override suspend fun input(command: AppServerCommand.Input) = error("unused")
        override suspend fun sync(command: AppServerCommand.Sync) = error("unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage) = error("unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = error("unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = error("unused")
    }
}
