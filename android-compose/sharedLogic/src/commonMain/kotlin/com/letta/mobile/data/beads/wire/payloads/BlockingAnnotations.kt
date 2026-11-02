package com.letta.mobile.data.beads.wire.payloads

import com.letta.mobile.data.beads.wire.core.IssueBlocking
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve BlockingAnnotations schema. */
@Serializable
data class BlockingAnnotations(
    val items: List<IssueBlocking>,
)
