package com.letta.mobile.data.beads.wire.mutations

import com.letta.mobile.data.beads.wire.core.Issue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve ReopenIssueResponse schema. */
@Serializable
data class ReopenIssueResponse(
    val issue: Issue,
    @SerialName("already_open")
    val alreadyOpen: Boolean,
    val revision: String,
)
