package com.letta.mobile.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.letta.mobile.data.memory.MemoryGraphEdgeKind
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.graph.MemoryGraphTuning
import com.letta.mobile.ui.components.foundation.LettaSectionLabel
import com.letta.mobile.ui.theme.LettaDimens

/** What the tuning panel asks its host to do: apply a tuning, reset it, reheat the graph, copy it as Kotlin. */
internal class MemoryGraphTuningActions(
    val onChange: (MemoryGraphTuning) -> Unit,
    val onReset: () -> Unit,
    val onReheat: () -> Unit,
    val onCopy: () -> Unit,
)

/**
 * Every knob of the live memory graph simulation, a slider and a number field each, grouped by
 * section, with Reset to defaults, Reheat and Copy as Kotlin defaults. A dev tool: only the
 * desktop's graph lookdev window shows it.
 */
@Composable
internal fun MemoryGraphTuningPanel(tuning: MemoryGraphTuning, actions: MemoryGraphTuningActions, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LettaDimens.Space.lg),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
            TextButton(onClick = actions.onReset) { Text("Reset to defaults") }
            TextButton(onClick = actions.onReheat) { Text("Reheat") }
            TextButton(onClick = actions.onCopy) { Text("Copy as Kotlin defaults") }
        }
        MemoryGraphKnobs.sections.forEach { section ->
            LettaSectionLabel(section.title)
            section.toggles.forEach { toggle -> ToggleRow(toggle, tuning, actions.onChange) }
            section.knobs.forEach { knob -> KnobRow(knob, tuning, actions.onChange) }
        }
    }
}

@Composable
private fun KnobRow(knob: MemoryGraphKnob, tuning: MemoryGraphTuning, onChange: (MemoryGraphTuning) -> Unit) {
    val value = knob.read(tuning)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Text(knob.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(KNOB_LABEL_WEIGHT))
        Slider(
            value = value.coerceIn(knob.range),
            onValueChange = { onChange(knob.write(tuning, it)) },
            valueRange = knob.range,
            modifier = Modifier.weight(KNOB_SLIDER_WEIGHT),
        )
        KnobField(value, { onChange(knob.write(tuning, it)) }, Modifier.weight(KNOB_FIELD_WEIGHT))
    }
}

@Composable
private fun ToggleRow(toggle: MemoryGraphToggle, tuning: MemoryGraphTuning, onChange: (MemoryGraphTuning) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Text(toggle.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked = toggle.read(tuning), onCheckedChange = { onChange(toggle.write(tuning, it)) })
    }
}

/** The knob's exact value, typed; a number that does not parse is kept as text until it does. */
@Composable
private fun KnobField(value: Float, onValue: (Float) -> Unit, modifier: Modifier) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            typed.toFloatOrNull()?.let(onValue)
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
        modifier = modifier,
    )
}

/** One tunable number: its [label], its slider's [range], and how it reads and writes a tuning. */
internal class MemoryGraphKnob(
    val label: String,
    val range: ClosedFloatingPointRange<Float>,
    val read: (MemoryGraphTuning) -> Float,
    val write: (MemoryGraphTuning, Float) -> MemoryGraphTuning,
)

/** One on/off knob: its [label], and how it reads and writes a tuning. */
internal class MemoryGraphToggle(
    val label: String,
    val read: (MemoryGraphTuning) -> Boolean,
    val write: (MemoryGraphTuning, Boolean) -> MemoryGraphTuning,
)

/** A titled group of knobs, its switches first. */
internal class MemoryGraphKnobSection(
    val title: String,
    val knobs: List<MemoryGraphKnob>,
    val toggles: List<MemoryGraphToggle> = emptyList(),
)

/** Every knob of [MemoryGraphTuning], by section. */
internal object MemoryGraphKnobs {
    val sections: List<MemoryGraphKnobSection> = listOf(
        MemoryGraphKnobSection("Repulsion", repulsion()),
        MemoryGraphKnobSection("Springs", springs()),
        MemoryGraphKnobSection("Clusters and seeding", clustersAndSeeding(), clusterAndSeedToggles()),
        MemoryGraphKnobSection("Gravity and collision", gravityAndCollision()),
        MemoryGraphKnobSection("Motion", motion()),
        MemoryGraphKnobSection("Cooling", cooling()),
        MemoryGraphKnobSection("Labels", labels(), labelToggles()),
    )

    private fun repulsion(): List<MemoryGraphKnob> = listOf(
        MemoryGraphKnob("Strength", 0f..20_000f, { it.repulsion }, { t, v -> t.copy(repulsion = v) }),
        MemoryGraphKnob("Cut-off distance (0 = none)", 0f..1_000f, { it.repulsionCutoff }, { t, v -> t.copy(repulsionCutoff = v) }),
        MemoryGraphKnob("Strength scale (crowded)", 0.5f..6f, { it.crowdedRepulsionScale }, { t, v -> t.copy(crowdedRepulsionScale = v) }),
        MemoryGraphKnob("Barnes–Hut above nodes", 0f..2_000f, { it.barnesHutAbove }, { t, v -> t.copy(barnesHutAbove = v) }),
        MemoryGraphKnob("Barnes–Hut theta", 0.1f..1.5f, { it.barnesHutTheta }, { t, v -> t.copy(barnesHutTheta = v) }),
    ) + MemoryGraphNodeKind.entries.map { kind ->
        MemoryGraphKnob("Charge: ${kind.name}", 0f..5f, { it.chargeOf(kind) }, { t, v -> t.copy(nodeCharge = t.nodeCharge + (kind to v)) })
    }

    private fun labelToggles(): List<MemoryGraphToggle> = listOf(
        MemoryGraphToggle("Cull overlapping labels", { it.labelCulling }, { t, v -> t.copy(labelCulling = v) }),
        MemoryGraphToggle(
            "Label neighbours on hover or select",
            { it.labelNeighboursOnFocus },
            { t, v -> t.copy(labelNeighboursOnFocus = v) },
        ),
    )

    private fun labels(): List<MemoryGraphKnob> = listOf(
        MemoryGraphKnob("Label at or over degree", 0f..20f, { it.labelMinDegree }, { t, v -> t.copy(labelMinDegree = v) }),
        MemoryGraphKnob("Label everything from zoom", 0.2f..5f, { it.labelZoomThreshold }, { t, v -> t.copy(labelZoomThreshold = v) }),
        MemoryGraphKnob(
            "Label everything at or under nodes",
            0f..400f,
            { it.labelAllUnderNodes },
            { t, v -> t.copy(labelAllUnderNodes = v) },
        ),
        MemoryGraphKnob("Label font scale", 0.5f..2f, { it.labelFontScale }, { t, v -> t.copy(labelFontScale = v) }),
        MemoryGraphKnob("Hysteresis (decisions)", 1f..10f, { it.labelHysteresisCount }, { t, v -> t.copy(labelHysteresisCount = v) }),
        MemoryGraphKnob("Hysteresis margin (px)", 0f..30f, { it.labelHysteresisMargin }, { t, v -> t.copy(labelHysteresisMargin = v) }),
        MemoryGraphKnob("Cull every N frames while moving", 1f..30f, { it.labelCullInterval }, { t, v -> t.copy(labelCullInterval = v) }),
    )

    private fun clusterAndSeedToggles(): List<MemoryGraphToggle> = listOf(
        MemoryGraphToggle("Cluster force", { it.clusterEnabled }, { t, v -> t.copy(clusterEnabled = v) }),
        MemoryGraphToggle("Seeded layout", { it.seededLayout }, { t, v -> t.copy(seededLayout = v) }),
        MemoryGraphToggle("Pin the backend at the centre (fleet)", { it.pinBackend }, { t, v -> t.copy(pinBackend = v) }),
    )

    private fun clustersAndSeeding(): List<MemoryGraphKnob> = listOf(
        MemoryGraphKnob("Cluster strength", 0f..0.5f, { it.clusterStrength }, { t, v -> t.copy(clusterStrength = v) }),
        MemoryGraphKnob("Seed spacing (per sqrt of rank)", 10f..200f, { it.seedSpacing }, { t, v -> t.copy(seedSpacing = v) }),
        MemoryGraphKnob("Seed jitter", 0f..100f, { it.seedJitter }, { t, v -> t.copy(seedJitter = v) }),
    )

    private fun springs(): List<MemoryGraphKnob> = listOf(
        MemoryGraphKnob("Degree scaling (0 off, 1 d3)", 0f..1f, { it.degreeScaling }, { t, v -> t.copy(degreeScaling = v) }),
        MemoryGraphKnob("Backend spoke stiffness (fleet)", 0f..0.3f, { it.hubSpokeStrength }, { t, v -> t.copy(hubSpokeStrength = v) }),
    ) + MemoryGraphEdgeKind.entries.flatMap { kind ->
        listOf(
            MemoryGraphKnob(
                "Length: ${kind.name}",
                10f..400f,
                { it.lengthOf(kind) },
                { t, v -> t.copy(springLength = t.springLength + (kind to v)) },
            ),
            MemoryGraphKnob(
                "Stiffness: ${kind.name}",
                0f..1f,
                { it.stiffnessOf(kind) },
                { t, v -> t.copy(springStiffness = t.springStiffness + (kind to v)) },
            ),
        )
    }

    private fun gravityAndCollision(): List<MemoryGraphKnob> = listOf(
        MemoryGraphKnob("Centre gravity", 0f..0.1f, { it.gravity }, { t, v -> t.copy(gravity = v) }),
        MemoryGraphKnob("Collision padding", 0f..40f, { it.collisionPadding }, { t, v -> t.copy(collisionPadding = v) }),
        MemoryGraphKnob("Collision strength", 0f..3f, { it.collisionStrength }, { t, v -> t.copy(collisionStrength = v) }),
        MemoryGraphKnob(
            "Collision strength (crowded)",
            0f..6f,
            { it.crowdedCollisionStrength },
            { t, v -> t.copy(crowdedCollisionStrength = v) },
        ),
    ) + MemoryGraphNodeKind.entries.map { kind ->
        MemoryGraphKnob(
            "Collision radius: ${kind.name}",
            0.2f..3f,
            { it.radiusScaleOf(kind) },
            { t, v -> t.copy(collisionRadiusScale = t.collisionRadiusScale + (kind to v)) },
        )
    }

    private fun motion(): List<MemoryGraphKnob> = listOf(
        MemoryGraphKnob("Damping (sparse)", 0f..0.99f, { it.sparseDamping }, { t, v -> t.copy(sparseDamping = v) }),
        MemoryGraphKnob("Damping (crowded)", 0f..0.99f, { it.crowdedDamping }, { t, v -> t.copy(crowdedDamping = v) }),
        MemoryGraphKnob("Speed cap (sparse)", 1f..60f, { it.sparseMaxSpeed }, { t, v -> t.copy(sparseMaxSpeed = v) }),
        MemoryGraphKnob("Speed cap (crowded)", 1f..60f, { it.crowdedMaxSpeed }, { t, v -> t.copy(crowdedMaxSpeed = v) }),
        MemoryGraphKnob("Sparse at or under (crowding)", 0f..500f, { it.sparseCrowding }, { t, v -> t.copy(sparseCrowding = v) }),
        MemoryGraphKnob("Crowded at or over (crowding)", 0f..1_000f, { it.crowdedCrowding }, { t, v -> t.copy(crowdedCrowding = v) }),
    )

    private fun cooling(): List<MemoryGraphKnob> = listOf(
        MemoryGraphKnob("Alpha start", 0.1f..2f, { it.alphaStart }, { t, v -> t.copy(alphaStart = v) }),
        MemoryGraphKnob("Cooling frames", 10f..600f, { it.coolingFrames }, { t, v -> t.copy(coolingFrames = v) }),
        MemoryGraphKnob("Alpha minimum", 0.0001f..0.05f, { it.alphaMin }, { t, v -> t.copy(alphaMin = v) }),
        MemoryGraphKnob("Stop below speed", 0f..1f, { it.settleSpeed }, { t, v -> t.copy(settleSpeed = v) }),
        MemoryGraphKnob("Most frames per heat", 30f..1_200f, { it.maxFramesPerHeat }, { t, v -> t.copy(maxFramesPerHeat = v) }),
    )
}

private const val KNOB_LABEL_WEIGHT = 2f
private const val KNOB_SLIDER_WEIGHT = 3f
private const val KNOB_FIELD_WEIGHT = 1.4f
