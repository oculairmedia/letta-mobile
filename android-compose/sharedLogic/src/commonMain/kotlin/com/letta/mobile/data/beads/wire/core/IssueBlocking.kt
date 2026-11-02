package com.letta.mobile.data.beads.wire.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve IssueBlocking schema. */
@Serializable
data class IssueBlocking(
    val id: String,
    @SerialName("blocked_by")
    val blockedBy: List<String>,
    val blocks: List<String>,
    val parent: String? = null,
)
