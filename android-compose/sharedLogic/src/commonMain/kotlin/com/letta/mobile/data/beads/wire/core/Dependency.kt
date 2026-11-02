package com.letta.mobile.data.beads.wire.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve Dependency schema. */
@Serializable
data class Dependency(
    val id: String? = null,
    @SerialName("issue_id")
    val issueId: String,
    @SerialName("depends_on_id")
    val dependsOnId: String,
    val type: String,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("created_by")
    val createdBy: String? = null,
    val metadata: String? = null,
    @SerialName("thread_id")
    val threadId: String? = null,
)
