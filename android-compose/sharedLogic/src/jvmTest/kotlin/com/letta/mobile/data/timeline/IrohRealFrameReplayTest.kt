package com.letta.mobile.data.timeline

import java.io.File
import kotlin.test.Test

/**
 * letta-mobile-h30cy: FAITHFUL device-free repro. These are the REAL Iroh
 * assistant stream_delta frames captured from the live wrapper via
 * `app-server-iroh-probe --dump-frames` for a single reply ("I'm Lester ...").
 * They share ONE stable otid, carry rotating letta-msg ids, monotonically
 * increasing seq ids, INCREMENTAL one-token content, and the content is a
 * text-part JSON ARRAY ([{"type":"text","text":"..."}]) — the exact shape that
 * defeated every synthetic test. Replaying them through the REAL reduceStreamFrame
 * must yield exactly ONE assistant row with the full concatenated text.
 */
class IrohRealFrameReplayTest {
    @Test
    fun `real captured iroh fragments reduce to one assistant row`() {
        // Delegate to the shared FixtureReplayTest corpus runner
        val fixtureTest = FixtureReplayTest()
        val file = File("src/jvmTest/resources/timeline-fixtures/lester-reply.jsonl")
        fixtureTest.runFixture(file)
    }
}
