package com.letta.mobile.data.beads.wire.payloads

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve BatchCreateDependency schema. */
@Serializable
data class BatchCreateDependency(
    @SerialName("target_id")
    val targetId: String,
    val type: String,
)
