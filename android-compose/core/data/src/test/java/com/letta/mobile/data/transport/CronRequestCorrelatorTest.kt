package com.letta.mobile.data.transport

import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag

@Tag("unit")
class CronRequestCorrelatorTest : WordSpec({

    "newCronRequestId" should {
        "generate a fresh UUID-prefixed request_id" {
            val correlator = CronRequestCorrelator()
            val id1 = correlator.newCronRequestId()
            val id2 = correlator.newCronRequestId()
            
            id1 shouldStartWith "cron-"
            id2 shouldStartWith "cron-"
            (id1 == id2) shouldBe false
        }
    }

    "awaitCronResponse" should {
        "return ServerFrame on success" {
            val correlator = CronRequestCorrelator()
            val requestId = correlator.newCronRequestId()
            val expectedFrame = ServerFrame.CronDeleteResponse(
                id = "frame-1",
                ts = "ts",
                requestId = requestId,
                success = true
            )

            runBlocking {
                val deferred = async {
                    correlator.awaitCronResponse(
                        requestId = requestId,
                        sendFrame = { true },
                        timeoutMs = 1000L
                    )
                }

                // Simulate response arrival
                delay(50L)
                val completed = correlator.completeRequest(requestId, expectedFrame)
                completed shouldBe true

                val result = deferred.await()
                result shouldBe expectedFrame
            }
        }

        "throw IllegalStateException if sendFrame returns false" {
            val correlator = CronRequestCorrelator()
            val requestId = correlator.newCronRequestId()

            runBlocking {
                var threw = false
                try {
                    correlator.awaitCronResponse(
                        requestId = requestId,
                        sendFrame = { false },
                        timeoutMs = 1000L
                    )
                } catch (e: IllegalStateException) {
                    e.message shouldBe "Cron send failed: sendFrame returned false"
                    threw = true
                }
                threw shouldBe true
            }
        }

        "throw TimeoutCancellationException if timeoutMs elapses" {
            val correlator = CronRequestCorrelator()
            val requestId = correlator.newCronRequestId()

            runBlocking {
                var threw = false
                try {
                    correlator.awaitCronResponse(
                        requestId = requestId,
                        sendFrame = { true },
                        timeoutMs = 50L
                    )
                } catch (e: TimeoutCancellationException) {
                    threw = true
                }
                threw shouldBe true
            }
        }
    }

    "completeRequest" should {
        "return false if completing non-existent or already completed request" {
            val correlator = CronRequestCorrelator()
            val frame = ServerFrame.CronDeleteResponse(
                id = "frame-1",
                ts = "ts",
                requestId = "non-existent",
                success = true
            )

            correlator.completeRequest("non-existent", frame) shouldBe false
        }
    }

    "cancelPendingRequests" should {
        "cancel all currently pending cron requests with the specified reason" {
            val correlator = CronRequestCorrelator()
            val id1 = correlator.newCronRequestId()
            val id2 = correlator.newCronRequestId()

            runBlocking {
                val def1 = async {
                    try {
                        correlator.awaitCronResponse(id1, { true }, 1000L)
                        null
                    } catch (e: CancellationException) {
                        e.message
                    }
                }

                val def2 = async {
                    try {
                        correlator.awaitCronResponse(id2, { true }, 1000L)
                        null
                    } catch (e: CancellationException) {
                        e.message
                    }
                }

                delay(50L)
                correlator.cancelPendingRequests("connection closed")

                def1.await() shouldBe "connection closed"
                def2.await() shouldBe "connection closed"
            }
        }
    }
})
