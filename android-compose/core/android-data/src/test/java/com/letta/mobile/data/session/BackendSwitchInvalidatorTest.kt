package com.letta.mobile.data.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("unit")
class BackendSwitchInvalidatorTest {

    private class StubCache(private val onClear: suspend () -> Unit) : BackendScopedCache {
        var clearCount = 0
            private set

        override suspend fun clearForBackendSwitch() {
            clearCount++
            onClear()
        }
    }

    @Test
    fun `clearAll returns all-success when every cache clears`() = runTest {
        val a = StubCache {}
        val b = StubCache {}
        val invalidator = BackendSwitchInvalidator(setOf(a, b))

        val result = invalidator.clearAll()

        assertEquals(2, result.successes)
        assertTrue(result.failures.isEmpty())
        assertTrue(result.allSucceeded)
        assertEquals(2, result.totalAttempted)
        assertEquals(1, a.clearCount)
        assertEquals(1, b.clearCount)
    }

    @Test
    fun `clearAll keeps clearing remaining caches when one fails`() = runTest {
        val good = StubCache {}
        val bad = StubCache { throw IllegalStateException("dao boom") }
        val alsoGood = StubCache {}
        val invalidator = BackendSwitchInvalidator(setOf(good, bad, alsoGood))

        val result = invalidator.clearAll()

        assertEquals(2, result.successes)
        assertEquals(1, result.failures.size)
        assertFalse(result.allSucceeded)
        assertEquals("StubCache", result.failures[0].cacheName)
        assertTrue(result.failures[0].error is IllegalStateException)
        // Every cache was attempted independently.
        assertEquals(1, good.clearCount)
        assertEquals(1, bad.clearCount)
        assertEquals(1, alsoGood.clearCount)
    }

    @Test
    fun `clearAll aggregates multiple failures`() = runTest {
        val invalidator = BackendSwitchInvalidator(
            setOf(
                StubCache { throw IllegalStateException("a") },
                StubCache { throw RuntimeException("b") },
            ),
        )

        val result = invalidator.clearAll()

        assertEquals(0, result.successes)
        assertEquals(2, result.failures.size)
        assertFalse(result.allSucceeded)
    }
}
