package com.letta.mobile.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionJankStatsMonitorTest {

    @Test
    fun `session sampler rejects zero and negative rates`() {
        assertFalse(JankSessionSampler.shouldSample(0.0, draw = 0.0))
        assertFalse(JankSessionSampler.shouldSample(-1.0, draw = 0.0))
    }

    @Test
    fun `session sampler accepts full rate and threshold draws`() {
        assertTrue(JankSessionSampler.shouldSample(1.0, draw = 0.999))
        assertTrue(JankSessionSampler.shouldSample(0.01, draw = 0.009))
        assertFalse(JankSessionSampler.shouldSample(0.01, draw = 0.01))
    }

    @Test
    fun `measurement recorder aggregates jank metrics and caps detailed frames`() {
        val recorder = JankMeasurementRecorder(frameBudgetMs = 16L, maxDetailedFrameMeasurements = 2)
        val sink = linkedMapOf<String, Double>()

        recorder.record(spanKey = "span-a", durationMs = 24L) { key, value -> sink[key] = value }

        assertEquals(1.0, sink["jank_frame_count"] ?: error("missing frame count"), 0.0)
        assertEquals(24.0, sink["jank_frame_max_ms"] ?: error("missing max"), 0.0)
        assertEquals(24.0, sink["jank_frame_total_ms"] ?: error("missing total"), 0.0)
        assertEquals(8.0, sink["jank_frame_over_budget_ms"] ?: error("missing over budget"), 0.0)
        assertEquals(24.0, sink["jank_frame_1_ms"] ?: error("missing first frame"), 0.0)

        sink.clear()
        recorder.record(spanKey = "span-a", durationMs = 40L) { key, value -> sink[key] = value }

        assertEquals(2.0, sink["jank_frame_count"] ?: error("missing frame count"), 0.0)
        assertEquals(40.0, sink["jank_frame_max_ms"] ?: error("missing max"), 0.0)
        assertEquals(64.0, sink["jank_frame_total_ms"] ?: error("missing total"), 0.0)
        assertEquals(32.0, sink["jank_frame_over_budget_ms"] ?: error("missing over budget"), 0.0)
        assertEquals(40.0, sink["jank_frame_2_ms"] ?: error("missing second frame"), 0.0)

        sink.clear()
        recorder.record(spanKey = "span-a", durationMs = 50L) { key, value -> sink[key] = value }

        assertEquals(3.0, sink["jank_frame_count"] ?: error("missing frame count"), 0.0)
        assertFalse(sink.containsKey("jank_frame_3_ms"))
    }

    @Test
    fun `measurement recorder resets when active span changes`() {
        val recorder = JankMeasurementRecorder(frameBudgetMs = 16L, maxDetailedFrameMeasurements = 3)
        val firstSink = linkedMapOf<String, Double>()
        val secondSink = linkedMapOf<String, Double>()

        recorder.record(spanKey = "span-a", durationMs = 30L) { key, value -> firstSink[key] = value }
        recorder.record(spanKey = "span-b", durationMs = 20L) { key, value -> secondSink[key] = value }

        assertEquals(1.0, secondSink["jank_frame_count"] ?: error("missing frame count"), 0.0)
        assertEquals(20.0, secondSink["jank_frame_total_ms"] ?: error("missing total"), 0.0)
        assertEquals(4.0, secondSink["jank_frame_over_budget_ms"] ?: error("missing over budget"), 0.0)
        assertEquals(20.0, secondSink["jank_frame_1_ms"] ?: error("missing first detailed frame"), 0.0)
    }
}
