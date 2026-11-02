package com.letta.mobile.data.beads.wire.payloads

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Wire representation of the bd serve CreateIssueDependency schema. */
@Serializable
data class CreateIssueDependency(
    @SerialName("target_id")
    val targetId: String,
    val type: String,
    val reverse: Boolean? = null,
    val metadata: JsonElement? = null,
)
