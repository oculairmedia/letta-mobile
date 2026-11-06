package com.letta.mobile.data.lanes

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * letta-mobile-db3fo (C0): the lane-inbox-v1 fixtures stay valid JSON and stay in step with the frozen
 * contract document. Each fixture must carry exactly the fields the document names for its shape, and the
 * rules the document states as values (defaults, limits, state sets) must hold in the fixtures. No host or
 * client code reads these yet; later stages decode them through their real types.
 */
class LaneInboxV1FixturesTest {
    private val dir = File(
        LaneInboxV1FixturesTest::class.java.getResource(ROOT)?.toURI() ?: error("missing fixture dir $ROOT"),
    )
    private val contract: String = generateSequence(dir.absoluteFile) { it.parentFile }
        .map { File(it, CONTRACT_PATH) }
        .firstOrNull(File::isFile)
        ?.readText()
        ?: error("missing $CONTRACT_PATH")

    private fun fixture(name: String): JsonObject =
        Json.parseToJsonElement(File(dir, name).readText()).jsonObject

    private fun assertFields(name: String, expected: Set<String>) {
        assertEquals(expected, fixture(name).keys - "_contract", "$name fields")
        expected.forEach { assertTrue("`$it`" in contract || it in contract, "$name: field $it is not named in the contract") }
    }

    @Test
    fun everyFixtureParsesAndCitesItsContractSection() {
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        assertEquals(EXPECTED_FIXTURES, files.map(File::getName).toSet())
        files.forEach { f ->
            val section = fixture(f.name)["_contract"]?.jsonPrimitive?.content.orEmpty()
            assertTrue(section.startsWith("lane-inbox-v1 §"), "${f.name} must cite its contract section")
        }
    }

    @Test
    fun messageAndCorrelationShapesMatchSection1() {
        assertFields("inbound-message.json", setOf("client_message_id", "sender", "sent_at", "expires_at", "body"))
        assertFields("reply-message.json", setOf("client_message_id", "sender", "sent_at", "correlation_id", "more", "body"))
        assertFields("outbound-request.json", setOf("to", "body", "reply_to"))
        assertFields(
            "pending-reply.json",
            setOf("correlation_id", "origin_agent", "origin_conversation", "expected_replier", "created_at", "expires_at"),
        )
        val request = fixture("outbound-request.json")["reply_to"]!!.jsonObject
        val pending = fixture("pending-reply.json")
        val reply = fixture("reply-message.json")
        assertEquals(pending["correlation_id"], request["correlation_id"], "the request carries the host-minted id")
        assertEquals(pending["correlation_id"], reply["correlation_id"], "peers copy the id into the reply")
        assertEquals(pending["expected_replier"], reply["sender"], "a matched reply comes from the expected replier")
        assertTrue(fixture("inbound-message.json")["client_message_id"]!!.jsonPrimitive.content.length <= MAX_CLIENT_MESSAGE_ID)
        assertNull(fixture("inbound-message.json")["correlation_id"], "correlation_id is present only on replies")
    }

    @Test
    fun senderGrantsMatchSection4AndDefaultDenies() {
        val fields = setOf(
            "sender_endpoint", "trackers", "proposal_kinds", "max_messages_per_hour",
            "max_open_proposals", "receipts", "memory_view",
        )
        assertFields("sender-grant.json", fields)
        assertFields("sender-grant-default.json", fields)
        val default = fixture("sender-grant-default.json")
        assertEquals(JsonArray(emptyList()), default["trackers"], "unknown senders cannot file beads")
        assertEquals(JsonArray(emptyList()), default["proposal_kinds"], "unknown senders cannot propose")
        assertEquals(JsonArray(emptyList()), default["memory_view"], "persona only by default (Q1)")
        assertEquals("minimal", default["receipts"]!!.jsonPrimitive.content, "minimal receipts by default (Q3)")
        assertEquals(DEFAULT_RATE, default["max_messages_per_hour"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun proposalMatchesSection5AndStartsPending() {
        assertFields("proposal.json", setOf("id", "kind", "args", "sender", "message_key", "created_at", "expires_at", "status"))
        assertEquals("pending", fixture("proposal.json")["status"]!!.jsonPrimitive.content)
        PROPOSAL_STATES.forEach { assertTrue("`$it`" in contract, "proposal state $it is in the contract") }
    }

    @Test
    fun digestEntryAndMarkersMatchSection6() {
        assertFields(
            "digest-entry.json",
            setOf(
                "entry_id", "message_key", "sender", "received_at", "finished_at",
                "outcome", "refs", "denied_tools", "summary_quote", "pinned",
            ),
        )
        val entry = fixture("digest-entry.json")
        assertTrue(entry["outcome"]!!.jsonPrimitive.content in DIGEST_OUTCOMES)
        assertEquals(setOf("beads_created", "beads_commented", "proposals", "reply_sent"), entry["refs"]!!.jsonObject.keys)
        assertTrue(entry["summary_quote"]!!.jsonPrimitive.content.length <= MAX_SUMMARY_QUOTE)
        assertFields("digest-markers.json", setOf("agent_id", "main_conversation", "shown_through", "acknowledged_through"))
        assertEquals(JsonNull, fixture("digest-markers.json")["acknowledged_through"], "only the human acknowledges")
    }

    @Test
    fun receiptStatesMatchSection7() {
        val receipts = fixture("receipts.json")
        val states = receipts["sequence"]!!.jsonArray.map { it.jsonObject["state"]!!.jsonPrimitive.content }
        assertEquals(listOf("accepted", "delivered", "handled", "answered"), states)
        val terminal = receipts["terminal_examples"]!!.jsonArray.map { it.jsonObject["state"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("rejected", "failed", "expired"), terminal)
        assertEquals(
            listOf("accepted", "rejected", "failed"),
            receipts["minimal_states"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        (states + terminal).forEach { assertTrue("`$it`" in contract, "receipt state $it is in the contract") }
    }

    @Test
    fun retentionNumbersHoldAndDedupeOutlastsTheLongestRequest() {
        listOf("7 days", "24h", "500", "72h", "14 days", "24h retry horizon").forEach {
            assertTrue(it in contract, "retention value $it is in the contract")
        }
        assertTrue(DEDUPE_RETENTION_DAYS > REQUEST_EXPIRY_DAYS + MAX_RETRY_WINDOW_DAYS, "dedupe outlasts expiry plus retries (Q4)")
    }

    private companion object {
        const val ROOT = "/lane-inbox-v1"
        const val CONTRACT_PATH = "docs/reference/contracts/lane-inbox-v1.md"
        const val MAX_CLIENT_MESSAGE_ID = 128
        const val MAX_SUMMARY_QUOTE = 200
        const val DEFAULT_RATE = 10
        const val DEDUPE_RETENTION_DAYS = 14
        const val REQUEST_EXPIRY_DAYS = 7
        const val MAX_RETRY_WINDOW_DAYS = 1 // the contract's 24h retry horizon (§2)
        val EXPECTED_FIXTURES = setOf(
            "inbound-message.json", "reply-message.json", "outbound-request.json", "pending-reply.json",
            "sender-grant.json", "sender-grant-default.json", "proposal.json", "digest-entry.json",
            "digest-markers.json", "receipts.json",
        )
        val PROPOSAL_STATES = listOf("pending", "approved", "declined", "expired", "superseded")
        val DIGEST_OUTCOMES = setOf("handled", "replied", "proposed", "failed", "rejected", "dead_letter")
    }
}
