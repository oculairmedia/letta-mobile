package com.letta.mobile.data.beads.wire.mutations

import com.letta.mobile.data.beads.wire.core.Issue
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve UpdateIssueResponse schema. */
@Serializable
data class UpdateIssueResponse(
    val issue: Issue,
    val changed: Boolean,
    val revision: String,
)
