package com.letta.mobile.data.beads.wire.core

import kotlinx.serialization.Serializable

/** Wire representation of the bd serve ReadyCount schema. */
@Serializable
data class ReadyCount(
    val total: Long,
)
