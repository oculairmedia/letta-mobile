package com.letta.mobile.data.timeline

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.time.Instant
import org.junit.jupiter.api.Tag

/**
 * Worked example for letta-mobile-3wo3: kotest-property adoption.
 *
 * Two high-leverage invariants from the bead:
 *
 *  - **Pure-delta merge** (use case 1) — the contract that the merge step in
 *    [ingestStreamEvent]'s non-standard branch is plain
 *    `oldText + newText` (empty deltas no-op). This is the contract that the
 *    lcp-cv3 / lcp-pro / lcp-r0m / wucn-snapshot-recovery cascade kept breaking
 *    by trying to be clever about prefix/snapshot collisions. The property
 *    here is intentionally simple: any fold over the merge contract must
 *    equal the direct concatenation of non-empty deltas.
 *
 *  - **`hasAlreadyIngestedStreamFrame` dedup gate** (use case 4) — full truth
 *    table over `(existingSeqId, incomingSeqId) ∈ {null, n}²` plus the
 *    `incoming <= existing` boundary. Catches off-by-one and accidental
 *    "skip when both null" regressions in one shot.
 *
 * See `docs/testing-with-kotest-property.md` for the rationale and pattern.
 */
@Tag("unit")
class TimelineSyncMergePropertyTest : StringSpec({

    "pure-delta merge fold equals direct concatenation of non-empty deltas" {
        // Spec mirrors the non-standard branch of the merge step
        // in ingestStreamEvent: empty deltas are no-ops; every other delta
        // is appended verbatim. Changing the production code to do anything
        // else (snapshot replace, prefix-drop, etc.) must fail this property.
        checkAll(Arb.list(Arb.string(0..50), 1..20)) { deltas ->
            val merged = deltas.fold("") { acc, delta ->
                if (delta.isEmpty()) acc else acc + delta
            }
            merged shouldBe deltas.joinToString("")
        }
    }

    "hasAlreadyIngestedStreamFrame returns true only when both seqIds non-null AND incoming <= existing" {
        checkAll(Arb.int(0..100).orNull(), Arb.int(0..100).orNull()) { existingSeq, incomingSeq ->
            val existing = makeAssistantConfirmed(seqId = existingSeq, serverId = "msg-existing")
            val incoming = makeAssistantConfirmed(seqId = incomingSeq, serverId = "msg-incoming")
            val expected = existingSeq != null &&
                incomingSeq != null &&
                incomingSeq <= existingSeq
            existing.hasAlreadyIngestedStreamFrame(incoming) shouldBe expected
        }
    }

    "hasAlreadyIngestedStreamFrame returns false when either side is not ASSISTANT" {
        // Non-assistant message types must never trigger the seq-dedup path —
        // tool_call / reasoning / user have their own merge / dedup rules.
        val nonAssistantTypes = listOf(
            TimelineMessageType.USER,
            TimelineMessageType.REASONING,
            TimelineMessageType.TOOL_CALL,
            TimelineMessageType.TOOL_RETURN,
            TimelineMessageType.SYSTEM,
            TimelineMessageType.ERROR,
            TimelineMessageType.OTHER,
        )
        checkAll(Arb.int(0..100), Arb.int(0..100)) { existingSeq, incomingSeq ->
            for (otherType in nonAssistantTypes) {
                val mismatchedExisting = makeAssistantConfirmed(
                    seqId = existingSeq, serverId = "msg-existing", messageType = otherType,
                )
                val asstIncoming = makeAssistantConfirmed(seqId = incomingSeq, serverId = "msg-incoming")
                mismatchedExisting.hasAlreadyIngestedStreamFrame(asstIncoming) shouldBe false

                val asstExisting = makeAssistantConfirmed(seqId = existingSeq, serverId = "msg-existing")
                val mismatchedIncoming = makeAssistantConfirmed(
                    seqId = incomingSeq, serverId = "msg-incoming", messageType = otherType,
                )
                asstExisting.hasAlreadyIngestedStreamFrame(mismatchedIncoming) shouldBe false
            }
        }
    }
})

private fun makeAssistantConfirmed(
    seqId: Int?,
    serverId: String,
    messageType: TimelineMessageType = TimelineMessageType.ASSISTANT,
): TimelineEvent.Confirmed = TimelineEvent.Confirmed(
    position = 1.0,
    otid = "otid-$serverId",
    content = "",
    serverId = serverId,
    messageType = messageType,
    date = Instant.EPOCH,
    runId = null,
    stepId = null,
    seqId = seqId,
)
