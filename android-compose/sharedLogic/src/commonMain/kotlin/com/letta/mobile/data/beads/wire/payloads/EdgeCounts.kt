package com.letta.mobile.data.beads.wire.payloads

import kotlinx.serialization.Serializable

/** Wire representation of the bd serve EdgeCounts schema. */
@Serializable
data class EdgeCounts(
    val anchors: List<AnchorEdgeCount>,
)
