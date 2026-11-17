package com.letta.mobile.ui.memory

import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.graph.MemoryGraphTuning
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The dev tuning file survives a relaunch, and a missing one is no tuning. */
class MemoryGraphTuningStoreTest {
    @Test
    fun aSavedTuningLoadsBack() {
        val dir = Files.createTempDirectory("graph-tuning")
        val store = dir.resolve("dev/memory-graph-tuning.json")
        assertNull(MemoryGraphTuningStore.load(store))
        val tuned = MemoryGraphTuning(
            repulsion = 6400f,
            nodeCharge = MemoryGraphTuning().nodeCharge + (MemoryGraphNodeKind.Agent to 3f),
        )
        MemoryGraphTuningStore.save(store, tuned)
        assertEquals(tuned, MemoryGraphTuningStore.load(store))
    }

    @Test
    fun theKnobsReadAndWriteEveryValueTheyShow() {
        val tuning = MemoryGraphTuning()
        MemoryGraphKnobs.sections.flatMap { it.knobs }.forEach { knob ->
            val moved = knob.write(tuning, knob.range.endInclusive)
            assertEquals(knob.range.endInclusive, knob.read(moved), knob.label)
        }
    }
}
