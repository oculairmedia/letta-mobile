package com.letta.mobile.data.beads.wire.payloads

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve BatchCreateItem schema. */
@Serializable
data class BatchCreateItem(
    val title: String,
    val description: String? = null,
    val design: String? = null,
    @SerialName("acceptance_criteria")
    val acceptanceCriteria: String? = null,
    val priority: Int? = null,
    @SerialName("issue_type")
    val issueType: String? = null,
    val assignee: String? = null,
    val labels: List<String>? = null,
    val dependencies: List<BatchCreateDependency>? = null,
)
