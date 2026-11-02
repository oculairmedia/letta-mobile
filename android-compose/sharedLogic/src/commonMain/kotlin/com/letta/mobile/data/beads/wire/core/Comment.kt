package com.letta.mobile.data.beads.wire.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve Comment schema. */
@Serializable
data class Comment(
    val id: String,
    @SerialName("issue_id")
    val issueId: String,
    val author: String,
    val text: String,
    @SerialName("created_at")
    val createdAt: String,
)
