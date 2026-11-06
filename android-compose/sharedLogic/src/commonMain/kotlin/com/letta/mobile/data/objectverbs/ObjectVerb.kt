package com.letta.mobile.data.objectverbs

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Open availability tag: enabled, disabled with a nonblank reason, or hidden. Future tags are hidden. */
@Serializable
data class VerbAvailability(val state: String, val reason: String? = null)

/** Current toggle state, not a request to invert a lens-local cache. Unknown future state fields are ignored. */
@Serializable
data class VerbToggleState(val pinned: Boolean? = null, val archived: Boolean? = null)

/**
 * A stable namespaced affordance. label is resolved for the current locale; icon is a portable
 * token. group/order provide stable menu ordering. inputSchema is the optional v1 JSON Schema
 * subset documented in object-verbs-v1.md. Missing presentation fields fail closed for future verbs.
 */
@Serializable
data class ObjectVerb(
    val id: String,
    val label: String = "",
    val icon: String = "",
    val availability: VerbAvailability = VerbAvailability("hidden"),
    val toggle: VerbToggleState? = null,
    val inputSchema: JsonObject? = null,
    val group: String = "organize",
    val order: Int = 0,
)
