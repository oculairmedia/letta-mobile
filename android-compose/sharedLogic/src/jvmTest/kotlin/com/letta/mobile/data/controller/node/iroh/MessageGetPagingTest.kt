package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageGetPagingTest {
    @BeforeTest
    fun setPageLimit() {
        NativeAdmin.resetCircuitForTest()
        ConversationAdminHandlers.messageGetPageLimitForTest = 3
    }

    @AfterTest
    fun clearPageLimit() {
        ConversationAdminHandlers.messageGetPageLimitForTest = null
        NativeAdmin.resetCircuitForTest()
    }

    private class PagingClient(
        private val pages: Map<String?, JsonArray>,
    ) : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow()
        val beforeCursors = mutableListOf<String?>()

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = error("unused")
        override suspend fun input(command: AppServerCommand.Input) = error("unused")
        override suspend fun sync(command: AppServerCommand.Sync) = error("unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage) = error("unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = error("unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = error("unused")
        override suspend fun conversationMessagesList(command: AppServerCommand.ConversationMessagesList) =
            AppServerInboundFrame.ConversationMessagesListResponse(
                command.requestId,
                true,
                pages[beforeCursor(command.query)] ?: JsonArray(emptyList()),
            ).also { beforeCursors += beforeCursor(command.query) }

        private fun beforeCursor(query: JsonObject?): String? =
            query?.get("before")?.jsonPrimitive?.contentOrNull
    }

    private fun page(vararg ids: String): JsonArray = buildJsonArray {
        ids.forEach { id ->
            add(
                buildJsonObject {
                    put("id", id)
                    put("message_type", "user_message")
                    put("content", id)
                },
            )
        }
    }

    @Test
    fun messageGetWalksBeforeCursorUntilMatch() = runTest {
        val client = PagingClient(
            mapOf(
                null to page("n1", "n2", "n3"),
                "n3" to page("o1", "o2", "target"),
            ),
        )
        val router = AdminRpcRouter()
        ConversationAdminHandlers.register(
            router,
            tiers = NativeReadTiers(nativeClient = client),
        )
        val response = Json.parseToJsonElement(
            router.dispatch(
                requestId = "pg",
                method = "message.get",
                params = buildJsonObject {
                    put("conversation_id", "conv-1")
                    put("message_id", "target")
                },
            ),
        ).jsonObject

        assertTrue(response.getValue("success").jsonPrimitive.boolean)
        assertEquals("target", response.getValue("result").jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(listOf(null, "n3"), client.beforeCursors)
    }
}
