package com.letta.mobile.feature.chat.coordination

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPipelineLifetimeTest {
    @Test fun legacyDrainCompletesBeforeReplacementAndDoesNotCancelParent() = runTest {
        val lifetime = ChatPipelineLifetime(this)
        val events = mutableListOf<String>()
        val started = CompletableDeferred<Unit>()
        lifetime.scope.launch {
            try { started.complete(Unit); kotlinx.coroutines.awaitCancellation() }
            finally { events += "legacy-closed" }
        }
        started.await()
        lifetime.retire()
        events += "canonical-created"
        assertEquals(listOf("legacy-closed", "canonical-created"), events)
        assertTrue(coroutineContext[Job]!!.isActive)
        lifetime.retire()
    }
}
