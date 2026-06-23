package com.letta.mobile.data.repository

import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduleDefinition
import com.letta.mobile.data.model.ScheduleMessage
import com.letta.mobile.data.model.SchedulePayload
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.testutil.FakeScheduleApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class ScheduleRepositoryTest {

    private lateinit var fakeApi: FakeScheduleApi
    private lateinit var repository: ScheduleRepository

    @Before
    fun setup() {
        fakeApi = FakeScheduleApi()
        repository = ScheduleRepository(fakeApi)
    }

    @Test
    fun `refreshSchedules updates agent flow`() = runTest {
        fakeApi.schedules["a1"] = mutableListOf(sampleScheduledMessage())

        repository.refreshSchedules("a1")

        assertEquals(1, repository.getSchedules("a1").first().size)
    }

    @Test
    fun `refreshSchedules handles empty scheduled messages successfully`() = runTest {
        fakeApi.schedules["a1"] = mutableListOf()

        repository.refreshSchedules("a1")

        assertTrue(repository.getSchedules("a1").first().isEmpty())
        assertTrue(fakeApi.calls.contains("listSchedules:a1"))
    }

    @Test
    fun `createSchedule refreshes repository cache`() = runTest {
        repository.createSchedule(
            agentId = "a1",
            params = ScheduleCreateParams(
                messages = listOf(ScheduleMessage(content = "hello", role = "user")),
                schedule = ScheduleDefinition(type = "one-time", scheduledAt = 1_700_000_000.0),
            )
        )

        assertTrue(fakeApi.calls.contains("createSchedule:a1"))
        assertEquals(1, repository.getSchedules("a1").first().size)
    }

    @Test
    fun `deleteSchedule removes from cache`() = runTest {
        fakeApi.schedules["a1"] = mutableListOf(sampleScheduledMessage())
        repository.refreshSchedules("a1")

        repository.deleteSchedule("a1", "s1")

        assertTrue(fakeApi.calls.contains("deleteSchedule:a1:s1"))
        assertTrue(repository.getSchedules("a1").first().isEmpty())
    }

    @Test
    fun `getSchedule retrieves single scheduled message`() = runTest {
        fakeApi.schedules["a1"] = mutableListOf(sampleScheduledMessage())

        val result = repository.getSchedule("a1", "s1")

        assertEquals("s1", result.id)
        assertEquals("one-time", result.schedule.type)
    }

    private fun sampleScheduledMessage() = ScheduledMessage(
        id = "s1",
        agentId = "a1",
        message = SchedulePayload(
            messages = listOf(ScheduleMessage(content = "hello", role = "user"))
        ),
        schedule = ScheduleDefinition(type = "one-time", scheduledAt = 1_700_000_000.0),
        nextScheduledTime = "2026-04-09T10:00:00Z",
    )
}
