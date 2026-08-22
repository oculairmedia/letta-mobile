package com.letta.mobile.data.repository

import com.letta.mobile.data.repository.api.VibesyncEventStreamLogger
import com.letta.mobile.data.repository.api.VibesyncEventStreamSource
import com.letta.mobile.data.repository.api.VibesyncStreamEndpointUnavailableException
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CachedVibesyncEventStreamRepositoryTest {

    @Test
    fun stopCancelsHangWithoutUnavailableLogOrRetry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val source = HangingStreamSource()
        val logger = RecordingLogger()
        val repo = CachedVibesyncEventStreamRepository(
            streamSource = source,
            scope = scope,
            logger = logger,
        )

        repo.start()
        scope.runCurrent()
        assertEquals(1, source.openCount)

        repo.stop()
        scope.runCurrent()
        advanceTimeBy(60_000)
        scope.runCurrent()

        assertEquals(1, source.openCount)
        assertFalse(logger.messages.any { it.contains("vibesync event stream unavailable") })
    }

    @Test
    fun endpointUnavailableExitsWithoutRetry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val source = UnavailableStreamSource()
        val logger = RecordingLogger()
        val repo = CachedVibesyncEventStreamRepository(
            streamSource = source,
            scope = scope,
            logger = logger,
        )

        repo.start()
        scope.runCurrent()
        advanceTimeBy(60_000)
        scope.runCurrent()

        assertEquals(1, source.openCount)
        assertTrue(logger.messages.any { it.contains("not available on this backend") })
        assertFalse(logger.messages.any { it.contains("vibesync event stream unavailable") })
    }

    private class HangingStreamSource : VibesyncEventStreamSource {
        var openCount = 0
        override suspend fun openStream(): ByteReadChannel {
            openCount++
            awaitCancellation()
        }
    }

    private class UnavailableStreamSource : VibesyncEventStreamSource {
        var openCount = 0
        override suspend fun openStream(): ByteReadChannel {
            openCount++
            throw VibesyncStreamEndpointUnavailableException()
        }
    }

    private class RecordingLogger : VibesyncEventStreamLogger {
        val messages = mutableListOf<String>()
        override fun info(message: String) {
            messages += message
        }

        override fun info(message: String, error: Throwable) {
            messages += message
        }
    }
}
