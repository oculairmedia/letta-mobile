package com.letta.mobile.data.transport

import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import org.junit.jupiter.api.Tag
import kotlin.concurrent.thread

/**
 * letta-mobile-2rkdj — defends the per-conv `{runId -> lastSeq}` map
 * semantics that drive resume-after-disconnect. Exercises only the
 * in-memory implementation; the Preferences-DataStore mirror is
 * verified by its own integration test (Phase 2).
 */
@Tag("unit")
class RunCursorStoreTest : WordSpec({

    "record" should {
        "store the cursor for a new (conv, run) pair" {
            val store = RunCursorStore.inMemory()
            store.record("conv-a", "run-1", 5L)
            store.activeRuns("conv-a") shouldContainExactly mapOf("run-1" to 5L)
        }

        "advance the cursor when seq is higher" {
            val store = RunCursorStore.inMemory()
            store.record("conv-a", "run-1", 5L)
            store.record("conv-a", "run-1", 12L)
            store.activeRuns("conv-a") shouldContainExactly mapOf("run-1" to 12L)
        }

        "ignore lower or equal seq (replayed-frame guard)" {
            val store = RunCursorStore.inMemory()
            store.record("conv-a", "run-1", 12L)
            store.record("conv-a", "run-1", 5L)
            store.record("conv-a", "run-1", 12L)
            store.activeRuns("conv-a") shouldContainExactly mapOf("run-1" to 12L)
        }

        "keep the highest cursor under concurrent writes" {
            val store = RunCursorStore.inMemory()
            val ready = CountDownLatch(20)
            val start = CountDownLatch(1)
            val writers = (1..20).map { seq ->
                thread(start = true) {
                    ready.countDown()
                    start.await()
                    store.record("conv-a", "run-1", seq.toLong())
                }
            }

            ready.await()
            start.countDown()
            writers.forEach { it.join() }

            store.activeRuns("conv-a") shouldContainExactly mapOf("run-1" to 20L)
        }

        "drop empty/zero arguments without throwing (defense in depth)" {
            val store = RunCursorStore.inMemory()
            store.record("", "run-1", 5L)
            store.record("conv-a", "", 5L)
            store.record("conv-a", "run-1", 0L)
            store.record("conv-a", "run-1", -1L)
            store.allActiveRuns().shouldBeEmpty()
        }
    }

    "clear" should {
        "drop the cursor for the given (conv, run)" {
            val store = RunCursorStore.inMemory()
            store.record("conv-a", "run-1", 5L)
            store.record("conv-a", "run-2", 7L)
            store.clear("conv-a", "run-1")
            store.activeRuns("conv-a") shouldContainExactly mapOf("run-2" to 7L)
        }

        "drop the conversation entry entirely when its last run is cleared" {
            val store = RunCursorStore.inMemory()
            store.record("conv-a", "run-1", 5L)
            store.clear("conv-a", "run-1")
            store.allActiveRuns().shouldBeEmpty()
        }

        "no-op when the (conv, run) is not present" {
            val store = RunCursorStore.inMemory()
            store.clear("conv-a", "run-1")
            store.allActiveRuns().shouldBeEmpty()
        }
    }

    "allActiveRuns" should {
        "snapshot every (conv, run, seq) tuple in flight" {
            val store = RunCursorStore.inMemory()
            store.record("conv-a", "run-1", 5L)
            store.record("conv-a", "run-2", 7L)
            store.record("conv-b", "run-3", 11L)
            val snapshot = store.allActiveRuns()
            snapshot.keys shouldContainExactlyInAnyOrder listOf("conv-a", "conv-b")
            snapshot["conv-a"]!! shouldContainExactly mapOf("run-1" to 5L, "run-2" to 7L)
            snapshot["conv-b"]!! shouldContainExactly mapOf("run-3" to 11L)
        }

        "return an empty map when nothing is in flight" {
            val store = RunCursorStore.inMemory()
            store.allActiveRuns().shouldBeEmpty()
        }
    }

    "activeRuns(conversationId)" should {
        "isolate per-conversation snapshots" {
            val store = RunCursorStore.inMemory()
            store.record("conv-a", "run-1", 5L)
            store.record("conv-b", "run-2", 7L)
            store.activeRuns("conv-a") shouldContainExactly mapOf("run-1" to 5L)
            store.activeRuns("conv-b") shouldContainExactly mapOf("run-2" to 7L)
            store.activeRuns("conv-c").shouldBeEmpty()
        }

        "return a defensive copy — caller mutations don't leak back" {
            val store = RunCursorStore.inMemory()
            store.record("conv-a", "run-1", 5L)
            val snapshot = store.activeRuns("conv-a").toMutableMap()
            snapshot["run-1"] = 999L
            snapshot["run-X"] = 1L
            // Original store unaffected.
            store.activeRuns("conv-a") shouldContainExactly mapOf("run-1" to 5L)
        }
    }

    "ensureLoaded" should {
        "be a no-op on the in-memory implementation" {
            val store = RunCursorStore.inMemory()
            // Multiple calls must not throw and must not reset state.
            store.record("conv-a", "run-1", 5L)
            store.ensureLoaded()
            store.ensureLoaded()
            store.activeRuns("conv-a") shouldContainExactly mapOf("run-1" to 5L)
        }
    }
})
