package com.letta.mobile.data.beads.wire.payloads

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve DependencyEdge schema. */
@Serializable
data class DependencyEdge(
    @SerialName("issue_id")
    val issueId: String,
    @SerialName("depends_on_id")
    val dependsOnId: String,
    val type: String,
)
