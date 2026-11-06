package com.letta.mobile.data.session.pins

import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.session.SessionRepositoryGraphProvider
import com.letta.mobile.data.flow.DerivedStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/** A session namespace follows the trusted graph, never a UI label or an object ID prefix. */
fun SessionRepositoryGraphProvider<out SessionRepositoryGraph>.pinSource(): StateFlow<String?> = DerivedStateFlow(
    currentGraph.map { it.backendDescriptor.backendId.value }, { current.backendDescriptor.backendId.value },
)
