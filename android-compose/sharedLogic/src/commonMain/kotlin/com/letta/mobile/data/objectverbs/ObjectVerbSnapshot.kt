package com.letta.mobile.data.objectverbs

import kotlinx.serialization.Serializable

/** A lens's presentation context. The trusted host binds source, principal and permissions separately. */
@Serializable
data class VerbContext(val lens: String)

/** Supplier metadata, never installation, enablement or a capability grant. Core owns v1 facets. */
@Serializable
data class VerbContribution(val owner: String, val kinds: Set<String>, val contractVersion: Int = 1)

/**
 * Read-only state alongside verbs. Null means unavailable/unknown, not false; pinOrder is a
 * zero-based position when pinned. kindTag (for example utility) is classification, not identity.
 */
@Serializable
data class ObjectFacets(
    val pinned: Boolean? = null,
    val pinOrder: Int? = null,
    val running: Boolean? = null,
    val needsAttention: Boolean? = null,
    val kindTag: String? = null,
)

/** One provider's versioned response for one object and context; it contains no executable callback. */
@Serializable
data class ObjectVerbSnapshot(
    val contractVersion: Int,
    val objectRef: ObjectRef,
    val context: VerbContext,
    val contribution: VerbContribution,
    val facets: ObjectFacets = ObjectFacets(),
    val verbs: List<ObjectVerb> = emptyList(),
) {
    /**
     * Compatibility projection only, not discovery or authorization. Support sets come from the
     * bound provider/adapter. Unknown kinds/versions expose neither verbs nor facets. Hidden,
     * unknown or malformed availability and unsupported verb IDs render nothing.
     */
    fun forRendering(knownKinds: Set<String>, knownVerbIds: Set<String>): ObjectVerbSnapshot? = when {
        contractVersion != 1 || contribution.contractVersion != 1 -> null
        objectRef.kind !in knownKinds -> null
        objectRef.kind !in contribution.kinds -> null
        else -> copy(verbs = verbs.filter { verb ->
            verb.id in knownVerbIds && verb.label.isNotBlank() && when (verb.availability.state) {
                "enabled" -> true
                "disabled" -> !verb.availability.reason.isNullOrBlank()
                else -> false
            }
        })
    }
}
