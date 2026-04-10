package com.letta.mobile.ui.screens.messagebatches

import com.letta.mobile.data.model.BatchMessage
import com.letta.mobile.data.model.Job
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.testutil.FakeMessageApi
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageBatchMonitorViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeApi: FakeMessageApi
    private lateinit var repository: MessageRepository
    private lateinit var viewModel: MessageBatchMonitorViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeApi = FakeMessageApi()
        fakeApi.batches.addAll(
            listOf(
                Job(
                    id = "batch-1",
                    status = "running",
                    createdAt = "2026-04-10T12:00:00Z",
                    jobType = "message_batch",
                    agentId = "agent-1",
                ),
                Job(
                    id = "batch-2",
                    status = "completed",
                    createdAt = "2026-04-09T12:00:00Z",
                    jobType = "message_batch",
                    agentId = "agent-2",
                ),
            )
        )
        fakeApi.batchMessagesByBatchId["batch-1"] = listOf(
            BatchMessage(
                id = "message-1",
                agentId = "agent-1",
                role = "user",
                content = JsonPrimitive("hello batch"),
                runId = "run-1",
                batchItemId = "item-1",
                createdAt = "2026-04-10T12:00:01Z",
            )
        )
        repository = MessageRepository(fakeApi, mockk(relaxed = true))
        viewModel = MessageBatchMonitorViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadBatches populates state`() = runTest {
        viewModel.loadBatches()

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertEquals(2, state.data.batches.size)
        assertEquals("batch-1", state.data.batches.first().id)
    }

    @Test
    fun `updateSearchQuery filters batches locally`() = runTest {
        viewModel.loadBatches()
        viewModel.updateSearchQuery("agent-2")

        val filtered = viewModel.getFilteredBatches()
        assertEquals(1, filtered.size)
        assertEquals("batch-2", filtered.first().id)
    }

    @Test
    fun `inspectBatch loads batch messages`() = runTest {
        viewModel.inspectBatch("batch-1")

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertEquals("batch-1", state.data.selectedBatch?.id)
        assertEquals(1, state.data.selectedBatchMessages.size)
        assertEquals("hello batch", state.data.selectedBatchMessages.first().content?.toString()?.trim('"'))
    }

    @Test
    fun `cancelBatch delegates to repository and refreshes batch`() = runTest {
        viewModel.inspectBatch("batch-1")

        viewModel.cancelBatch("batch-1")

        val state = viewModel.uiState.value as com.letta.mobile.ui.common.UiState.Success
        assertTrue(fakeApi.calls.contains("cancelBatch:batch-1"))
        assertEquals("cancelled", state.data.selectedBatch?.status)
    }
}
