package com.letta.mobile.data.beads.wire.payloads

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve ApplyUpdateItem schema. */
@Serializable
data class ApplyUpdateItem(
    val target: Ref,
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
