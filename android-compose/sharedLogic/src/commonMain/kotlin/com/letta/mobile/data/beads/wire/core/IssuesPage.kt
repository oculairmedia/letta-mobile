package com.letta.mobile.data.beads.wire.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve IssuesPage schema. */
@Serializable
data class IssuesPage(
    val items: List<IssueWithCounts>,
    @SerialName("has_more")
    val hasMore: Boolean,
    @SerialName("next_cursor")
    val nextCursor: String? = null,
)
