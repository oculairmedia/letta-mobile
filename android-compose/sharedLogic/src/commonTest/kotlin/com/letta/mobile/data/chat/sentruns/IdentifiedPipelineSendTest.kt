package com.letta.mobile.data.chat.sentruns

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IdentifiedPipelineSendTest {
    @Test
    fun aFailureBeforeAcceptanceReachesTheCallerAndLeavesThePipelineScopeRunning() = runTest {
        val scope = CoroutineScope(Job() + UnconfinedTestDispatcher(testScheduler))
        val pipeline = IdentifiedPipelineSend(scope, Mutex(), { "otid-1" }, {}, { null }) { _, _ ->
            error("refused before acceptance")
        }
        val send = pipeline.binding(SentRunLedger())

        val failure = assertFailsWith<IllegalStateException> { send.sendIdentified("hello") }
        assertEquals("refused before acceptance", failure.message)
        assertTrue(scope.isActive)
    }
}
