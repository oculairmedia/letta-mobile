package com.letta.mobile.data.feed.wire

import com.letta.mobile.data.serialization.LettaJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Every conversation feed v1 fixture case, decoded through the client's own types (the host replays the same file
 * through its real route).
 */
class FeedFixtureTest {
    private val root = Json.parseToJsonElement(feedSubscribeContractText).jsonObject

    private val cases: List<JsonObject> get() = root.getValue("cases").jsonArray.map { it.jsonObject }

    private fun successes(): List<JsonObject> =
        cases.map { it.getValue("expected_response").jsonObject }.filter { it.getValue("success").jsonPrimitive.boolean }

    private fun JsonObject?.observedTerminal(): Boolean? = this?.get("terminal_observed")?.jsonPrimitive?.boolean

    @Test
    fun theFixtureNamesItsMethodAndCapability() {
        assertEquals(ConversationFeedWire.SUBSCRIBE, root.getValue("method").jsonPrimitive.content)
        assertEquals(ConversationFeedWire.CAPABILITY, root.getValue("capability").jsonPrimitive.content)
        assertTrue(ConversationFeedWire.SUBSCRIBE in ConversationFeedWire.METHODS)
    }

    @Test
    fun everySuccessfulResultDecodesToAPageAndReEncodesToTheSameJson() {
        val pages = cases.map { it.getValue("expected_response").jsonObject }.filter { it.getValue("success").jsonPrimitive.boolean }
        assertTrue(pages.isNotEmpty())
        pages.forEach { response ->
            val result = response.getValue("result").jsonObject
            val page = LettaJson.wire.decodeFromJsonElement(FeedPage.serializer(), result)
            assertEquals(result, LettaJson.wire.encodeToJsonElement(FeedPage.serializer(), page))
        }
    }

    @Test
    fun everyPageObeysTheContractsStructuralRules() {
        successes().forEach { response ->
            val page = LettaJson.wire.decodeFromJsonElement(FeedPage.serializer(), response.getValue("result"))
            assertTrue(page.mode == ConversationFeedWire.MODE_REPLAY || page.mode == ConversationFeedWire.MODE_SNAPSHOT)
            if (page.mode == ConversationFeedWire.MODE_REPLAY) assertNull(page.resyncReason) else assertNotNull(page.resyncReason)
            assertTrue(page.entries.map { it.seq }.zipWithNext().all { (a, b) -> a < b }, "seq strictly increases within a page")
            assertTrue(page.entries.all { (it.payload == null) != (it.pointer == null) }, "exactly one of payload and pointer")
            assertTrue(page.entries.all { it.seq <= page.headSeq && it.seq > page.baseSeq })
            if (!page.hasMore) assertEquals(page.headSeq, page.cursor.seq, "a caught-up page's cursor is the head")
        }
    }

    @Test
    fun noPageEverCarriesATerminalTheHostDidNotObserve() {
        val closedWithoutTerminal = cases.map { it.getValue("expected_response").jsonObject }
            .filter { it.getValue("success").jsonPrimitive.boolean }
            .flatMap { LettaJson.wire.decodeFromJsonElement(FeedPage.serializer(), it.getValue("result")).entries }
            .filter { it.kind == ConversationFeedWire.KIND_TURN_CLOSED && it.payload.observedTerminal() == false }
        assertTrue(closedWithoutTerminal.isNotEmpty(), "the fixture covers a turn closed without a terminal")
        closedWithoutTerminal.forEach { closed ->
            val turn = cases.flatMap { case ->
                case.getValue("expected_response").jsonObject.takeIf { it.getValue("success").jsonPrimitive.boolean }
                    ?.let { LettaJson.wire.decodeFromJsonElement(FeedPage.serializer(), it.getValue("result")).entries }.orEmpty()
            }.filter { it.turn == closed.turn }
            val types = turn.mapNotNull { it.payload?.get("message_type")?.jsonPrimitive?.content }
            val invented = types.filter { it == "stop_reason" || it == "error_message" || it == "loop_error" }
            assertTrue(invented.isEmpty(), "invented terminal in ${closed.turn}: $invented")
        }
    }

    @Test
    fun everyOversizedPayloadIsAPointerNamingWhatWasOmittedAndNeverTheData() {
        val pointers = cases.map { it.getValue("expected_response").jsonObject }.filter { it.getValue("success").jsonPrimitive.boolean }
            .flatMap { LettaJson.wire.decodeFromJsonElement(FeedPage.serializer(), it.getValue("result")).entries }
            .filter { it.pointer != null }
        assertTrue(pointers.isNotEmpty(), "the fixture covers a pointer")
        pointers.forEach { entry ->
            val byteLen = entry.pointer!!.getValue(ConversationFeedWire.POINTER_BYTE_LEN).jsonPrimitive.content.toInt()
            assertTrue(byteLen > ConversationFeedWire.MAX_INLINE_PAYLOAD_BYTES)
            assertTrue(entry.pointer.toString().length < 512, "a pointer is small")
        }
    }

    @Test
    fun everyResyncReasonTheClientCanReceiveIsCoveredByTheFixture() {
        val reasons = cases.map { it.getValue("expected_response").jsonObject }.filter { it.getValue("success").jsonPrimitive.boolean }
            .mapNotNull { it.getValue("result").jsonObject["resync_reason"]?.jsonPrimitive?.content }.toSet()
        assertTrue(
            setOf(
                ConversationFeedWire.RESYNC_NO_CURSOR,
                ConversationFeedWire.RESYNC_EPOCH_CHANGED,
                ConversationFeedWire.RESYNC_AHEAD_OF_HEAD,
            ).all { it in reasons },
            "covered: $reasons",
        )
    }

    @Test
    fun theRequestParamsInEveryCaseUseOnlyTheNamedFields() {
        val known = setOf(
            ConversationFeedWire.CONVERSATION_ID, ConversationFeedWire.AGENT_ID, ConversationFeedWire.FROM, ConversationFeedWire.LIMIT,
        )
        cases.forEach { case -> assertTrue(case.getValue("params").jsonObject.keys.all { it in known }, case.getValue("name").toString()) }
    }
}
