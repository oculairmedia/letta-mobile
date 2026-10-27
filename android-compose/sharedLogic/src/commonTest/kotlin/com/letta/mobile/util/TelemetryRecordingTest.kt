package com.letta.mobile.util

import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/** Installs a fresh history before subclass setup and detaches it after subclass cleanup. */
abstract class TelemetryRecordingTest {
    private var recording: AutoCloseable? = null

    @BeforeTest
    fun installTelemetryRecording() {
        recording = Telemetry.installRecorder(TelemetryRecorder())
    }

    @AfterTest
    fun detachTelemetryRecording() {
        recording?.close()
        recording = null
    }
}
