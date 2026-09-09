package com.letta.mobile.data.timeline

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TimelineLegacyAdmissionTest {
    @Test fun closeRejectsNewWorkAndWaitsForAcceptedWork() = runTest {
        val gate = TimelineLegacyAdmission()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operation = async { gate.admitted("conversation") { entered.complete(Unit); release.await() } }
        entered.await()
        val drain = async { gate.close("conversation") }
        yield()
        assertFalse(drain.isCompleted)
        assertFailsWith<IllegalStateException> { gate.admitted("conversation") { error("Must not enter") } }
        assertTrue(gate.admitted("other") { true })
        release.complete(Unit)
        operation.await()
        drain.await()
        assertFailsWith<IllegalStateException> { gate.admitted("conversation") {} }
    }

    @Test fun cancelledDrainDoesNotReopenAdmission() = runTest {
        val gate = TimelineLegacyAdmission()
        val entered = CompletableDeferred<Unit>()
        val operation = async { gate.admitted("conversation") { entered.complete(Unit); CompletableDeferred<Unit>().await() } }
        entered.await()
        val drain = async { gate.close("conversation") }
        yield()
        drain.cancel()
        operation.cancel()
        operation.join()
        gate.close("conversation")
        assertFailsWith<IllegalStateException> { gate.admitted("conversation") {} }
    }
}
