package com.letta.mobile.data.repository

import com.letta.mobile.data.model.StepFeedbackUpdateParams
import com.letta.mobile.testutil.FakeStepApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StepRepositoryTest {

    private lateinit var fakeApi: FakeStepApi
    private lateinit var repository: StepRepository

    @Before
    fun setup() {
        fakeApi = FakeStepApi()
        repository = StepRepository(fakeApi)
    }

    @Test
    fun `refreshSteps updates state flow`() = runTest {
        fakeApi.steps.addAll(listOf(fakeApi.sampleStep("step-1"), fakeApi.sampleStep("step-2")))

        repository.refreshSteps()

        assertEquals(2, repository.steps.first().size)
    }

    @Test
    fun `listSteps delegates to api`() = runTest {
        fakeApi.steps.addAll(listOf(fakeApi.sampleStep("step-1"), fakeApi.sampleStep("step-2")))

        val result = repository.listSteps()

        assertEquals(2, result.size)
        assertTrue(fakeApi.calls.contains("listSteps"))
    }

    @Test
    fun `getStepMetrics delegates to api`() = runTest {
        val result = repository.getStepMetrics("step-1")

        assertEquals("step-1", result.id)
        assertTrue(fakeApi.calls.contains("retrieveStepMetrics:step-1"))
    }

    @Test
    fun `getStepTrace delegates to api`() = runTest {
        val result = repository.getStepTrace("step-1")

        assertEquals("step-1", result?.stepId)
        assertTrue(fakeApi.calls.contains("retrieveStepTrace:step-1"))
    }

    @Test
    fun `updateStepFeedback refreshes cached step`() = runTest {
        repository.upsertStep(fakeApi.sampleStep("step-1"))

        val updated = repository.updateStepFeedback("step-1", StepFeedbackUpdateParams(feedback = "negative", tags = listOf("reviewed")))

        assertEquals("negative", updated.feedback)
        assertEquals("negative", repository.steps.first().first().feedback)
        assertTrue(fakeApi.calls.contains("updateStepFeedback:step-1"))
    }
}
