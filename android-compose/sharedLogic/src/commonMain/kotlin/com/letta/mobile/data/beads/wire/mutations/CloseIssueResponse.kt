package com.letta.mobile.data.beads.wire.mutations

import com.letta.mobile.data.beads.wire.core.Issue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve CloseIssueResponse schema. */
@Serializable
data class CloseIssueResponse(
    val issue: Issue,
    @SerialName("already_closed")
    val alreadyClosed: Boolean,
    @SerialName("open_children")
    val openChildren: Int,
    val revision: String,
)
