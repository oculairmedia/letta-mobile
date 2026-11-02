package com.letta.mobile.data.schedules.runs

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.schedules.CalendarQuery
import com.letta.mobile.data.schedules.CalendarRange
import com.letta.mobile.data.schedules.ScheduleCalendar
import com.letta.mobile.data.schedules.ScheduleClock
import com.letta.mobile.data.schedules.ScheduleDef
import com.letta.mobile.data.schedules.ScheduleProjection
import com.letta.mobile.data.schedules.RunStatus
import com.letta.mobile.data.schedules.SchedulesView
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class CronRunRecordsTest {
    private val now = Instant.parse("2026-11-02T12:00:00Z")
    private val date = LocalDate(2026, 11, 2)
    private val def = ScheduleDef("task-A", "daily", null, null, true)

    private fun page(): JsonObject {
        val path = "docs/reference/contracts/run.record/merged-cron-runs-v1.json"
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.first { File(it, path).isFile }
        val case = Json.parseToJsonElement(File(root, path).readText()).jsonObject
        val producerPath = "docs/reference/contracts/run.record/success-examples-v1.json"
        val producer = Json.parseToJsonElement(File(root, producerPath).readText()).jsonObject
        assertEquals(producer.getValue("cases").jsonArray.last(), case)
        return case.getValue("response").jsonObject.getValue("result").jsonObject.getValue("page").jsonObject
    }

    @Test
    fun wrapperAndUpstreamRowsRespectFixture() {
        val records = cronRunRecords(page())
        assertEquals(2, records.size)
        assertEquals("requested by operator", records[0].reason)
        assertEquals("human", records[0].source)
        assertNull(records[1].reason)
        assertNull(records[1].source)
        val history = ScheduleProjection.history(listOf(def), now, TimeZone.UTC, mapOf(def.id to records))
        assertEquals("human", history.schedules.single().recentRuns.first().source)
    }

    @Test
    fun missingReasonAndExtraFieldsAreSafeAndMalformedRowsAreSkipped() {
        val upstream = page().getValue("entries").jsonArray[1].jsonObject
        val old = JsonObject(upstream.filterKeys { it != "reason" && it != "source" } + ("extra" to JsonPrimitive("ignored")))
        val malformed = JsonObject(old - "ts")
        val result = cronRunRecords(JsonObject(mapOf("entries" to JsonArray(listOf(old, malformed, JsonPrimitive("bad"))))))
        assertEquals(1, result.size)
        assertNull(result.single().reason)
        val legacyReason = JsonObject(page().getValue("entries").jsonArray[0].jsonObject - "source")
        assertNull(cronRunRecords(JsonObject(mapOf("entries" to JsonArray(listOf(legacyReason))))).single().reason)
    }

    @Test
    fun preservedUpstreamOutcomeAndErrorAreRecognizedWithoutInventingSuccess() {
        val row = page().getValue("entries").jsonArray[1].jsonObject
        val byOutcome = JsonObject((row - "status") + ("outcome" to JsonPrimitive("success")))
        val byError = JsonObject((row - "status") + ("error" to JsonPrimitive("failed")))
        val records = cronRunRecords(JsonObject(mapOf("entries" to JsonArray(listOf(byOutcome, byError)))))
        assertEquals(listOf(RunStatus.Done, RunStatus.Failed), records.map { it.status })
    }

    @Test
    fun historyFetchUsesWrapperCapabilityAndTaskParameters() = runTest {
        val calls = mutableListOf<JsonObject>()
        val fixture = page()
        val transport = object : NoOpChannelTransport() {
            override val hostCapabilities = setOf("run.record")
            override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse {
                assertEquals("cron.runs", method)
                val params = Json.parseToJsonElement(requireNotNull(body)).jsonObject
                calls += params
                val responsePage = if (params.getValue("task_id").jsonPrimitive.content == "task-A") fixture else buildJsonObject {
                    put("entries", JsonArray(emptyList()))
                    put("hasMore", false)
                }
                return AppServerInboundFrame.AdminRpcResponse("test", true, buildJsonObject { put("page", responsePage) })
            }
        }
        val records = CronRunHistory(transport).load(AgentId("agent-A"), listOf("task-A"))
        assertEquals("requested by operator", records.first().reason)
        assertEquals(2, calls.size)
        assertEquals("agent-A", calls[0].getValue("agentId").jsonPrimitive.content)
        assertEquals(
            "wrapper:49c092ce7040125d6646de4396e097ea2f05611f619daf559689acb5265780c4",
            calls[1].getValue("task_id").jsonPrimitive.content,
        )
    }

    @Test
    fun olderWrapperWithoutCapabilityDoesNotRequestHistory() = runTest {
        val transport = object : NoOpChannelTransport() {
            override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse =
                error("unexpected request")
        }
        assertTrue(CronRunHistory(transport).load(AgentId("agent-A"), listOf("task-A")).isEmpty())
    }

    @Test
    fun offSlotWakeIsOnlyOnAgenda() {
        val records = cronRunRecords(page())
        val history = mapOf(def.id to records)
        val query = CalendarQuery(ScheduleClock(now, TimeZone.UTC), history)
        for (view in listOf(SchedulesView.Week, SchedulesView.Month)) {
            assertTrue(ScheduleCalendar.days(listOf(def), CalendarRange.dates(view, date), query).all { it.entries.isEmpty() })
        }
        assertTrue(ScheduleProjection.week(listOf(def), date, now, TimeZone.UTC, history).runs.isEmpty())
        val agenda = ScheduleCalendar.days(listOf(def), listOf(date), query.copy(includeUnscheduled = true))
        assertEquals("requested by operator", agenda.single().events.single().run.reason)
        val filtered = query.copy(includePaused = false, includeUnscheduled = true)
        assertTrue(ScheduleCalendar.days(listOf(def.copy(active = false)), listOf(date), filtered).single().entries.isEmpty())
    }
}
