package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

/**
 * letta-mobile-lgns8.21.5 — explicit [NativeAdminOperationPolicy] contract.
 */
class NativeAdminOperationPolicyContractTest {

    @BeforeTest
    fun clearBreaker() = NativeAdmin.resetCircuitForTest()

    @AfterTest
    fun clearBreakerAfter() = NativeAdmin.resetCircuitForTest()

    @Test
    fun everyNativeAdminOpHasUniqueMethodAndExplicitPolicy() {
        val methods = NativeAdminOp.entries.map { it.method.lowercase() }
        assertEquals(methods.size, methods.toSet().size, "duplicate NativeAdminOp.method")
        NativeAdminOp.entries.forEach { op ->
            assertEquals(op.policy, NativeAdmin.policyForMethod(op.method))
        }
    }

    @Test
    fun mutatingMethodNamesMustUseMutationAmbiguousPolicy() {
        val mutationMarkers = listOf(
            ".create", ".update", ".delete", ".delete_all", ".archive", ".restore",
            ".install", ".uninstall",
        )
        NativeAdminOp.entries.forEach { op ->
            val looksMutating = mutationMarkers.any { op.method.lowercase().endsWith(it.removePrefix(".")) ||
                op.method.lowercase().contains(it) }
            // Prefer suffix match on final segment.
            val suffixMutating = mutationMarkers.any { op.method.lowercase().endsWith(it) }
            if (suffixMutating || op.method.equals("conversation.restore", ignoreCase = true)) {
                assertEquals(
                    NativeAdminOperationPolicy.MutationAmbiguous,
                    op.policy,
                    "${op.method} must be MutationAmbiguous",
                )
            }
        }
        assertEquals(
            NativeAdminOperationPolicy.MutationAmbiguous,
            NativeAdminOp.ConversationRestore.policy,
        )
    }

    @Test
    fun sourceRequireCallSitesOnlyUseNativeAdminOpCatalog() {
        val root = locateIrohHandlersDir()
        val requireCall = Regex("""NativeAdmin\.require\(\s*[^,]+,\s*([^,\)]+)""")
        val stringLiteralOp = Regex("""NativeAdmin\.require\(\s*[^,]+,\s*"[^"]+"""")
        val files = root.toFile().walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty(), "expected handler sources under $root")
        files.forEach { file ->
            val text = file.readText()
            assertFalse(
                stringLiteralOp.containsMatchIn(text),
                "${file.name} still passes a string op to NativeAdmin.require",
            )
            requireCall.findAll(text).forEach { match ->
                val secondArg = match.groupValues[1].trim()
                assertTrue(
                    secondArg.startsWith("NativeAdminOp.") || secondArg == "op",
                    "${file.name}: require second arg must be NativeAdminOp.* (got $secondArg)",
                )
            }
        }
    }

    @Test
    fun conversationRestoreTimeoutDoesNotTripReadBreaker() = runTest {
        assertFailsWith<IllegalArgumentException> {
            NativeAdmin.require(FakeClient, NativeAdminOp.ConversationRestore) {
                delay(35_000)
                "late"
            }
        }
        // Mutation timeout must not open the breaker — a follow-up read/write still probes.
        var probed = false
        val result = NativeAdmin.require(FakeClient, NativeAdminOp.ConversationGet) {
            probed = true
            "ok"
        }
        assertTrue(probed)
        assertEquals("ok", result)
    }

    @Test
    fun readTimeoutTripsBreakerButMutationTimeoutDoesNotAutoRetry() = runTest {
        assertFailsWith<IllegalArgumentException> {
            NativeAdmin.require(FakeClient, NativeAdminOp.AgentList) {
                delay(5_000)
                "x"
            }
        }
        // Read breaker open — next same-op require fails closed without probing.
        var readProbed = false
        assertFailsWith<IllegalArgumentException> {
            NativeAdmin.require(FakeClient, NativeAdminOp.AgentList) {
                readProbed = true
                "y"
            }
        }
        assertFalse(readProbed, "read timeout must trip breaker; no automatic retry probe")

        NativeAdmin.resetCircuitForTest()
        var mutationAttempts = 0
        assertFailsWith<IllegalArgumentException> {
            NativeAdmin.require(FakeClient, NativeAdminOp.ConversationRestore) {
                mutationAttempts++
                delay(35_000)
                "late-success"
            }
        }
        assertEquals(1, mutationAttempts, "require must not automatically re-issue a timed-out mutation")
    }

    private fun locateIrohHandlersDir(): java.nio.file.Path {
        var dir = java.nio.file.Paths.get("").toAbsolutePath()
        repeat(8) {
            val candidate = dir.resolve(
                "src/jvmAndAndroid/kotlin/com/letta/mobile/data/controller/node/iroh",
            )
            if (Files.isDirectory(candidate)) return candidate
            val fromModule = dir.resolve(
                "android-compose/sharedLogic/src/jvmAndAndroid/kotlin/com/letta/mobile/data/controller/node/iroh",
            )
            if (Files.isDirectory(fromModule)) return fromModule
            dir = dir.parent ?: return@repeat
        }
        error("could not locate iroh handler sources")
    }

    private object FakeClient : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()
        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = error("unused")
        override suspend fun input(command: AppServerCommand.Input) = error("unused")
        override suspend fun sync(command: AppServerCommand.Sync) = error("unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage) = error("unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = error("unused")
        override suspend fun sendExternalToolResponse(
            command: AppServerCommand.ExternalToolCallResponse,
        ) = error("unused")
    }
}
