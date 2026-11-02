package com.letta.mobile.data.beads.wire.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve ReadyPage schema. */
@Serializable
data class ReadyPage(
    val items: List<IssueWithCounts>,
    @SerialName("has_more")
    val hasMore: Boolean,
)
