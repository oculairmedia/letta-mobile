package com.letta.mobile.data.beads.wire.core

import kotlinx.serialization.Serializable

/** Wire representation of the bd serve Health schema. */
@Serializable
data class Health(
    val status: String,
)
