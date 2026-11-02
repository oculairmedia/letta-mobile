package com.letta.mobile.data.beads.wire.core

import kotlinx.serialization.Serializable

/** Wire representation of the bd serve AddCommentRequest schema. */
@Serializable
data class AddCommentRequest(
    val author: String,
    val text: String,
)
