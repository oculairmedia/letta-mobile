package com.letta.mobile.data.beads.wire.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Wire representation of the bd serve EventRecord schema. */
@Serializable
data class EventRecord(
    val seq: Long,
    val ts: String,
    val op: String,
    @SerialName("issue_id")
    val issueId: String,
    val actor: String? = null,
    val issue: JsonElement?,
    val dep: JsonElement? = null,
    val comment: JsonElement? = null,
)
