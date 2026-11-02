package com.letta.mobile.data.beads.wire.payloads

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve ApplyItemResult schema. */
@Serializable
data class ApplyItemResult(
    val kind: String,
    @SerialName("issue_id")
    val issueId: String,
    @SerialName("depends_on_id")
    val dependsOnId: String? = null,
    val changed: Boolean,
    val revision: String,
)
