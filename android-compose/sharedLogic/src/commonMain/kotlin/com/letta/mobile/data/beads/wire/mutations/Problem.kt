package com.letta.mobile.data.beads.wire.mutations

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve Problem schema. */
@Serializable
@Suppress("LongParameterList") // Mirrors the pinned wire schema.
data class Problem(
    val type: String? = null,
    val status: Int,
    val title: String,
    val code: String,
    val detail: String? = null,
    val `param`: String? = null,
    val reason: String? = null,
    val assignee: String? = null,
    @SerialName("issue_status")
    val issueStatus: String? = null,
    @SerialName("open_children")
    val openChildren: Int? = null,
    @SerialName("existing_type")
    val existingType: String? = null,
    @SerialName("requested_type")
    val requestedType: String? = null,
    @SerialName("issue_id")
    val issueId: String? = null,
    @SerialName("blocker_id")
    val blockerId: String? = null,
    @SerialName("blocker_is_ancestor")
    val blockerIsAncestor: Boolean? = null,
    @SerialName("expected_version")
    val expectedVersion: String? = null,
    @SerialName("actual_version")
    val actualVersion: String? = null,
    @SerialName("expected_status")
    val expectedStatus: String? = null,
    @SerialName("actual_status")
    val actualStatus: String? = null,
    @SerialName("expected_assignee")
    val expectedAssignee: String? = null,
    @SerialName("actual_assignee")
    val actualAssignee: String? = null,
    @SerialName("item_index")
    val itemIndex: Int? = null,
    @SerialName("item_kind")
    val itemKind: String? = null,
    @SerialName("item_key")
    val itemKey: String? = null,
    @SerialName("item_issue_id")
    val itemIssueId: String? = null,
    @SerialName("declared_later")
    val declaredLater: Boolean? = null,
    val since: Long? = null,
    val floor: Long? = null,
    val head: Long? = null,
    @SerialName("request_id")
    val requestId: String,
    @SerialName("server_project_id")
    val serverProjectId: String? = null,
)
