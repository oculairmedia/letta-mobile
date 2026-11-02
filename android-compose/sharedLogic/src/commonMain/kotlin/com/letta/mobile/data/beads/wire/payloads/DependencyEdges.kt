package com.letta.mobile.data.beads.wire.payloads

import com.letta.mobile.data.beads.wire.core.Dependency
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve DependencyEdges schema. */
@Serializable
data class DependencyEdges(
    val items: List<Dependency>,
    val missing: List<String>,
)
