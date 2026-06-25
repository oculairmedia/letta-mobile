package com.letta.mobile.util

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineExtensionsTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    class TestViewModel : ViewModel() {
        fun launchAction(
            onError: ((Exception) -> Unit)? = null,
            block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit
        ) {
            safeLaunch(tag = "TestViewModel", onError = onError, block = block)
        }
    }

    @Test
    fun `safeLaunch executes block successfully`() = runTest(testDispatcher) {
        val viewModel = TestViewModel()
        var executed = false
        
        viewModel.launchAction {
            delay(10)
            executed = true
        }
        
        advanceUntilIdle()
        assertTrue(executed)
    }

    @Test
    fun `safeLaunch invokes onError when exception occurs`() = runTest(testDispatcher) {
        val viewModel = TestViewModel()
        var errorCaught: Exception? = null
        
        viewModel.launchAction(
            onError = { errorCaught = it }
        ) {
            delay(10)
            throw RuntimeException("Test Error")
        }
        
        advanceUntilIdle()
        assertTrue(errorCaught is RuntimeException)
        assertEquals("Test Error", errorCaught?.message)
    }

    @Test
    fun `safeLaunch rethrows CancellationException and does not call onError`() = runTest(testDispatcher) {
        val viewModel = TestViewModel()
        var errorCaught: Exception? = null
        
        viewModel.launchAction(
            onError = { errorCaught = it }
        ) {
            delay(10)
            throw CancellationException("Cancelled")
        }
        
        // Advance time to allow the coroutine to run
        advanceUntilIdle()
        // The child coroutine fails with CancellationException, which is standard for cancellation.
        // It should NOT have called onError.
        assertTrue(errorCaught == null)
    }
}
