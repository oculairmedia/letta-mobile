package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
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

    private val client = FakeClient()

    @Test
    fun aHangingNativeAttemptFallsBackFastInsteadOfBlockingForTheFullRequestTimeout() = runTest {
        val result = NativeAdmin.attempt(client, "agent.list") {
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
        assertEquals("ok", NativeAdmin.attempt(client, "agent.list") { "ok" })
    }

    @Test
    fun aNullClientSkipsNativeAndReturnsNull() = runTest {
        assertNull(NativeAdmin.attempt<String>(null, "agent.list") { "x" })
    }

    @Test
    fun afterANativeTimeoutTheBreakerSkipsOnlyThatCommand() = runTest {
        // First op hangs → trips the breaker for agent.list only.
        var firstProbed = false
        assertNull(
            NativeAdmin.attempt(client, "agent.list") { firstProbed = true; delay(120_000); "x" },
        )
        assertTrue(firstProbed, "the first op probes native")

        // Same command must NOT probe while its breaker is open.
        var sameOpProbed = false
        assertNull(
            NativeAdmin.attempt(client, "agent.list") { sameOpProbed = true; "y" },
            "breaker open for agent.list → skip native",
        )
        assertFalse(sameOpProbed, "native must be skipped for the tripped command")

        // Unrelated command still probes — Phase 2 per-command circuit.
        var otherProbed = false
        assertEquals(
            "ok",
            NativeAdmin.attempt(client, "conversation.list") { otherProbed = true; "ok" },
        )
        assertTrue(otherProbed, "unrelated commands must not inherit another op's breaker")
    }

    @Test
    fun independentClientsDoNotShareLegacyBreakers() = runTest {
        val failed = EqualClient()
        val healthy = EqualClient()
        assertNull(NativeAdmin.attempt<String>(failed, "agent.list") { error("offline") })
        assertEquals("healthy", NativeAdmin.attempt(healthy, "agent.list") { "healthy" })
        var probed = false
        assertNull(NativeAdmin.attempt(failed, "agent.list") { probed = true; "unexpected" })
        assertFalse(probed, "healthy client success must not clear the failed client's breaker")
        assertEquals("other", NativeAdmin.attempt(failed, "agent.get") { "other" })
    }

    @Test
    fun independentClientsDoNotShareRequiredBreakers() = runTest {
        val failed = EqualClient()
        val healthy = EqualClient()
        val op = NativeAdminOp.byMethod("agent.list")!!
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            NativeAdmin.require<String>(failed, op) { error("offline") }
        }
        assertEquals("healthy", NativeAdmin.require(healthy, op) { "healthy" })
        var probed = false
        val failure = kotlin.test.assertFailsWith<IllegalArgumentException> {
            NativeAdmin.require(failed, op) { probed = true; "unexpected" }
        }
        assertFalse(probed)
        assertTrue(failure.message.orEmpty().contains("temporarily unavailable"))
        assertEquals("other", NativeAdmin.require(failed, NativeAdminOp.byMethod("agent.get")!!) { "other" })
    }

    private class EqualClient : AppServerClient by FakeClient() {
        override fun equals(other: Any?): Boolean = other is EqualClient
        override fun hashCode(): Int = 1
    }

    @Test
    fun conversationRestoreIsClassifiedAsMutation() {
        assertEquals(
            NativeAdminOperationPolicy.MutationAmbiguous,
            NativeAdmin.policyForMethod("conversation.restore"),
        )
        assertEquals(
            NativeAdminOperationPolicy.MutationAmbiguous,
            NativeAdmin.policyForMethod("conversation.archive"),
        )
        assertEquals(
            NativeAdminOperationPolicy.ReadScan,
            NativeAdmin.policyForMethod("conversation.list"),
        )
        assertEquals(
            NativeAdminOperationPolicy.ReadScan,
            NativeAdmin.policyForMethod("agent.list"),
        )
        assertEquals(
            NativeAdminOperationPolicy.ReadScan,
            NativeAdmin.policyForMethod("message.list"),
        )
        assertEquals(
            NativeAdminOperationPolicy.Read,
            NativeAdmin.policyForMethod("message.get"),
        )
        assertNull(NativeAdmin.policyForMethod("not.a.registered.op"))
    }

    private class FakeClient : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()
        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = error("unused")
        override suspend fun input(command: AppServerCommand.Input) = error("unused")
        override suspend fun sync(command: AppServerCommand.Sync) = error("unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage) = error("unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = error("unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = error("unused")
    }
}
