package com.letta.mobile.domain

import com.letta.mobile.testutil.FakeToolApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class ClientToolSyncTest {

    @Test
    fun `syncTools attaches returned tool id`() = runTest {
        val fakeToolApi = FakeToolApi()
        val sync = ClientToolSync(fakeToolApi)

        sync.syncTools(agentId = "agent-1")

        assertTrue(fakeToolApi.calls.contains("upsertTool:get_device_info"))
        assertTrue(fakeToolApi.calls.contains("attachTool:agent-1:new-0"))
    }
}
