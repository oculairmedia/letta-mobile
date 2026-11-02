package com.letta.mobile.data.beads.wire.payloads

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Wire representation of the bd serve ApplyMetadataPatch schema. */
@Serializable
data class ApplyMetadataPatch(
    val replace: JsonElement? = null,
    val merge: JsonElement? = null,
    val `set`: JsonElement? = null,
    val unset: List<String>? = null,
)
