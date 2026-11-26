package com.letta.mobile.util.flight

import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecorder
import com.letta.mobile.util.attr
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Where the flight record keeps its lines, oldest first, within a fixed byte ceiling: once full, the oldest lines go. On
 * Android and desktop it is a pair of files that outlive the process; where there is no file system (the browser) it is
 * [InMemoryFlightRecordStore], which ends with the page.
 */
interface FlightRecordStore {
    /** Appends [lines] in order. Blocking I/O is allowed: only the flight record's own writer calls it, never a caller of `Telemetry`. */
    fun append(lines: List<String>)

    /** Every line kept, oldest first. */
    fun read(): List<String>
}

/** A [FlightRecordStore] in memory: at most [maxBytes] of lines (UTF-8), evicting the oldest. */
class InMemoryFlightRecordStore(private val maxBytes: Long = DEFAULT_MAX_BYTES) : FlightRecordStore {
    private val lock = kotlinx.atomicfu.locks.SynchronizedObject()
    private val lines = ArrayDeque<String>()
    private var bytes = 0L

    override fun append(lines: List<String>) = kotlinx.atomicfu.locks.synchronized(lock) {
        lines.forEach { line ->
            this.lines.addLast(line)
            bytes += sizeOf(line)
        }
        while (bytes > maxBytes && this.lines.isNotEmpty()) bytes -= sizeOf(this.lines.removeFirst())
    }

    override fun read(): List<String> = kotlinx.atomicfu.locks.synchronized(lock) { lines.toList() }

    private fun sizeOf(line: String): Long = line.encodeToByteArray().size + 1L

    private companion object {
        const val DEFAULT_MAX_BYTES = 256L * 1024
    }
}

/**
 * Writes the telemetry events [FlightRecordPolicy] keeps to [store], off the caller's thread. [offer] only filters and hands
 * the event to a bounded queue (the oldest waiting event is dropped when it is full); a coroutine in [scope] waits
 * [flushDelayMs] after the first waiting event, then rate-limits, formats and appends everything waiting in one write. A
 * process killed between two writes loses at most [flushDelayMs] of events.
 */
internal class FlightRecorder(
    private val store: FlightRecordStore,
    scope: CoroutineScope,
    private val flushDelayMs: Long = FLUSH_DELAY_MS,
) {
    private val waiting = Channel<Telemetry.Event>(QUEUE_CAPACITY, BufferOverflow.DROP_OLDEST)
    private val limiter = FlightRateLimiter()

    init {
        scope.launch { drain() }
    }

    /** Called inside every `Telemetry.event`: a map lookup and a non-blocking queue offer, nothing else. */
    fun offer(event: Telemetry.Event) {
        if (FlightRecordPolicy.admits(event)) waiting.trySend(event)
    }

    /** Stops taking events; what is already waiting is still written. */
    fun close() {
        waiting.close()
    }

    private suspend fun drain() {
        for (first in waiting) {
            delay(flushDelayMs)
            val batch = buildList {
                add(first)
                while (true) add(waiting.tryReceive().getOrNull() ?: break)
            }
            val lines = batch.mapNotNull { event -> limiter.admit(event)?.let { FlightRecordPolicy.lineFor(event, it) } }
            if (lines.isNotEmpty()) store.append(lines)
        }
    }

    private companion object {
        const val FLUSH_DELAY_MS = 1_000L
        const val QUEUE_CAPACITY = 512
    }
}

/**
 * The client's flight record: a small, bounded record of its own connection, turn and feed telemetry that outlives logcat
 * (and, on Android and desktop, the process), so a report of a "wedged" conversation can be read from the client's side
 * after the fact. It keeps ids, seqs, states, counts and timings only ([FlightRecordPolicy]); each host installs it once
 * beside its [TelemetryRecorder].
 */
object FlightRecording {
    /** The tag of the record's own events (the process start marker). */
    const val TAG: String = "FlightRecord"

    @Volatile
    private var installed: FlightRecordStore? = null

    /**
     * Starts recording what [recorder] records into [store], writing from [scope], and marks the start with [build] (the app's
     * version or host name). Closing the result stops recording; lines already waiting are still written.
     */
    fun install(recorder: TelemetryRecorder, store: FlightRecordStore, scope: CoroutineScope, build: String): AutoCloseable {
        val flight = FlightRecorder(store, scope)
        recorder.onRecorded = flight::offer
        installed = store
        Telemetry.event(TAG, "process.start", "build" attr build)
        return AutoCloseable {
            recorder.onRecorded = null
            flight.close()
        }
    }

    /** [install] into an [InMemoryFlightRecordStore], for a host with no file system (the browser): the record ends with it. */
    fun installInMemory(recorder: TelemetryRecorder, build: String): AutoCloseable =
        install(recorder, InMemoryFlightRecordStore(), CoroutineScope(SupervisorJob() + Dispatchers.Default), build)

    /** The record's lines, oldest first; empty when nothing is installed. */
    fun read(): List<String> = installed?.read().orEmpty()
}
