package com.letta.mobile.data.schedules.runs

import com.letta.mobile.data.schedules.RunStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CronRunRecordsCommonTest {
    @Test
    fun malformedAndUnrecognizedEntriesDoNotBecomeSuccesses() {
        val row = buildJsonObject {
            put("ts", 1793577600000L)
            put("jobId", "task-A")
            put("status", "unknown")
            put("extra", "ignored")
        }
        val page = JsonObject(mapOf("entries" to JsonArray(listOf(row, JsonObject(row - "ts"), JsonPrimitive("bad")))))
        val record = cronRunRecords(page).single()
        assertEquals(RunStatus.Unrecorded, record.status)
        assertNull(record.reason)
        assertNull(record.source)
    }
}
