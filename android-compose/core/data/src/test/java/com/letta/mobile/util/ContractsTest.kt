package com.letta.mobile.util

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.ui.common.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import com.letta.mobile.testutil.TestData

class ContractsTest {

    @Test
    fun requireValidAgent_withValidAgent_returnsAgent() {
        val agent = TestData.agent(id = "valid-id")
        
        val result = requireValidAgent(agent)
        
        assertEquals(agent, result)
    }

    @Test
    fun requireValidAgent_withNullAgent_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException::class.java) {
            requireValidAgent(null)
        }
    }

    @Test
    fun requireValidAgent_withBlankAgentId_throwsIllegalArgumentException() {
        val agent = TestData.agent(id = "   ")
        
        assertThrows(IllegalArgumentException::class.java) {
            requireValidAgent(agent)
        }
    }

    @Test
    fun requireLoadedState_withSuccessState_returnsSuccessState() {
        val state = UiState.Success("data")
        
        val result = requireLoadedState(state)
        
        assertEquals(state, result)
    }

    @Test
    fun requireLoadedState_withLoadingState_throwsIllegalArgumentException() {
        val state = UiState.Loading
        
        assertThrows(IllegalArgumentException::class.java) {
            requireLoadedState(state)
        }
    }

    @Test
    fun requireLoadedState_withErrorState_throwsIllegalArgumentException() {
        val state = UiState.Error("error")
        
        assertThrows(IllegalArgumentException::class.java) {
            requireLoadedState(state)
        }
    }
}
