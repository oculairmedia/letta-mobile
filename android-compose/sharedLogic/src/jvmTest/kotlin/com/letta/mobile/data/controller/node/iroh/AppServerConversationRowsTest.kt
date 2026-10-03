package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppServerConversationRowsTest {
    /** 250 rows ui-msg-250 (newest) .. ui-msg-1, served by `before` cursor and `limit`. */
    private class History : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow()
        val limits = mutableListOf<Int>()

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = error("unused")
        override suspend fun input(command: AppServerCommand.Input) = error("unused")
        override suspend fun sync(command: AppServerCommand.Sync) = error("unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage) = error("unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = error("unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = error("unused")
        override suspend fun conversationMessagesList(command: AppServerCommand.ConversationMessagesList): AppServerInboundFrame.ConversationMessagesListResponse {
            val limit = command.query?.get("limit")?.jsonPrimitive?.contentOrNull?.toInt() ?: 100
            val before = command.query?.get("before")?.jsonPrimitive?.contentOrNull?.removePrefix("ui-msg-")?.toInt() ?: 251
            limits += limit
            val ids = (before - 1 downTo maxOf(1, before - limit))
            val rows = ids.map { buildJsonObject { put("id", "ui-msg-$it"); put("message_type", "assistant_message"); put("content", "x") } }
            return AppServerInboundFrame.ConversationMessagesListResponse(command.requestId, true, JsonArray(rows))
        }
    }

    @Test
    fun pagesBackByCursorAndCapsTheLastPageAtTheLimit() = runTest {
        val client = History()

        val rows = AppServerConversationRows(client).newestRows("c", 230)

        assertEquals(230, rows.size)
        assertEquals("ui-msg-250", rows.first().getValue("id").jsonPrimitive.content)
        assertEquals(listOf(100, 100, 30), client.limits)
    }

    @Test
    fun stopsAtAShortPage() = runTest {
        val client = History()

        val rows = AppServerConversationRows(client).newestRows("c", 1_000)

        assertEquals(250, rows.size)
        assertTrue(client.limits.size <= 4)
    }

    @Test
    fun lateBoundSourceServesNothingBeforeBind() = runTest {
        assertEquals(emptyList(), LateBoundConversationRows().newestRows("c", 10))
    }
}
