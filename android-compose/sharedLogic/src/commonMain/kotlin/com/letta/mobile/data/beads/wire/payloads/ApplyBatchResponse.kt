package com.letta.mobile.data.beads.wire.payloads

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Wire representation of the bd serve ApplyBatchResponse schema. */
@Serializable
data class ApplyBatchResponse(
    val keys: JsonElement,
    val items: List<ApplyItemResult>,
)
