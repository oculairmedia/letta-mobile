package com.letta.mobile.data.repository

import com.letta.mobile.testutil.FakeBlockApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BlockRepositoryTest {

    private lateinit var fakeApi: FakeBlockApi
    private lateinit var repository: BlockRepository

    @Before
    fun setup() {
        fakeApi = FakeBlockApi()
        repository = BlockRepository(fakeApi)
    }

    @Test
    fun `updateBlock calls API with correct params`() = runTest {
        repository.updateBlock("a1", "persona", "New persona value")
        assertTrue(fakeApi.calls.contains("updateBlock:a1:persona"))
    }

    @Test
    fun `updateBlock returns updated block`() = runTest {
        val result = repository.updateBlock("a1", "human", "Updated human block")
        assertEquals("human", result.label)
        assertEquals("Updated human block", result.value)
    }

    @Test(expected = com.letta.mobile.data.api.ApiException::class)
    fun `updateBlock throws on API failure`() = runTest {
        fakeApi.shouldFail = true
        repository.updateBlock("a1", "persona", "value")
    }
}
