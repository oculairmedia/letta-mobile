package com.letta.mobile.util

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class TelemetryLifecycleTest {
    @Test
    fun beforeInstallationAndAfterDetachLoggingContinuesWithoutHistory() {
        val output = ByteArrayOutputStream()
        val originalOut = System.out
        val delegate = Telemetry.delegate
        val logcat = Telemetry.logcatEnabled.get()
        val enabled = Telemetry.enabled.get()
        val recorder = TelemetryRecorder()
        val installation = Telemetry.installRecorder(recorder)
        installation.close()
        try {
            Telemetry.delegate = null
            Telemetry.enabled.set(true)
            Telemetry.logcatEnabled.set(true)
            System.setOut(PrintStream(output))
            Telemetry.event("Lifecycle", "before-info")
            Telemetry.event("Lifecycle", "before-debug", level = Telemetry.Level.DEBUG)
            assertTrue(Telemetry.snapshot().isEmpty())
            Telemetry.installRecorder(recorder).use {
                Telemetry.event("Lifecycle", "installed")
                assertEquals(listOf("installed"), recorder.snapshot().map { it.name })
            }
            Telemetry.event("Lifecycle", "after-info")
            assertTrue(Telemetry.snapshot().isEmpty())
            assertEquals(listOf("installed"), recorder.snapshot().map { it.name })
            assertTrue(output.toString().contains("before-info"))
            assertTrue(output.toString().contains("after-info"))
            assertFalse(output.toString().contains("before-debug"))
        } finally {
            installation.close()
            System.setOut(originalOut)
            Telemetry.delegate = delegate
            Telemetry.enabled.set(enabled)
            Telemetry.logcatEnabled.set(logcat)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun existingCollectorsFollowReplacementAndObsoleteCloseCannotDetachNewOwner() = runTest {
        val first = TelemetryRecorder()
        val second = TelemetryRecorder()
        val observed = mutableListOf<List<String>>()
        val firstInstallation = Telemetry.installRecorder(first)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            Telemetry.events.collect { events -> observed += events.map { it.name } }
        }
        try {
            first.record(event("first"))
            runCurrent()
            Telemetry.installRecorder(second).use {
                runCurrent()
                firstInstallation.close()
                second.record(event("second"))
                runCurrent()
                assertEquals(listOf("second"), Telemetry.snapshot().map { it.name })
                first.record(event("abandoned"))
                runCurrent()
                assertEquals(listOf("second"), observed.last())
            }
            runCurrent()
            assertTrue(observed.last().isEmpty())
            assertTrue(observed.contains(listOf("first")))
            assertTrue(observed.contains(listOf("second")))
        } finally {
            firstInstallation.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun slowCollectorsReceiveLatestOwnerWithoutQueuedAbandonedHistory() = runTest {
        val binding = TelemetryHistoryBinding()
        val blocked = CompletableDeferred<Unit>()
        val observed = mutableListOf<List<String>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            binding.collect { events ->
                observed += events.map { it.name }
                if (observed.size == 1) blocked.await()
            }
        }
        runCurrent()
        assertEquals(listOf(emptyList()), observed)
        val first = TelemetryRecorder()
        binding.install(first).use {
            first.record(event("abandoned"))
            runCurrent()
            val second = TelemetryRecorder()
            binding.install(second).use {
                second.record(event("current"))
                runCurrent()
                blocked.complete(Unit)
                runCurrent()
                assertEquals(listOf(emptyList(), listOf("current")), observed)
            }
        }
    }

    @Test
    fun concurrentRoutedWritersCannotMutateHistoryAfterCloseReturns() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            repeat(25) { round ->
                val binding = TelemetryHistoryBinding()
                val recorder = TelemetryRecorder()
                val installation = binding.install(recorder)
                val entered = CountDownLatch(4)
                val closed = AtomicBoolean(false)
                val writers = (0 until 4).map { writer ->
                    executor.submit {
                        binding.record(event("$round:$writer:first"))
                        entered.countDown()
                        var sequence = 0
                        while (!closed.get()) binding.record(event("$round:$writer:${sequence++}"))
                        repeat(200) { binding.record(event("$round:$writer:after-close:$it")) }
                    }
                }
                try {
                    assertTrue(entered.await(10, TimeUnit.SECONDS))
                    installation.close()
                    val detached = recorder.snapshot()
                    closed.set(true)
                    writers.forEach { it.get(10, TimeUnit.SECONDS) }
                    assertEquals(detached, recorder.snapshot())
                    assertTrue(binding.value.isEmpty())
                } finally {
                    closed.set(true)
                    installation.close()
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun reinstallingTheSameRecorderStillCreatesDistinctOwnership() {
        val binding = TelemetryHistoryBinding()
        val recorder = TelemetryRecorder()
        val obsolete = binding.install(recorder)
        binding.install(recorder).use {
            obsolete.close()
            binding.record(event("current-owner"))
            assertEquals(listOf("current-owner"), recorder.snapshot().map { it.name })
        }
    }

    private fun event(name: String) = Telemetry.Event(0, "Lifecycle", name, null, emptyMap(), Telemetry.Level.INFO)
}
