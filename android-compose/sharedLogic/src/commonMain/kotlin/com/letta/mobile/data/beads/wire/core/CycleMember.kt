package com.letta.mobile.data.beads.wire.core

import kotlinx.serialization.Serializable

/** Wire representation of the bd serve CycleMember schema. */
@Serializable
data class CycleMember(
    val id: String,
    val issue: Issue? = null,
)
