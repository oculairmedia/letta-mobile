package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.SubagentEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Executable ownership matrix for lgns8.13: every admin_rpc method the Iroh
 * node registers must carry a reviewed owner, public contract, authorization
 * class, data store, fallback, and migration slice. The matrix is diffed
 * against the real [AdminRpcRegistry] router so adding or removing a method
 * without an ownership decision fails this test.
 */
class IrohAdminOwnershipMatrixTest {
    private val matrix = fixtureJson("iroh-admin-ownership-matrix.json")
    private val inventory = fixtureJson("installed-protocol-v2-inventory.json")
    private val operations = matrix["operations"]!!.jsonArray.map { it.jsonObject }
    private val enums = matrix["enums"]!!.jsonObject

    private object EmptySubagentSource : SubagentRegistrySource {
        override suspend fun list(conversationId: String, includeTerminal: Boolean): List<SubagentEntry> = emptyList()

        override suspend fun todos(conversationId: String, toolCallId: String): SubagentTodosSnapshot? = null
    }

    @Test
    fun matrixCoversExactlyTheRegisteredAdminRpcMethods() {
        val router = AdminRpcRegistry.buildRouter(
            adminBaseUrl = "http://127.0.0.1:0",
            controller = null,
            subagentRegistrySource = EmptySubagentSource,
            pairingService = IrohPairingService(InMemoryPairedPeerStore()),
        )
        val registered = router.registeredMethods
        val declared = operations.map { it.requiredString("method") }.toSet()

        assertEquals(operations.size, declared.size, "Duplicate method rows in the ownership matrix")
        assertEquals(
            emptySet(),
            registered - declared,
            "Registered admin_rpc methods missing an ownership decision — add rows to iroh-admin-ownership-matrix.json",
        )
        assertEquals(
            emptySet(),
            declared - registered,
            "Ownership matrix declares methods the router no longer registers — prune stale rows",
        )
    }

    @Test
    fun everyOperationDeclaresAFullReviewedDecision() {
        val owners = enums.stringSet("owners")
        val authorizationClasses = enums.stringSet("authorization_classes")
        val dataStores = enums.stringSet("data_stores")
        val fallbacks = enums.stringSet("fallbacks")
        val migrationSlices = enums.stringSet("migration_slices")

        operations.forEach { row ->
            val method = row.requiredString("method")
            assertTrue(row.requiredString("handler").isNotBlank(), "$method must name its handler")
            assertTrue(row.requiredString("current_backend").isNotBlank(), "$method must name its current backend")
            assertTrue(row.requiredString("public_contract").isNotBlank(), "$method must name its public contract")
            assertTrue(row.requiredString("owner") in owners, "$method has an unknown owner")
            assertTrue(
                row.requiredString("authorization_class") in authorizationClasses,
                "$method has an unknown authorization class",
            )
            assertTrue(row.requiredString("data_store") in dataStores, "$method has an unknown data store")
            assertTrue(row.requiredString("fallback") in fallbacks, "$method has an unknown fallback")
            assertTrue(
                row.requiredString("migration_slice") in migrationSlices,
                "$method has an unknown migration slice",
            )
        }
    }

    @Test
    fun appServerOwnedOperationsCiteOnlyPinnedNativeDiscriminants() {
        val commands = inventory.stringSet("commands")
        val messages = inventory.stringSet("messages")
        val known = commands + messages

        operations.filter { it.requiredString("owner") == "app_server_v2" }.forEach { row ->
            val method = row.requiredString("method")
            val cited = row.stringSet("native_contract")
            assertTrue(cited.isNotEmpty(), "$method claims app_server_v2 ownership without citing native discriminants")
            cited.forEach { discriminant ->
                assertTrue(
                    discriminant in known,
                    "$method cites '$discriminant', which is not in the pinned v2 inventory",
                )
            }
        }
        operations.filterNot { it.requiredString("owner") == "app_server_v2" }.forEach { row ->
            assertTrue(
                row.stringSet("native_contract").isEmpty(),
                "${row.requiredString("method")} cites native discriminants but is not app_server_v2-owned",
            )
        }
    }

    @Test
    fun noOperationUsesDirectLettaStorageAccess() {
        val dataStores = enums.stringSet("data_stores")
        assertTrue("letta_storage_direct" !in dataStores, "Direct Letta storage access is forbidden by the epic")
        operations.forEach { row ->
            val backend = row.requiredString("current_backend")
            assertTrue(
                !backend.contains("sqlite", ignoreCase = true) && !backend.contains("direct storage", ignoreCase = true),
                "${row.requiredString("method")} may not access Letta storage directly",
            )
        }
    }

    @Test
    fun unsupportedNativeOperationsAreExplicitAndMatchThePinnedInventory() {
        val gaps = matrix["unsupported_native_operations"]!!.jsonArray.map { it.jsonObject }
        val gapOperations = gaps.map { it.requiredString("operation") }.toSet()
        assertTrue("conversation_delete" in gapOperations, "conversation_delete gap must stay explicit")

        val absentByCapability = inventory["capabilities"]!!.jsonArray.map { it.jsonObject }
            .associate { it.requiredString("id") to it.stringSet("absent_operations") }
        val methodsByName = operations.associateBy { it.requiredString("method") }
        gaps.forEach { gap ->
            val operation = gap.requiredString("operation")
            val capability = gap.requiredString("capability")
            assertTrue(
                operation in absentByCapability.getValue(capability),
                "$operation is not recorded as absent under '$capability' in the pinned inventory",
            )
            gap.stringSet("affected_methods").forEach { method ->
                assertTrue(method in methodsByName, "$operation names unknown method $method")
            }
        }

        val conversationDelete = methodsByName.getValue("conversation.delete")
        assertEquals("capability_gated_unsupported", conversationDelete.requiredString("owner"))
        assertEquals("deny_fail_closed", conversationDelete.requiredString("fallback"))
    }

    @Test
    fun everyDomainNamedByTheEpicIsEitherRoutedOrExplicitlyDecided(): Unit {
        val unrouted = matrix["unrouted_domains"]!!.jsonArray.map { it.jsonObject }
        val domains = unrouted.map { it.requiredString("domain") }.toSet()
        setOf("crons", "search", "channels", "secrets", "filesystem_terminal", "mcp_catalog").forEach { required ->
            assertTrue(required in domains, "Epic-scoped domain '$required' needs an explicit unrouted decision")
        }
        unrouted.forEach { domain ->
            assertTrue(
                domain.requiredString("decision").isNotBlank(),
                "${domain.requiredString("domain")} must record a decision",
            )
        }

        val cronContracts = unrouted.first { it.requiredString("domain") == "crons" }.stringSet("native_contract")
        val commands = inventory.stringSet("commands")
        assertTrue(cronContracts.isNotEmpty())
        cronContracts.forEach { discriminant ->
            assertTrue(discriminant in commands, "crons cites '$discriminant', which is not a pinned v2 command")
        }
    }

    private fun fixtureJson(name: String): JsonObject {
        val stream = checkNotNull(javaClass.getResourceAsStream("/appserver/$name")) { "Missing fixture $name" }
        return Json.parseToJsonElement(stream.bufferedReader(Charsets.UTF_8).use { it.readText() }).jsonObject
    }

    private fun JsonObject.requiredString(key: String): String =
        checkNotNull(this[key]?.jsonPrimitive?.content) { "Missing '$key' in $this" }

    private fun JsonObject.stringSet(key: String): Set<String> =
        this[key]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
}
