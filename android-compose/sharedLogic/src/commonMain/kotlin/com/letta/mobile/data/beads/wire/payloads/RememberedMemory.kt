package com.letta.mobile.data.beads.wire.payloads

import kotlinx.serialization.Serializable

/** Wire representation of the bd serve RememberedMemory schema. */
@Serializable
data class RememberedMemory(
    val key: String,
    val value: String,
    val replaced: Boolean,
)
