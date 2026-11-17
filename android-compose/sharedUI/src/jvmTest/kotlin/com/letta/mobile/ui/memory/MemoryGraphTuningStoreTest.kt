@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.memory

import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.graph.MemoryGraphTuning
import java.nio.file.Files
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The dev tuning file survives a relaunch, and a missing one is no tuning. */
class MemoryGraphTuningStoreTest {
    @Test
    fun typedKnobsRejectNonFiniteAndOutOfRangeValues() = runDesktopComposeUiTest {
        val changes = mutableListOf<MemoryGraphTuning>()
        setContent {
            androidx.compose.material3.MaterialTheme {
                MemoryGraphTuningPanel(MemoryGraphTuning(), MemoryGraphTuningActions(changes::add, {}, {}, {}))
            }
        }
        val field = onAllNodes(hasSetTextAction())[0]
        listOf("NaN", "Infinity", "-1", "20001").forEach { value ->
            field.performTextReplacement(value)
            runOnIdle { assertEquals(emptyList(), changes, value) }
        }
        field.performTextReplacement("6400")
        runOnIdle { assertEquals(6400f, changes.single().repulsion) }
    }

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
