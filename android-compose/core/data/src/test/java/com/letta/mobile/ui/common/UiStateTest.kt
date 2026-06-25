package com.letta.mobile.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStateTest {

    @Test
    fun `Loading is a singleton and properly instantiated`() {
        val state: UiState<String> = UiState.Loading
        assertTrue(state is UiState.Loading)
    }

    @Test
    fun `Success holds data correctly`() {
        val data = "test_data"
        val state = UiState.Success(data)
        
        assertEquals(data, state.data)
        assertTrue(state is UiState.Success)
    }

    @Test
    fun `Error holds message correctly`() {
        val errorMessage = "Something went wrong"
        val state = UiState.Error(errorMessage)
        
        assertEquals(errorMessage, state.message)
        assertTrue(state is UiState.Error)
    }

    @Test
    fun `Success equality works correctly`() {
        val state1 = UiState.Success(42)
        val state2 = UiState.Success(42)
        val state3 = UiState.Success(100)
        
        assertEquals(state1, state2)
        assertTrue(state1 != state3)
    }

    @Test
    fun `Error equality works correctly`() {
        val state1 = UiState.Error("error1")
        val state2 = UiState.Error("error1")
        val state3 = UiState.Error("error2")
        
        assertEquals(state1, state2)
        assertTrue(state1 != state3)
    }
}
