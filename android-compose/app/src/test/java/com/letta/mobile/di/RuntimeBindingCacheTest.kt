package com.letta.mobile.di

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.fail

class RuntimeBindingCacheTest {
    @Test fun concurrentCreationPublishesExactlyOneBinding() = runTest {
        val cache = RuntimeBindingCache<String, Any>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var creates = 0
        val first = async { cache.get("scope") { creates++; entered.complete(Unit); release.await(); Any() } }
        entered.await()
        val second = async { cache.get("scope") { creates++; Any() } }
        yield()
        assertFalse(second.isCompleted)
        release.complete(Unit)
        assertSame(first.await(), second.await())
        assertEquals(1, creates)
    }

    @Test fun retirementWaitsForCreationAndRejectsReacquisition() = runTest {
        val cache = RuntimeBindingCache<String, Any>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val binding = Any()
        val first = async { cache.get("scope") { entered.complete(Unit); release.await(); binding } }
        entered.await()
        var cleaned: Any? = null
        val retire = async { cache.close { cleaned = it.single() } }
        yield()
        assertFalse(retire.isCompleted)
        release.complete(Unit)
        first.await()
        retire.await()
        assertSame(binding, cleaned)
        try {
            cache.get("scope") { Any() }
            fail("Retired cache accepted reacquisition")
        } catch (_: IllegalStateException) {
            // Closed caches reject new bindings.
        }
        cache.close { error("Cleanup must run once") }
    }

    @Test fun closeAdmissionBeforeCancellingBlockedOperation() = runTest {
        val gate = com.letta.mobile.data.timeline.TimelineLegacyAdmission()
        val entered = CompletableDeferred<Unit>()
        val operation = async { gate.admitted("scope") { entered.complete(Unit); CompletableDeferred<Unit>().await() } }
        entered.await()
        val drain = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { gate.close("scope") }
        assertFalse(drain.isCompleted)
        try {
            gate.admitted("scope") {}
            fail("Closed admission accepted an operation")
        } catch (_: IllegalStateException) {
            // Closing admission rejects work before the drain completes.
        }
        operation.cancel()
        operation.join()
        drain.await()
    }

    @Test fun cancelledCreatorDoesNotPublishPartialBinding() = runTest {
        val cache = RuntimeBindingCache<String, Any>()
        val entered = CompletableDeferred<Unit>()
        val create = async { cache.get("scope") { entered.complete(Unit); CompletableDeferred<Any>().await() } }
        entered.await()
        create.cancel()
        create.join()
        val replacement = Any()
        assertSame(replacement, cache.get("scope") { replacement })
    }
}
