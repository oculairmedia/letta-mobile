package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-dzt3g: the owner's phone showed one streaming reply as the concatenation of its own
 * cumulative snapshots ("ThatThat pointsThat points at..."), starting at the very first frames. Each
 * test streams S1..Sn, every Sk a longer prefix of one reply starting at 4 characters, as raw wire
 * lines through the phone's observer path, and requires one row equal to Sn on every frame.
 * The variants differ only in what identity the frames carry.
 */
class CumulativeSnapshotStackingTest {
    @Test fun snapshotsWithAStableMessageIdStayOneRow() = assertSnapshotsStayOneRow { index, text ->
        RawDelta(index, "assistant_message", text, id = "ui-msg-9184646", runId = "local-run-803")
    }

    @Test fun snapshotsWithNoIdentityStayOneRow() = assertSnapshotsStayOneRow { index, text ->
        RawDelta(index, "assistant_message", text)
    }

    @Test fun snapshotsWithOneOtidAndRotatingIdsStayOneRow() = assertSnapshotsStayOneRow { index, text ->
        RawDelta(index, "assistant_message", text, id = "letta-msg-$index", otid = "otid-reply", runId = "run-1")
    }

    @Test fun stampedSnapshotsStayOneRow() = assertSnapshotsStayOneRow { index, text ->
        RawDelta(index, "assistant_message", text, id = "ui-msg-1", stamp = "lm-1" to index)
    }

    @Test fun sampledStampedSnapshotsWithSkippedFramesStayOneRow() = assertSnapshotsStayOneRow(every = 3) { index, text ->
        RawDelta(index, "assistant_message", text, id = "ui-msg-1", stamp = "lm-1" to index)
    }

    private fun assertSnapshotsStayOneRow(every: Int = 1, delta: (Int, String) -> RawDelta) = runBlocking {
        val snapshots = REPLY.indices.filter { it >= 3 && (it - 3) % every == 0 }.map { REPLY.take(it + 1) }
        val recorder = TimelineFrameRecorder.open(scope)
        try {
            recorder.streamRaw(snapshots.mapIndexed { index, text -> RawWireFrames.line(delta(index + 1, text)) })
            val frames = recorder.frames
            assertEquals(emptyList(), timelineInvariantViolations(frames), frames.joinToString("\n"))
            assertEquals(listOf(snapshots.last()), frames.last().rows.flatMap { it.contents })
        } finally {
            recorder.close()
        }
    }

    private companion object {
        const val REPLY = "That points at the live stream path, not storage, since the stored thread is correct."
        val scope = TimelineScope("backend", RawWireFrames.CONVERSATION, RawWireFrames.AGENT)
    }
}
