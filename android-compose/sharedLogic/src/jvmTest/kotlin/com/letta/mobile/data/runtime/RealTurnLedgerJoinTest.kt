package com.letta.mobile.data.runtime

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-r1xkl: joins the REAL captured turns (4 models x 2 turns, each a user prompt, one
 * tool call and a streamed two-paragraph reply) to their stored `message.list` rows.
 */
class RealTurnLedgerJoinTest {
    private class MemoryStore : TurnIdentityStore {
        val data = LinkedHashMap<StoredRowRef, RowIdentity>()

        override suspend fun load(conversationId: String): Map<StoredRowRef, RowIdentity> = data.toMap()

        override suspend fun append(conversationId: String, entries: Map<StoredRowRef, RowIdentity>) {
            data.putAll(entries)
        }
    }

    private fun ledgerOver(rows: List<JsonObject>) =
        TurnIdentityLedger(MemoryStore(), { _, _ -> rows }) { 0L }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content

    @Test
    fun everyRealTurnJoinsUserToolAndReplyRowsWithNothingUnmatched() = runTest {
        RealTurnLedgerFixtures.MODELS.forEach { model ->
            listOf(1, 2).forEach { turn ->
                val settled = RealTurnLedgerFixtures.settledTurn(model, turn)

                val report = ledgerOver(RealTurnLedgerFixtures.listedRows(model, turn)).settleTurn("conv", settled)

                assertTrue(settled.messages.size >= 4, "$model turn$turn streamed ${settled.messages.size}")
                assertEquals(TurnMatchReport(settled.messages.size, emptyList()), report, "$model turn$turn")
            }
        }
    }

    @Test
    fun theReplyRowCarriesTheLogicalIdTheStreamMinted() = runTest {
        RealTurnLedgerFixtures.MODELS.forEach { model ->
            val settled = RealTurnLedgerFixtures.settledTurn(model, 1)
            val rows = RealTurnLedgerFixtures.listedRows(model, 1)
            val ledger = ledgerOver(rows)
            ledger.settleTurn("conv", settled)

            val enriched = ledger.enrich("conv", rows)

            val reply = enriched.first { it.text("message_type") == "assistant_message" }
            val streamed = settled.messages.last { it.messageType == "assistant_message" }
            assertEquals(streamed.logicalId, reply.text("logical_message_id"), model)
            assertEquals(settled.turnId, reply.text("turn_id"), model)
            val toolCall = enriched.first { it.text("message_type") == "approval_request_message" }
            assertTrue(checkNotNull(toolCall.text("logical_message_id")).startsWith("tc-"), model)
            val toolReturn = enriched.first { it.text("message_type") == "tool_return_message" }
            assertTrue(checkNotNull(toolReturn.text("logical_message_id")).startsWith("tr-"), model)
        }
    }

    @Test
    fun theUserRowJoinsByOtidDespiteThePrependedSystemReminder() = runTest {
        RealTurnLedgerFixtures.MODELS.forEach { model ->
            val settled = RealTurnLedgerFixtures.settledTurn(model, 1)
            val rows = RealTurnLedgerFixtures.listedRows(model, 1)
            val storedUser = rows.first { it.text("message_type") == "user_message" }
            val ledger = ledgerOver(rows)
            ledger.settleTurn("conv", settled)

            val enriched = ledger.enrich("conv", rows).first { it.text("message_type") == "user_message" }

            assertEquals(storedUser.text("otid"), enriched.text("logical_message_id"), model)
            assertEquals(storedUser.text("otid"), enriched.text("turn_id"), model)
        }
    }

    @Test
    fun theTwoRepliesOfOneConversationKeepDistinctLogicalIds() = runTest {
        val model = RealTurnLedgerFixtures.MODELS.first()
        val rows = RealTurnLedgerFixtures.listedRows(model, 2)
        val ledger = ledgerOver(rows)
        ledger.settleTurn("conv", RealTurnLedgerFixtures.settledTurn(model, 1))
        ledger.settleTurn("conv", RealTurnLedgerFixtures.settledTurn(model, 2))

        val replies = ledger.enrich("conv", rows).filter { it.text("message_type") == "assistant_message" }

        assertEquals(2, replies.size)
        assertNotEquals(replies[0].text("logical_message_id"), replies[1].text("logical_message_id"))
    }
}
