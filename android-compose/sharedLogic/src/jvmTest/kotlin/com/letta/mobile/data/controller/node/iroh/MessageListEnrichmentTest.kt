package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.runtime.RealTurnLedgerFixtures
import com.letta.mobile.data.runtime.RowIdentity
import com.letta.mobile.data.runtime.StoredRowRef
import com.letta.mobile.data.runtime.TurnIdentityLedger
import com.letta.mobile.data.runtime.TurnIdentityStore
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** letta-mobile-r1xkl: a row served by the real `message.list` route carries the ledger's two ids. */
class MessageListEnrichmentTest {
    @BeforeTest
    fun resetCircuit() = NativeAdmin.resetCircuitForTest()

    @AfterTest
    fun resetCircuitAfter() = NativeAdmin.resetCircuitForTest()

    private class ListClient(private val rows: List<JsonObject>) : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow()

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = error("unused")
        override suspend fun input(command: AppServerCommand.Input) = error("unused")
        override suspend fun sync(command: AppServerCommand.Sync) = error("unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage) = error("unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = error("unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = error("unused")
        override suspend fun conversationMessagesList(command: AppServerCommand.ConversationMessagesList) =
            AppServerInboundFrame.ConversationMessagesListResponse(command.requestId, true, JsonArray(rows))
    }

    private class MemoryStore : TurnIdentityStore {
        val data = LinkedHashMap<StoredRowRef, RowIdentity>()
        override suspend fun load(conversationId: String) = data.toMap()
        override suspend fun append(conversationId: String, entries: Map<StoredRowRef, RowIdentity>) {
            data.putAll(entries)
        }
    }

    private suspend fun listVia(ledger: TurnIdentityLedger?, client: ListClient): JsonArray {
        val router = AdminRpcRouter()
        ConversationAdminHandlers.register(router, tiers = NativeReadTiers(nativeClient = client, turnIdentity = ledger))
        val response = Json.parseToJsonElement(
            router.dispatch("ml", "message.list", buildJsonObject { put("conversation_id", "conv-1") }),
        ).jsonObject
        return response.getValue("result").jsonArray
    }

    @Test
    fun aListedRowCarriesLogicalMessageIdAndTurnId() = runTest {
        val model = RealTurnLedgerFixtures.MODELS.first()
        val rows = RealTurnLedgerFixtures.listedRows(model, 1)
        val ledger = TurnIdentityLedger(MemoryStore(), { _, limit -> rows.take(limit) }) { 0L }
        val settled = RealTurnLedgerFixtures.settledTurn(model, 1)
        ledger.settleTurn("conv-1", settled)

        val listed = listVia(ledger, ListClient(rows))

        val reply = listed.map { it.jsonObject }.first { it["message_type"]?.jsonPrimitive?.contentOrNull == "assistant_message" }
        assertEquals(
            settled.messages.first { it.messageType == "assistant_message" }.logicalId,
            reply["logical_message_id"]?.jsonPrimitive?.contentOrNull,
        )
        assertEquals(settled.turnId, reply["turn_id"]?.jsonPrimitive?.contentOrNull)
        listed.forEach { assertNotNull(it.jsonObject["logical_message_id"]) }
    }

    @Test
    fun withoutALedgerRowsAreServedAsStored() = runTest {
        val rows = RealTurnLedgerFixtures.listedRows(RealTurnLedgerFixtures.MODELS.first(), 1)

        val listed = listVia(null, ListClient(rows))

        assertEquals(null, listed.first().jsonObject["logical_message_id"])
    }
}
