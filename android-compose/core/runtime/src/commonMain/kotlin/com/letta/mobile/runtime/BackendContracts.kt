package com.letta.mobile.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Execution location of a backend. Descriptive identity, not an authorization grant.
 */
@Serializable
enum class BackendKind {
    /** Execution on a remote Letta backend. */
    RemoteLetta,
    /** Execution by the local letta-code runtime. */
    LocalLettaCode,
}

/**
 * Identity and advertised feature set of one backend/runtime pair.
 * A display label is not a stable cache key; scope stored events by [backendId] and [runtimeId].
 */
@Serializable
data class BackendDescriptor(
    /** Backend scope that owns this record. */
    val backendId: BackendId,
    /** Runtime scope within the backend. */
    val runtimeId: RuntimeId,
    /** Execution location advertised by this backend. */
    val kind: BackendKind,
    /** Human-readable backend name; not a stable identity. */
    val label: String,
    /** Operations advertised as supported by the backend. */
    val capabilities: BackendCapabilities,
)

/**
 * Backend-advertised features used to select supported UI/runtime operations.
 *
