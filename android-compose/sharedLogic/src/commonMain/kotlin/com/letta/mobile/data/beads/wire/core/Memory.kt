package com.letta.mobile.data.beads.wire.core

import kotlinx.serialization.Serializable

/** Wire representation of the bd serve Memory schema. */
@Serializable
data class Memory(
    val key: String,
    val value: String,
)
