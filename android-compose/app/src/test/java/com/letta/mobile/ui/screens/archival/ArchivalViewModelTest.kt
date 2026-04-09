package com.letta.mobile.ui.screens.archival

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.letta.mobile.data.model.Passage
import com.letta.mobile.data.repository.PassageRepository
import com.letta.mobile.ui.common.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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
        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertEquals(2, state.data.passages.size)
        }
    }

    @Test
    fun `addPassage refreshes list`() = runTest {
        fakeRepo.setPassages("a1", emptyList())
        viewModel.loadPassages()
        viewModel.addPassage("New passage text")
        assertTrue(fakeRepo.createCalls.contains("a1:New passage text"))
    }

    @Test
    fun `deletePassage removes from list`() = runTest {
        fakeRepo.setPassages("a1", listOf(Passage(id = "p1", text = "Old")))
        viewModel.loadPassages()
        viewModel.deletePassage("p1")
        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertTrue(state.data.passages.none { it.id == "p1" })
        }
    }

    @Test
    fun `search updates passages with results`() = runTest {
        fakeRepo.setPassages("a1", listOf(Passage(id = "p1", text = "Kotlin is great")))
        fakeRepo.searchResults = listOf(Passage(id = "p1", text = "Kotlin is great"))
        viewModel.loadPassages()
        viewModel.search("Kotlin")
        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertEquals("Kotlin", state.data.searchQuery)
        }
    }

    @Test
    fun `inspectPassage stores selected passage`() = runTest {
        val passage = Passage(id = "p1", text = "Some knowledge", sourceId = "source-1")
        fakeRepo.setPassages("a1", listOf(passage))
        viewModel.loadPassages()

        viewModel.inspectPassage(passage)

        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertEquals("p1", state.data.selectedPassage?.id)
        }
    }

    @Test
    fun `clearSelectedPassage clears selected passage`() = runTest {
        val passage = Passage(id = "p1", text = "Some knowledge", sourceId = "source-1")
        fakeRepo.setPassages("a1", listOf(passage))
        viewModel.loadPassages()
        viewModel.inspectPassage(passage)

        viewModel.clearSelectedPassage()

        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertEquals(null, state.data.selectedPassage)
        }
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
        viewModel.uiState.test { assertTrue(awaitItem() is UiState.Error) }
    }

    private class FakePassageRepo : PassageRepository(null!!) {
        private val _passages = mutableMapOf<String, List<Passage>>()
        var shouldFail = false
        var searchResults = emptyList<Passage>()
        val createCalls = mutableListOf<String>()

        fun setPassages(agentId: String, passages: List<Passage>) { _passages[agentId] = passages }

        override fun getPassages(agentId: String): StateFlow<List<Passage>> {
            return MutableStateFlow(_passages[agentId] ?: emptyList())
        }
        override suspend fun refreshPassages(agentId: String) {
            if (shouldFail) throw Exception("Failed")
        }
        override suspend fun createPassage(agentId: String, text: String): Passage {
            createCalls.add("$agentId:$text")
            val p = Passage(id = "new-${createCalls.size}", text = text)
            _passages[agentId] = (_passages[agentId] ?: emptyList()) + p
            return p
        }
        override suspend fun deletePassage(agentId: String, passageId: String) {
            _passages[agentId] = (_passages[agentId] ?: emptyList()).filter { it.id != passageId }
        }
        override suspend fun searchArchival(agentId: String, query: String): List<Passage> {
            return searchResults
        }
    }
}
