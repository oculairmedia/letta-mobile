package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.node.iroh.IrohAdminOwnershipMatrix.requiredString
import com.letta.mobile.data.model.SubagentEntry
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * lgns8.9: THE ADMIN REST SERVICE IS RETIRED.
 *
 * Phase 3 left 36 methods owned by a bounded `admin_rest_service` adapter that
 * was never injected in production, so the whole domain sat at
 * capability-unavailable. This slice replaced every one of them with an explicit
 * owner — an App Server v2 command, the read-only local-backend store, a
 * controller-native catalog, or a documented fail-closed denial — so the
 * ownership matrix now declares ZERO admin_rest_service rows and no handler
 * constructs an [AdminProxyClient] for a Letta `/v1` admin path at all.
 *
 * This test is the fail-on-revert guard for that: reintroducing a generic admin
 * REST base (or an `admin_rest_service` matrix row) fails here.
 */
class AdminRestServiceInjectionTest {
    private var savedFactory: (() -> AdminProxyTransport)? = null
    private val dialed = mutableListOf<String>()

    @BeforeTest
    fun recordDials() {
        savedFactory = AdminProxyClient.defaultTransportFactory
        AdminProxyClient.defaultTransportFactory = {
            AdminProxyTransport { _, url, _ -> dialed += url; error("no dial expected") }
        }
    }

    @AfterTest
    fun restore() {
        savedFactory?.let { AdminProxyClient.defaultTransportFactory = it }
        dialed.clear()
    }

    private object EmptySubagentSource : SubagentRegistrySource {
        override suspend fun list(conversationId: String, includeTerminal: Boolean): List<SubagentEntry> = emptyList()

        override suspend fun todos(conversationId: String, toolCallId: String): SubagentTodosSnapshot? = null
    }

    /** Every method the retired admin REST adapter used to own, by handler. */
    private val formerAdminRestMethods: Set<String> =
        RunAdminHandlers.METHODS + ArchiveAdminHandlers.METHODS + IdentityAdminHandlers.METHODS +
            ModelAdminHandlers.CONSTANT_CATALOG_METHODS + ScheduleAdminHandlers.METHODS +
            ToolAdminHandlers.METHODS + McpAdminHandlers.METHODS + setOf("agent.context")

    @Test
    fun theRetiredAdminRestSurfaceIsStillFullyEnumerated() {
        assertEquals(36, formerAdminRestMethods.size, "expected the 36 former admin_rest methods")
    }

    @Test
    fun theOwnershipMatrixDeclaresNoAdminRestServiceRows() {
        val remaining = IrohAdminOwnershipMatrix.operations
            .filter {
                it.requiredString("owner") == "admin_rest_service" ||
                    it.requiredString("post_shim_owner") == "admin_rest_service"
            }
            .map { it.requiredString("method") }
        assertEquals(
            emptyList(),
            remaining,
            "lgns8.9 retired admin_rest_service: every row needs a real owner or a fail-closed denial",
        )
    }

    /** The successor owners are exactly the four the slice allows. */
    @Test
    fun everyFormerAdminRestMethodCarriesOneOfTheFourApprovedOwners() {
        val approved = setOf(
            "app_server_v2",
            IrohAdminOwnershipMatrix.LOCAL_BACKEND_STORE_OWNER,
            "controller_native",
            "capability_gated_unsupported",
        )
        val byMethod = IrohAdminOwnershipMatrix.operations.associateBy { it.requiredString("method") }
        formerAdminRestMethods.forEach { method ->
            val owner = byMethod.getValue(method).requiredString("post_shim_owner")
            assertTrue(owner in approved, "$method has unapproved post-shim owner '$owner'")
        }
    }

    /**
     * With NOTHING injected — no native client, no store, no VibeSync — the
     * whole former admin REST surface must resolve without a single HTTP dial:
     * controller-native rows succeed from constants, everything else denies.
     */
    @Test
    fun withNothingInjectedTheFormerAdminRestSurfaceNeverDialsAnHttpHost() = runTest {
        val router = AdminRpcRegistry.buildRouter(
            adminBaseUrl = "http://127.0.0.1:8291",
            controller = null,
            subagentRegistrySource = EmptySubagentSource,
            pairingService = IrohPairingService(InMemoryPairedPeerStore()),
            nativeClient = null,
            vibesyncBaseUrl = null,
            localBackendDir = null,
        )
        val nativeConstants = IrohAdminOwnershipMatrix.operations
            .filter { it.requiredString("post_shim_owner") == "controller_native" }
            .map { it.requiredString("method") }
            .toSet()

        formerAdminRestMethods.forEach { method ->
            val response = router.dispatch(
                AdminRpcInvocation(
                    requestId = "t",
                    method = method,
                    params = buildJsonObject {
                        put("run_id", "r")
                        put("agent_id", "a")
                        put("id", "i")
                        put("name", "n")
                        put("tool_id", "tool-unknown")
                        put("block_id", "block-unknown")
                        put("schedule_id", "s")
                    },
                    context = AdminRpcRequestContext.Authenticated,
                ),
            )
            if (method in nativeConstants && method !in TOOL_LOOKUP_BY_ID) {
                assertTrue(response.contains("\"success\":true"), "$method is controller-native: $response")
            } else {
                assertTrue(response.contains("\"success\":false"), "$method should fail closed: $response")
            }
        }
        assertTrue(dialed.isEmpty(), "no admin REST surface may remain: $dialed")
    }

    private companion object {
        /** Controller-native, but an unknown id legitimately fails rather than inventing a row. */
        val TOOL_LOOKUP_BY_ID = setOf("tool.get")
    }
}
