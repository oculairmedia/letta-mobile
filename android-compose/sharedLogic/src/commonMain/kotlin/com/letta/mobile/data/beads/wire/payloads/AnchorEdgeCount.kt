package com.letta.mobile.data.beads.wire.payloads

import kotlinx.serialization.Serializable

/** Wire representation of the bd serve AnchorEdgeCount schema. */
@Serializable
data class AnchorEdgeCount(
    val id: String,
    val count: Long,
    val missing: Boolean,
)
