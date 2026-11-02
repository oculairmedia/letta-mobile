package com.letta.mobile.data.beads.wire.mutations

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve UpdateIssueRequest schema. */
@Serializable
data class UpdateIssueRequest(
    val actor: String,
    val patch: IssuePatchBody,
    @SerialName("expected_version")
    val expectedVersion: String? = null,
    @SerialName("expected_status")
    val expectedStatus: String? = null,
    @SerialName("expected_assignee")
    val expectedAssignee: String? = null,
    @SerialName("force_close_policy")
    val forceClosePolicy: Boolean? = null,
    @SerialName("force_assignee_transfer")
    val forceAssigneeTransfer: Boolean? = null,
)
