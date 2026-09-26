package com.letta.mobile.feature.chat

import com.letta.mobile.feature.chat.screen.TimelineOpeningObservation

/**
 * S1 state recorder, NOT a display-frame or latency benchmark. The logical tick is injected by
 * fixture operations/Compose callbacks; duplicate commits/layouts are intentionally retained.
 * Selection and generation describe fixture boundaries, not canonical engine instrumentation.
 * Readiness requires the fixture's exact ordered visible window AND its resolved anchor/offset.
 * Expected keys use visibleItemsInfo iteration order, not physical top-to-bottom screen order.
 * The reverse-layout Compose fixture verifies this convention; synthetic layouts preserve it too.
 * Extra, duplicate, reordered, or interleaved visible rows are not the expected window. It does
 * not infer completeness from Paging emission, itemCount, elapsed time, or pagination exhaustion.
 * S2/S3 must supply real bounded-window/anchor readiness and validate GPU-visible atomic reveal.
 */
internal class TimelineOpeningRecorder(
    private val expectedKeys: List<String>,
    private val anchorKey: String?,
    private val anchorOffset: Int = 0,
) {
    enum class Milestone { Selection, PresentationOpen, FirstGeneration, ViewportReady, FirstContentCommitted, Empty, Failed }
    data class Entry(val tick: Int, val observation: TimelineOpeningObservation)
    val observations = mutableListOf<Entry>()
    val milestones = mutableListOf<Pair<Int, Milestone>>()
    private var tick = 0
    private var anchorResolved = false
    private var latestLayout: TimelineOpeningObservation.Layout? = null

    fun mark(milestone: Milestone) {
        if (milestones.none { it.second == milestone }) milestones += ++tick to milestone
    }

    fun resolveAnchor() {
        anchorResolved = true
        latestLayout?.let(::checkReady)
    }

    fun observe(observation: TimelineOpeningObservation) {
        observations += Entry(++tick, observation)
        when (observation) {
            is TimelineOpeningObservation.Committed -> when {
                observation.confirmedEmpty -> mark(Milestone.Empty)
                observation.surface == TimelineOpeningObservation.Surface.InitialFailed ||
                    observation.surface == TimelineOpeningObservation.Surface.OpenFailed -> mark(Milestone.Failed)
            }
            is TimelineOpeningObservation.Layout -> {
                latestLayout = observation
                checkReady(observation)
                // A measured transcript row is content already exposed by the current branch.
                // This milestone is deliberately allowed BEFORE readiness to pin the S1 defect.
                if (observation.rows.any { it.key in expectedKeys && visible(it, observation) }) {
                    mark(Milestone.FirstContentCommitted)
                }
            }
        }
    }

    private fun visible(row: TimelineOpeningObservation.VisibleRow, layout: TimelineOpeningObservation.Layout) =
        row.size > 0 && row.offset < layout.viewportEnd && row.offset + row.size > layout.viewportStart

    private fun hasExpectedWindow(visible: List<TimelineOpeningObservation.VisibleRow>): Boolean =
        expectedKeys.isNotEmpty() && visible.map { it.key } == expectedKeys

    private fun hasResolvedAnchor(visible: List<TimelineOpeningObservation.VisibleRow>): Boolean =
        anchorResolved && visible.any { it.key == anchorKey && it.offset == anchorOffset }

    private fun checkReady(layout: TimelineOpeningObservation.Layout) {
        val visible = layout.rows.filter { visible(it, layout) }
        if (!hasExpectedWindow(visible)) return
        if (!hasResolvedAnchor(visible)) return
        mark(Milestone.ViewportReady)
    }
}
