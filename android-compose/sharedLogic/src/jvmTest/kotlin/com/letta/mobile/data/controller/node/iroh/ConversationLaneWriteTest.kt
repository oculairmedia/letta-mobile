package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationLaneWriteTest {
    private class Client : AppServerClient by FakeNativeClient() {
        val rows = mutableListOf<JsonObject>()
        override suspend fun conversationList(command: AppServerCommand.ConversationList) =
            AppServerInboundFrame.ConversationListResponse(command.requestId, true, conversations = JsonArray(rows))
        override suspend fun conversationRetrieve(command: AppServerCommand.ConversationRetrieve) =
            AppServerInboundFrame.ConversationRetrieveResponse(
                command.requestId, true,
                conversation = rows.first { it["id"]?.jsonPrimitive?.content == command.conversationId },
            )
        override suspend fun conversationCreate(
            command: AppServerCommand.ConversationCreate,
        ): AppServerInboundFrame.ConversationCreateResponse {
            val row = JsonObject(command.body + ("id" to JsonPrimitive("c${rows.size}")))
            rows += row
            return AppServerInboundFrame.ConversationCreateResponse(command.requestId, true, conversation = row)
        }
        override suspend fun conversationUpdate(
            command: AppServerCommand.ConversationUpdate,
        ): AppServerInboundFrame.ConversationUpdateResponse {
            val index = rows.indexOfFirst { it["id"]?.jsonPrimitive?.content == command.conversationId }
            rows[index] = JsonObject(rows[index] + command.body)
            return AppServerInboundFrame.ConversationUpdateResponse(command.requestId, true, conversation = rows[index])
        }
    }

    @Test
    fun taggedDefaultConversationBlocksAnotherHome() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("lane-default").toFile()
        try {
            val key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("default:a".toByteArray())
            val dir = java.io.File(root, "conversations/$key").apply { mkdirs() }
            java.io.File(dir, "conversation.json").writeText("""{"id":"default","agent_id":"a","tags":["lane:home"]}""")
            val client = Client()
            val router = AdminRpcRouter().also {
                ConversationAdminHandlers.register(
                    it, NativeReadTiers(nativeClient = client, localBackendStore = LocalBackendAdminStore(root)),
                )
            }
            val body = buildJsonObject { put("agent_id", "a"); put("tags", JsonArray(listOf(JsonPrimitive("lane:home")))) }
            val response = Json.parseToJsonElement(router.dispatch(
                AdminRpcInvocation("r", "conversation.create", body, AdminRpcRequestContext.Authenticated),
            )).jsonObject
            assertFalse(response.getValue("success").jsonPrimitive.boolean)
            assertEquals("lane_already_assigned", response.getValue("error").jsonPrimitive.content)
            assertTrue(client.rows.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun unreadableConversationDirectoryFailsClosed() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("lane-unreadable").toFile()
        try {
            java.io.File(root, "conversations").writeText("not a directory")
            val client = Client()
            val router = AdminRpcRouter().also {
                ConversationAdminHandlers.register(
                    it, NativeReadTiers(nativeClient = client, localBackendStore = LocalBackendAdminStore(root)),
                )
            }
            val body = buildJsonObject { put("agent_id", "a"); put("tags", JsonArray(listOf(JsonPrimitive("lane:inbox")))) }
            val result = Json.parseToJsonElement(router.dispatch(
                AdminRpcInvocation("r", "conversation.create", body, AdminRpcRequestContext.Authenticated),
            )).jsonObject
            assertFalse(result.getValue("success").jsonPrimitive.boolean)
            assertTrue(result.getValue("error").jsonPrimitive.content.contains("lane_check_unavailable"))
            assertTrue(client.rows.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun laneWriteWithoutLocalStoreFailsClosed() = runBlocking {
        val client = Client()
        val router = AdminRpcRouter().also { ConversationAdminHandlers.register(it, NativeReadTiers(nativeClient = client)) }
        val body = buildJsonObject { put("agent_id", "a"); put("tags", JsonArray(listOf(JsonPrimitive("lane:home")))) }
        val result = Json.parseToJsonElement(router.dispatch(
            AdminRpcInvocation("r", "conversation.create", body, AdminRpcRequestContext.Authenticated),
        )).jsonObject
        assertFalse(result.getValue("success").jsonPrimitive.boolean)
        assertTrue(result.getValue("error").jsonPrimitive.content.contains("lane_check_unavailable"))
        assertTrue(client.rows.isEmpty())
    }

    @Test
    fun replacingTagsReleasesLaneAndKeepsOnlySubmittedTags() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("lane-replace").toFile()
        try {
            val client = Client()
            val router = AdminRpcRouter().also {
                ConversationAdminHandlers.register(
                    it, NativeReadTiers(nativeClient = client, localBackendStore = LocalBackendAdminStore(root)),
                )
            }
            suspend fun invoke(method: String, body: JsonObject): Boolean {
                val response = Json.parseToJsonElement(
                    router.dispatch(AdminRpcInvocation("r", method, body, AdminRpcRequestContext.Authenticated)),
                ).jsonObject
                val success = response.getValue("success").jsonPrimitive.boolean
                assertTrue(success, response.toString())
                return success
            }
            val create = buildJsonObject { put("agent_id", "a"); put("tags", JsonArray(listOf(JsonPrimitive("lane:inbox")))) }
            assertTrue(invoke("conversation.create", create))
            val id = client.rows.single().getValue("id").jsonPrimitive.content
            val replacement = JsonArray(listOf(JsonPrimitive("project:p")))
            assertTrue(invoke("conversation.update", buildJsonObject { put("conversation_id", id); put("tags", replacement) }))
            assertEquals(replacement, client.rows.single()["tags"])
            assertTrue(invoke("conversation.create", create))
            assertEquals(2, client.rows.size)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun concurrentClaimsHaveOneWinnerAndAnotherAgentMayClaim() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("lane-race").toFile()
        try {
            val client = Client()
            val router = AdminRpcRouter().also {
                ConversationAdminHandlers.register(
                    it, NativeReadTiers(nativeClient = client, localBackendStore = LocalBackendAdminStore(root)),
                )
            }
            suspend fun create(agent: String): Boolean {
                val body = buildJsonObject { put("agent_id", agent); put("tags", JsonArray(listOf(JsonPrimitive("lane:inbox")))) }
                val result = Json.parseToJsonElement(router.dispatch(
                    AdminRpcInvocation("r-$agent", "conversation.create", body, AdminRpcRequestContext.Authenticated),
                )).jsonObject
                val success = result.getValue("success").jsonPrimitive.boolean
                if (!success) assertEquals("lane_already_assigned", result.getValue("error").jsonPrimitive.content)
                return success
            }
            val results = listOf(async { create("a") }, async { create("a") }).awaitAll()
            assertEquals(1, results.count { it })
            assertTrue(create("b"))
            assertEquals(2, client.rows.size)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun createAndUpdateRejectDuplicateInboxAndHome() = runBlocking {
        val client = Client()
        val root = kotlin.io.path.createTempDirectory("lane-write").toFile()
        val router = AdminRpcRouter().also {
            ConversationAdminHandlers.register(
                it, NativeReadTiers(nativeClient = client, localBackendStore = LocalBackendAdminStore(root)),
            )
        }
        suspend fun invoke(method: String, body: JsonObject): Boolean {
            val result = Json.parseToJsonElement(
                router.dispatch(AdminRpcInvocation("r", method, body, AdminRpcRequestContext.Authenticated)),
            ).jsonObject
            val success = result.getValue("success").jsonPrimitive.boolean
            if (!success) assertEquals("lane_already_assigned", result.getValue("error").jsonPrimitive.content)
            return success
        }
        for (lane in listOf("inbox", "home")) {
            val first = buildJsonObject { put("agent_id", "a"); put("tags", JsonArray(listOf(JsonPrimitive("lane:$lane")))) }
            assertTrue(invoke("conversation.create", first))
            val firstId = client.rows.last().getValue("id").jsonPrimitive.content
            assertTrue(invoke("conversation.update", buildJsonObject {
                put("conversation_id", firstId)
                put("tags", JsonArray(listOf(JsonPrimitive("lane:$lane"), JsonPrimitive("project:p"))))
            }))
            assertEquals(JsonArray(listOf(JsonPrimitive("lane:$lane"), JsonPrimitive("project:p"))), client.rows.last()["tags"])
            assertFalse(invoke("conversation.create", first))
            val other = buildJsonObject { put("agent_id", "a"); put("tags", JsonArray(listOf(JsonPrimitive("other")))) }
            assertTrue(invoke("conversation.create", other))
            val id = client.rows.last().getValue("id").jsonPrimitive.content
            assertFalse(invoke("conversation.update", buildJsonObject {
                put("conversation_id", id)
                put("tags", JsonArray(listOf(JsonPrimitive("lane:$lane"))))
            }))
        }
    }
}
