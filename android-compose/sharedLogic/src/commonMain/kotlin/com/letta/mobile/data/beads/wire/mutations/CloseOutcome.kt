package com.letta.mobile.data.beads.wire.mutations

import com.letta.mobile.data.beads.wire.core.Issue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve CloseOutcome schema. */
@Serializable
data class CloseOutcome(
    @SerialName("issue_id")
    val issueId: String,
    val issue: Issue? = null,
    @SerialName("already_closed")
    val alreadyClosed: Boolean? = null,
    @SerialName("open_children")
    val openChildren: Int? = null,
    val code: String? = null,
    val detail: String? = null,
)
