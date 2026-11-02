package com.letta.mobile.data.beads.wire.payloads

import kotlinx.serialization.Serializable

/** Wire representation of the bd serve AddDependenciesResponse schema. */
@Serializable
data class AddDependenciesResponse(
    val added: List<DependencyEdge>,
)
