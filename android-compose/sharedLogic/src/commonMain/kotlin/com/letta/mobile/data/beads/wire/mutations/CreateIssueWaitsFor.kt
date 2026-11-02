package com.letta.mobile.data.beads.wire.mutations

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve CreateIssueWaitsFor schema. */
@Serializable
data class CreateIssueWaitsFor(
    @SerialName("spawner_id")
    val spawnerId: String,
    val gate: String? = null,
)
