package com.letta.mobile.data.beads.wire.core

import kotlinx.serialization.Serializable

/** Wire representation of the bd serve EventsPage schema. */
@Serializable
data class EventsPage(
    val records: List<EventRecord>,
    val head: Long,
)
