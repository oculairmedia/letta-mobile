package com.letta.mobile.data.schedules.leases

import com.letta.mobile.data.schedules.runs.LeaseHistory
import com.letta.mobile.data.schedules.runs.leaseRecords
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class LeaseTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun root(): File =
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "docs/reference/contracts/lease.list/wrapper-records-v1.json").isFile }

    private fun fixture(): AppServerInboundFrame.AdminRpcResponse {
        val path = "docs/reference/contracts/run.record/success-examples-v1.json"
        val root = root()
        val cases = Json.parseToJsonElement(File(root, path).readText()).jsonObject.getValue("cases").jsonArray
        val response = cases.first { it.jsonObject["name"] == JsonPrimitive("lease-list") }.jsonObject.getValue("response")
        return json.decodeFromJsonElement(AppServerInboundFrame.AdminRpcResponse.serializer(), response)
    }

    @Test
    fun mapsTheActualLeaseListResponseAndUnknownStates() {
        val response = fixture()
        val lease = leaseRecords(response).single()
        assertEquals("lease-1", lease.id)
        assertEquals("agent-A", lease.owner)
        assertEquals("work", lease.label)
        assertEquals(LeaseState.RECOVERED, lease.state)
        val result = response.result!!.jsonObject
        val row = result.getValue("leases").jsonArray.single().jsonObject
        mapOf(
            "active" to LeaseState.ACTIVE,
            "done" to LeaseState.DONE,
            "expired" to LeaseState.EXPIRED,
            "recovered" to LeaseState.RECOVERED,
            "future" to LeaseState.UNKNOWN,
        ).forEach { (rawState, expectedState) ->
            val modifiedRow = kotlinx.serialization.json.JsonObject(row + ("state" to JsonPrimitive(rawState)))
            val modified = kotlinx.serialization.json.JsonObject(
                result + ("leases" to kotlinx.serialization.json.JsonArray(listOf(modifiedRow))),
            )
            assertEquals(expectedState, leaseRecords(response.copy(result = modified)).single().state)
        }
    }

    @Test
    fun shippedLeaseListErrorFixtureMapsToNoLeases() {
        val producerPath = "docs/reference/contracts/lease.list/wrapper-records-v1.json"
        val clientPath = "android-compose/sharedLogic/src/commonTest/resources/contracts/lease.list/wrapper-records-v1.json"
        val root = root()
        val producer = File(root, producerPath).readText()
        val fixture = json.parseToJsonElement(File(root, clientPath).readText()).jsonObject
        assertEquals(producer, File(root, clientPath).readText(), "lease.list client fixture must remain byte-for-byte equivalent")
        val response = fixture.getValue("cases").jsonArray.single().jsonObject.getValue("expected_response")
        val frame = json.decodeFromJsonElement(AppServerInboundFrame.AdminRpcResponse.serializer(), response)
        assertTrue(leaseRecords(frame).isEmpty())
    }

    @Test
    fun readsEveryPageFromAnAdvertisingWrapper() = runTest {
        val offsets = mutableListOf<Int>()
        val original = fixture()
        val base = original.result!!.jsonObject
        val transport = object : NoOpChannelTransport() {
            override val hostCapabilities = setOf("lease.list")
            override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse {
                assertEquals("lease.list", method)
                val params = Json.parseToJsonElement(body!!).jsonObject
                assertEquals("agent-A", params.getValue("agentId").jsonPrimitive.content)
                val offset = params.getValue("offset").jsonPrimitive.content.toInt()
                offsets += offset
                val page = kotlinx.serialization.json.JsonObject(base + mapOf(
                    "hasMore" to JsonPrimitive(offset == 0),
                    "nextOffset" to if (offset == 0) JsonPrimitive(1) else kotlinx.serialization.json.JsonNull,
                ))
                return original.copy(result = page)
            }
        }
        assertEquals(2, LeaseHistory(transport).load(AgentId("agent-A")).size)
        assertEquals(listOf(0, 1), offsets)
    }

    @Test
    fun olderWrapperHasNoLeaseRequests() = runTest {
        val transport = object : NoOpChannelTransport() {
            override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse =
                error("unexpected request")
        }
        assertTrue(LeaseHistory(transport).load(AgentId("agent-A")).isEmpty())
    }

    @Test
    fun splitsAtMidnightAndClampsNonpositiveDurationToOneMinute() {
        val lease = leaseRecords(fixture()).single().copy(
            startedAt = Instant.parse("2026-11-02T23:30:00Z"), dueAt = Instant.parse("2026-11-03T01:15:00Z"),
        )
        val first = leaseSpan(lease, LocalDate(2026, 11, 2), TimeZone.UTC)!!
        val second = leaseSpan(lease, LocalDate(2026, 11, 3), TimeZone.UTC)!!
        assertEquals(1410f, first.startMinute)
        assertEquals(30f, first.durationMinutes)
        assertEquals(0f, second.startMinute)
        assertEquals(75f, second.durationMinutes)
        val clamped = lease.copy(dueAt = lease.startedAt)
        assertEquals(1f, leaseSpan(clamped, LocalDate(2026, 11, 2), TimeZone.UTC)!!.durationMinutes)
        assertEquals(
            1f,
            leaseSpan(
                clamped.copy(dueAt = Instant.parse("2026-11-01T00:00:00Z")),
                LocalDate(2026, 11, 2),
                TimeZone.UTC,
            )!!.durationMinutes,
        )
    }
}
