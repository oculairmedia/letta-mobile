package com.letta.mobile.ui.memory

import com.letta.mobile.data.memory.MemoryGraphEdgeKind
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.graph.MemoryGraphTuning

/**
 * Tunings the physics tests pin so they test the simulation's mechanics, not the shipped preset:
 * [classic] is the tuning before the dev window's tuned preset became the default (springs at
 * the controller layout's 110 and 0.14, every kind alike), which the mechanics assertions (a bloom
 * settles near the controller's layout, a leaf follows its dragged hub) are written against.
 */
internal object MemoryGraphTestTunings {
    val classic = MemoryGraphTuning(
        repulsion = 3200f,
        nodeCharge = MemoryGraphNodeKind.entries.associateWith { 1f },
        repulsionCutoff = 0f,
        crowdedRepulsionScale = 3f,
        barnesHutAbove = 300f,
        barnesHutTheta = 0.9f,
        barnesHutRebuildFrames = 10f,
        springLength = MemoryGraphEdgeKind.entries.associateWith { 110f },
        naturalness = 1f,
        linkLengthSpread = mapOf(
            MemoryGraphEdgeKind.Runs to 0.2f,
            MemoryGraphEdgeKind.Remembers to 0.3f,
            MemoryGraphEdgeKind.Uses to 0.25f,
            MemoryGraphEdgeKind.Delivers to 0.15f,
        ),
        linkStiffnessSpread = 0.3f,
        chargeSpread = 0.2f,
        collisionRadiusSpread = 0.15f,
        gravitySpread = 0.2f,
        clusterSpread = 0.2f,
        linkLengthDegreeBias = 0.1f,
        springStiffness = MemoryGraphEdgeKind.entries.associateWith { 0.14f },
        hubSpokeStrength = 0.02f,
        pinBackend = true,
        degreeScaling = 1f,
        clusterEnabled = true,
        clusterStrength = 0.05f,
        seededLayout = true,
        seedSpacing = 60f,
        seedJitter = 30f,
        gravity = 0.01f,
        collisionPadding = 10f,
        collisionStrength = 0.6f,
        crowdedCollisionStrength = 3f,
        collisionMaxShare = 0.35f,
        collisionFadeAlpha = 0.1f,
        collisionRadiusScale = MemoryGraphNodeKind.entries.associateWith { 1f },
        sparseDamping = 0.75f,
        crowdedDamping = 0.45f,
        sparseMaxSpeed = 30f,
        crowdedMaxSpeed = 12f,
        sparseCrowding = 40f,
        crowdedCrowding = 240f,
        alphaStart = 1f,
        coolingFrames = 150f,
        alphaMin = 0.001f,
        settleSpeed = 0.04f,
        maxFramesPerHeat = 360f,
        labelMinDegree = 3f,
        labelZoomThreshold = 1.6f,
        labelAllUnderNodes = 60f,
        labelCulling = true,
        labelNeighboursOnFocus = true,
        labelFontScale = 1f,
        labelHysteresisCount = 3f,
        labelHysteresisMargin = 6f,
        labelCullInterval = 8f,
    )
}
