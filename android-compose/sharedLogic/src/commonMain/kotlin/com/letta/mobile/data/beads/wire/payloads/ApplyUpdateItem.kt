package com.letta.mobile.data.beads.wire.payloads

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Update fields shared by single and batch operations; each operation validates its own members. */
@Serializable
data class ApplyUpdateItem(
    val actor: String? = null,
    val target: Ref? = null,
    val patch: ApplyPatchBody,
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
