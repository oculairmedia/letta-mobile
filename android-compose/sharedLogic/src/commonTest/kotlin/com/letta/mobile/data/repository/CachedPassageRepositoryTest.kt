package com.letta.mobile.data.repository

import app.cash.turbine.test
import com.letta.mobile.data.model.Passage
import com.letta.mobile.data.model.PassageCreateParams
import com.letta.mobile.data.repository.api.PassageRemoteSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CachedPassageRepositoryTest {

    @Test
    fun createPassageReturnsCommittedPassageWhenFollowUpListFails() = runTest {
        val remote = FakePassageRemoteSource()
        val repository = CachedPassageRepository(remote)

        repository.getPassages("agent-1").test {
            assertEquals(emptyList(), awaitItem())

            val created = repository.createPassage("agent-1", "Committed passage")

            assertEquals("Committed passage", created.text)
            assertEquals(listOf(created), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(listOf("createPassage", "listPassages"), remote.calls)
        assertTrue(remote.listShouldFail)
    }

    private class FakePassageRemoteSource : PassageRemoteSource {
        val calls = mutableListOf<String>()
        var listShouldFail = false

        override suspend fun listPassages(
            agentId: String,
            limit: Int?,
            after: String?,
            search: String?,
        ): List<Passage> {
            calls.add("listPassages")
            if (listShouldFail) error("list timed out")
            return emptyList()
        }

        override suspend fun createPassage(agentId: String, params: PassageCreateParams): Passage {
            calls.add("createPassage")
            listShouldFail = true
            return Passage(id = "passage-1", text = params.text, agentId = agentId)
        }

        override suspend fun deletePassage(agentId: String, passageId: String) {
            calls.add("deletePassage")
        }

        override suspend fun searchArchival(agentId: String, query: String, limit: Int?): List<Passage> =
            emptyList()
    }
}
