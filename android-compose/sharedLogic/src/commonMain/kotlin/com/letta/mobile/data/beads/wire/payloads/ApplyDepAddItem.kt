package com.letta.mobile.data.beads.wire.payloads

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Wire representation of the bd serve ApplyDepAddItem schema. */
@Serializable
data class ApplyDepAddItem(
    val source: Ref,
    val target: Ref,
    val type: String,
    val metadata: JsonElement? = null,
)
