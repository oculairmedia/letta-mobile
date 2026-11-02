package com.letta.mobile.data.beads.wire.payloads

import kotlinx.serialization.Serializable

/** Wire representation of the bd serve AddDependenciesRequest schema. */
@Serializable
data class AddDependenciesRequest(
    val actor: String,
    val edges: List<DependencyEdge>,
)
