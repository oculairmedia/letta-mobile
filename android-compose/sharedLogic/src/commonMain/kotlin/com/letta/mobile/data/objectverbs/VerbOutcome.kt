package com.letta.mobile.data.objectverbs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Data passed to the provider's future invoke boundary; input is validated there, never executed by a lens. */
@Serializable
data class VerbInvocation(val objectRef: ObjectRef, val verbId: String, val context: VerbContext, val input: JsonObject? = null)

/** Typed result of a provider invocation. No registry or invocation implementation is supplied in v1's proposal. */
@Serializable
sealed interface VerbOutcome {
    /** The provider durably applied a change (or found the desired state already applied). */
    @Serializable
    @SerialName("updated")
    data class Updated(val snapshot: ObjectVerbSnapshot) : VerbOutcome

    /** The host may navigate to an already authorized canonical reference. */
    @Serializable
    @SerialName("open")
    data class Open(val target: ObjectRef) : VerbOutcome

    /** The user dismissed input or confirmation; nothing was applied. */
    @Serializable
    @SerialName("cancelled")
    data object Cancelled : VerbOutcome

    /** Missing provider/object, denied access or stale availability; no mutation was applied. */
    @Serializable
    @SerialName("unavailable")
    data class Unavailable(val reason: String) : VerbOutcome

    /** Provider validation rejected the input before applying a mutation; keys are schema property names. */
    @Serializable
    @SerialName("invalid_input")
    data class InvalidInput(val errors: Map<String, String>) : VerbOutcome

    /** The effect failed; retryable describes whether an explicit retry can be offered. */
    @Serializable
    @SerialName("failed")
    data class Failed(val reason: String, val retryable: Boolean = false) : VerbOutcome
}
