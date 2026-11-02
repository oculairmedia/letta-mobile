package com.letta.mobile.data.beads.wire.payloads

import kotlinx.serialization.Serializable

/** Wire representation of the bd serve Ref schema. */
@Serializable
data class Ref(
    val key: String? = null,
    val id: String? = null,
)
