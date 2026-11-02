package com.letta.mobile.data.beads.wire.mutations

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Wire representation of the bd serve CompareAndSetMetadataRequest schema. */
@Serializable
data class CompareAndSetMetadataRequest(
    val actor: String,
    val key: String,
    val expected: JsonElement? = null,
    val value: JsonElement? = null,
)
