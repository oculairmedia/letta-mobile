package com.letta.mobile.data.beads.wire.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Wire representation of the bd serve IssueCount schema. */
@Serializable
data class IssueCount(
    val total: Long,
    val groups: JsonElement? = null,
)
