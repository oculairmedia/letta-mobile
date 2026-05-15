package com.letta.mobile.ui.screens.archival

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.letta.mobile.data.model.Passage
import com.letta.mobile.data.repository.PassageRepository
import com.letta.mobile.ui.common.UiState
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class ArchivalViewModelTest {

    private lateinit var fakeRepo: FakePassageRepo
    private lateinit var viewModel: ArchivalViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakePassageRepo()
        val savedState = SavedStateHandle(mapOf("agentId" to "a1"))
        viewModel = ArchivalViewModel(savedState, fakeRepo)
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `loadPassages sets Success`() = runTest {
        fakeRepo.setPassages("a1", listOf(
            Passage(id = "p1", text = "Some knowledge"),
            Passage(id = "p2", text = "More knowledge"),
        ))
        viewModel.loadPassages()
        val state = awaitSuccessState()
        assertEquals(2, state.passages.size)
    }

    @Test
    fun `addPassage refreshes list`() = runTest {
        fakeRepo.setPassages("a1", emptyList())
        viewModel.loadPassages()
        viewModel.addPassage("New passage text")
        assertTrue(fakeRepo.createCalls.contains("a1:New passage text"))
        val state = awaitSuccessState()
        assertTrue(state.passages.any { it.text == "New passage text" })
    }

    @Test
    fun `deletePassage removes from list`() = runTest {
        fakeRepo.setPassages("a1", listOf(Passage(id = "p1", text = "Old")))
        viewModel.loadPassages()
        viewModel.deletePassage("p1")
        val state = awaitSuccessState()
        assertTrue(state.passages.none { it.id == "p1" })
    }

    @Test
    fun `search updates passages with results`() = runTest {
        fakeRepo.setPassages("a1", listOf(Passage(id = "p1", text = "Kotlin is great")))
        fakeRepo.searchResults = listOf(Passage(id = "p1", text = "Kotlin is great"))
        viewModel.loadPassages()
        viewModel.search("Kotlin")
        val state = awaitSuccessState()
        assertEquals("Kotlin", state.searchQuery)
    }

    @Test
    fun `inspectPassage stores selected passage`() = runTest {
        val passage = Passage(id = "p1", text = "Some knowledge", sourceId = "source-1")
        fakeRepo.setPassages("a1", listOf(passage))
        viewModel.loadPassages()

        viewModel.inspectPassage(passage)

        val state = awaitSuccessState()
        assertEquals("p1", state.selectedPassage?.id)
    }

    @Test
    fun `clearSelectedPassage clears selected passage`() = runTest {
        val passage = Passage(id = "p1", text = "Some knowledge", sourceId = "source-1")
        fakeRepo.setPassages("a1", listOf(passage))
        viewModel.loadPassages()
        viewModel.inspectPassage(passage)

        viewModel.clearSelectedPassage()

        val state = awaitSuccessState()
        assertEquals(null, state.selectedPassage)
    }

    @Test
    fun `filterHasSource filters passages locally`() = runTest {
        fakeRepo.setPassages(
            "a1",
            listOf(
                Passage(id = "p1", text = "With source", sourceId = "source-1"),
                Passage(id = "p2", text = "Without source"),
            )
        )
        viewModel.loadPassages()

        viewModel.setFilterHasSource(true)

        val filtered = viewModel.getFilteredPassages()
        assertEquals(1, filtered.size)
        assertEquals("p1", filtered.first().id)
    }

    @Test
    fun `filterHasMetadata filters passages locally`() = runTest {
        fakeRepo.setPassages(
            "a1",
            listOf(
                Passage(id = "p1", text = "With metadata", metadata = mapOf("kind" to "note")),
                Passage(id = "p2", text = "Without metadata"),
            )
        )
        viewModel.loadPassages()

        viewModel.setFilterHasMetadata(true)

        val filtered = viewModel.getFilteredPassages()
        assertEquals(1, filtered.size)
        assertEquals("p1", filtered.first().id)
    }

    @Test
    fun `loadPassages sets Error on failure`() = runTest {
        fakeRepo.shouldFail = true
        viewModel.loadPassages()
        val state = awaitSuccessState()
        assertTrue(state.passages.isEmpty())
    }

    private suspend fun awaitSuccessState(): ArchivalUiState {
        return viewModel.uiState.first { it is UiState.Success }.let { (it as UiState.Success).data }
    }

    private class FakePassageRepo : PassageRepository(mockk(relaxed = true)) {
        private val _passages = mutableMapOf<String, List<Passage>>()
        private val passageFlows = mutableMapOf<String, MutableStateFlow<List<Passage>>>()
        var shouldFail = false
        var searchResults = emptyList<Passage>()
        val createCalls = mutableListOf<String>()

        fun setPassages(agentId: String, passages: List<Passage>) {
            _passages[agentId] = passages
            flowForAgent(agentId).value = passages
        }

        override fun getPassages(agentId: String): StateFlow<List<Passage>> {
            return flowForAgent(agentId)
        }
        override suspend fun refreshPassages(agentId: String) {
            if (shouldFail) throw Exception("Failed")
            flowForAgent(agentId).value = _passages[agentId].orEmpty()
        }
        override suspend fun createPassage(agentId: String, text: String): Passage {
            createCalls.add("$agentId:$text")
            val p = Passage(id = "new-${createCalls.size}", text = text)
            setPassages(agentId, _passages[agentId].orEmpty() + p)
            return p
        }
        override suspend fun deletePassage(agentId: String, passageId: String) {
            setPassages(agentId, _passages[agentId].orEmpty().filter { it.id != passageId })
        }
        override suspend fun searchArchival(agentId: String, query: String): List<Passage> {
            return searchResults
        }

        private fun flowForAgent(agentId: String): MutableStateFlow<List<Passage>> {
            return passageFlows.getOrPut(agentId) {
                MutableStateFlow(_passages[agentId].orEmpty())
            }
        }
    }
}
