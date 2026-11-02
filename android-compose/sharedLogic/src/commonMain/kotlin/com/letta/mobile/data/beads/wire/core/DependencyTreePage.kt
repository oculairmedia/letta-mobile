package com.letta.mobile.data.beads.wire.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve DependencyTreePage schema. */
@Serializable
data class DependencyTreePage(
    val items: List<TreeNode>,
    @SerialName("has_more")
    val hasMore: Boolean,
)
