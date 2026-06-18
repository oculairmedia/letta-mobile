package com.letta.mobile.data.transport

import android.util.Log
import com.letta.mobile.data.timeline.ConversationCursorStore
import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("unit")
class CursorResumeCoordinatorTest : WordSpec({

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    beforeSpec {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
    }

    afterSpec {
        unmockkAll()
    }

    "loadHelloResumeCursors" should {
        "load sorted cursors and clear local cache states" {
            val testScope = TestScope()
            val cursorStore = RunCursorStore.inMemory()
            val conversationCursorStore = mockk<ConversationCursorStore>()
            
            coEvery { conversationCursorStore.getAllCursors() } returns mapOf(
                "conv-b" to 20L,
                "conv-a" to 10L,
                "conv-c" to -1L
            )

            val coordinator = CursorResumeCoordinator(
                scope = testScope,
                cursorStore = cursorStore,
                conversationCursorStore = conversationCursorStore,
                json = json
            )

            val result = coordinator.loadHelloResumeCursors()

            result shouldBe listOf(
                ResumeCursor("conv-a", 10L),
                ResumeCursor("conv-b", 20L)
            )
        }
    }

    "register and track resumed runs" should {
        "register, remove and retrieve active resumed runs" {
            val testScope = TestScope()
            val cursorStore = RunCursorStore.inMemory()
            val conversationCursorStore = mockk<ConversationCursorStore>()

            val coordinator = CursorResumeCoordinator(
                scope = testScope,
                cursorStore = cursorStore,
                conversationCursorStore = conversationCursorStore,
                json = json
            )

            coordinator.registerResumedRun("run-1", "conv-1")
            coordinator.getResumedRunConversationId("run-1") shouldBe "conv-1"

            coordinator.removeResumedRun("run-1") shouldBe "conv-1"
            coordinator.getResumedRunConversationId("run-1") shouldBe null
        }
    }

    "recordCursorFromEnvelope" should {
        "parse valid JSON frames and record sequence cursor" {
            val testScope = TestScope()
            val cursorStore = RunCursorStore.inMemory()
            val conversationCursorStore = mockk<ConversationCursorStore>()

            val coordinator = CursorResumeCoordinator(
                scope = testScope,
                cursorStore = cursorStore,
                conversationCursorStore = conversationCursorStore,
                json = json
            )

            val activeConversationForRun: (String) -> String? = { runId ->
                if (runId == "run-1") "conv-1" else null
            }

            val text = """{"type":"assistant_message","run_id":"run-1","seq":10}"""
            coordinator.recordCursorFromEnvelope(text, activeConversationForRun)

            cursorStore.activeRuns("conv-1") shouldContainExactly mapOf("run-1" to 10L)
        }

        "skip record if frame type is in SKIP_CURSOR_TYPES" {
            val testScope = TestScope()
            val cursorStore = RunCursorStore.inMemory()
            val conversationCursorStore = mockk<ConversationCursorStore>()

            val coordinator = CursorResumeCoordinator(
                scope = testScope,
                cursorStore = cursorStore,
                conversationCursorStore = conversationCursorStore,
                json = json
            )

            val text = """{"type":"welcome","run_id":"run-1","seq":10}"""
            coordinator.recordCursorFromEnvelope(text) { "conv-1" }

            cursorStore.allActiveRuns().shouldBeEmpty()
        }
    }

    "recordHelloResumeReplayTelemetry" should {
        "track telemetry count when seq > afterSeq" {
            val testScope = TestScope()
            val cursorStore = RunCursorStore.inMemory()
            val conversationCursorStore = mockk<ConversationCursorStore>()

            coEvery { conversationCursorStore.getAllCursors() } returns mapOf("conv-1" to 10L)

            val coordinator = CursorResumeCoordinator(
                scope = testScope,
                cursorStore = cursorStore,
                conversationCursorStore = conversationCursorStore,
                json = json
            )

            coordinator.loadHelloResumeCursors()

            val mockFrame = ServerFrame.CronDeleteResponse(
                id = "f",
                ts = "ts",
                success = true
            )
            
            coordinator.recordHelloResumeReplayTelemetry(
                frame = mockFrame,
                text = """{"conversation_id":"conv-1","seq":15}""",
                conversationIdOrNull = { null },
                seqOrNull = { null }
            )
        }
    }

    "clearExpiredCursor" should {
        "clear cursor from store and trigger clearCursor in conversationCursorStore" {
            val testScope = TestScope()
            val cursorStore = RunCursorStore.inMemory()
            val conversationCursorStore = mockk<ConversationCursorStore>(relaxed = true)

            val coordinator = CursorResumeCoordinator(
                scope = testScope,
                cursorStore = cursorStore,
                conversationCursorStore = conversationCursorStore,
                json = json
            )

            cursorStore.record("conv-1", "run-1", 10L)
            coordinator.registerResumedRun("run-1", "conv-1")

            val expiredFrame = ServerFrame.Error(
                id = "err",
                ts = "ts",
                code = "cursor_expired",
                conversationId = "conv-1",
                runId = "run-1"
            )

            runTest {
                coordinator.clearExpiredCursor(expiredFrame) { "SimpleState" }
                testScope.testScheduler.advanceUntilIdle()

                cursorStore.allActiveRuns().shouldBeEmpty()
                coordinator.getResumedRunConversationId("run-1") shouldBe null
                coVerify { conversationCursorStore.clearCursor("conv-1") }
            }
        }
    }

    "resumeActiveRuns" should {
        "dispatch recovery subscribe calls for all active runs" {
            val testScope = TestScope()
            val cursorStore = RunCursorStore.inMemory()
            val conversationCursorStore = mockk<ConversationCursorStore>()

            val coordinator = CursorResumeCoordinator(
                scope = testScope,
                cursorStore = cursorStore,
                conversationCursorStore = conversationCursorStore,
                json = json
            )

            cursorStore.record("conv-1", "run-1", 10L)
            cursorStore.record("conv-1", "run-2", 20L)

            val subscribedRuns = mutableListOf<Pair<String, Long>>()
            coordinator.resumeActiveRuns(
                subscribeFn = { runId, lastSeq ->
                    subscribedRuns.add(runId to lastSeq)
                    true
                },
                stateValueSimpleName = { "SimpleState" }
            )

            subscribedRuns shouldContainExactlyInAnyOrder listOf(
                "run-1" to 10L,
                "run-2" to 20L
            )
        }

        "skip and clear cursors for known user-stopped runs" {
            val testScope = TestScope()
            val cursorStore = RunCursorStore.inMemory()
            val conversationCursorStore = mockk<ConversationCursorStore>()

            val coordinator = CursorResumeCoordinator(
                scope = testScope,
                cursorStore = cursorStore,
                conversationCursorStore = conversationCursorStore,
                json = json
            )

            cursorStore.record("conv-1", "run-stopped", 10L)
            cursorStore.record("conv-1", "run-active", 20L)
            coordinator.markRunUserStopped("conv-1", "run-stopped")

            val subscribedRuns = mutableListOf<Pair<String, Long>>()
            coordinator.resumeActiveRuns(
                subscribeFn = { runId, lastSeq ->
                    subscribedRuns.add(runId to lastSeq)
                    true
                },
                stateValueSimpleName = { "SimpleState" }
            )

            subscribedRuns shouldBe listOf("run-active" to 20L)
            cursorStore.allActiveRuns() shouldContainExactly mapOf("conv-1" to mapOf("run-active" to 20L))
        }

        "clear a user-cancelled run cursor before reconnect can resubscribe it" {
            val testScope = TestScope()
            val cursorStore = RunCursorStore.inMemory()
            val conversationCursorStore = mockk<ConversationCursorStore>()

            val coordinator = CursorResumeCoordinator(
                scope = testScope,
                cursorStore = cursorStore,
                conversationCursorStore = conversationCursorStore,
                json = json
            )

            cursorStore.record("conv-1", "run-cancelled", 42L)
            coordinator.markRunUserStopped("conv-1", "run-cancelled")

            val subscribedRuns = mutableListOf<Pair<String, Long>>()
            coordinator.resumeActiveRuns(
                subscribeFn = { runId, lastSeq ->
                    subscribedRuns.add(runId to lastSeq)
                    true
                },
                stateValueSimpleName = { "SimpleState" }
            )

            subscribedRuns.shouldBeEmpty()
            cursorStore.allActiveRuns().shouldBeEmpty()
        }
    }
})
