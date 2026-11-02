package com.letta.mobile.data.beads.wire.mutations

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve ReleaseIssueRequest schema. */
@Serializable
data class ReleaseIssueRequest(
    val actor: String,
    @SerialName("expected_assignee")
    val expectedAssignee: String? = null,
    val force: Boolean? = null,
)
