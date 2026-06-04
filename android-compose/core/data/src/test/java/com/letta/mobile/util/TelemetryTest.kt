package com.letta.mobile.util

import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag

@Tag("unit")
class TelemetryTest : WordSpec({
    "Telemetry" should {
        "collect events when Logcat mirroring is disabled" {
            val wasEnabled = Telemetry.enabled.get()
            val wasLogcatEnabled = Telemetry.logcatEnabled.get()

            try {
                Telemetry.clear()
                Telemetry.enabled.set(true)
                Telemetry.logcatEnabled.set(false)

                Telemetry.event("Metrics", "sample", "count" to 1)

                val snapshot = Telemetry.snapshot()
                snapshot shouldHaveSize 1
                snapshot.first().tag shouldBe "Metrics"
                snapshot.first().name shouldBe "sample"
                snapshot.first().attrs shouldContain ("count" to 1)
            } finally {
                Telemetry.clear()
                Telemetry.enabled.set(wasEnabled)
                Telemetry.logcatEnabled.set(wasLogcatEnabled)
            }
        }

        "keep chat hot path diagnostics disabled by default" {
            Telemetry.chatHotPathDebugEnabled.get() shouldBe false
            Telemetry.isChatHotPathDebugEnabled() shouldBe false
        }

        "tolerate concurrent emitters and clear operations" {
            val wasEnabled = Telemetry.enabled.get()
            val wasLogcatEnabled = Telemetry.logcatEnabled.get()

            try {
                Telemetry.clear()
                Telemetry.enabled.set(true)
                Telemetry.logcatEnabled.set(false)

                runBlocking {
                    val emitters = (1..8).map { worker ->
                        async(Dispatchers.Default) {
                            repeat(250) { index ->
                                Telemetry.event("Concurrent", "sample", "worker" to worker, "index" to index)
                            }
                        }
                    }
                    val clearer = async(Dispatchers.Default) {
                        repeat(50) { Telemetry.clear() }
                    }
                    (emitters + clearer).awaitAll()
                }

                (Telemetry.snapshot().size <= 1000) shouldBe true
            } finally {
                Telemetry.clear()
                Telemetry.enabled.set(wasEnabled)
                Telemetry.logcatEnabled.set(wasLogcatEnabled)
            }
        }
    }
})
