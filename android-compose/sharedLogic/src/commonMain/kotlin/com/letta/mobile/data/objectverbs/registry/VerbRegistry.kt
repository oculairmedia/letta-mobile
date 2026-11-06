package com.letta.mobile.data.objectverbs.registry

import com.letta.mobile.data.objectverbs.ObjectRef
import com.letta.mobile.data.objectverbs.ObjectVerb
import com.letta.mobile.data.objectverbs.ObjectVerbSnapshot
import com.letta.mobile.data.objectverbs.VerbAvailability
import com.letta.mobile.data.objectverbs.VerbContext
import com.letta.mobile.data.objectverbs.VerbContribution
import com.letta.mobile.data.objectverbs.VerbInvocation
import com.letta.mobile.data.objectverbs.VerbOutcome
import com.letta.mobile.data.objectverbs.VerbToggleState
import com.letta.mobile.data.pins.DevicePinStore
import com.letta.mobile.data.pins.ScopedObjectRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.booleanOrNull

/** Trusted core contribution; all old mutators and lenses invoke this same pin implementation. */
class CoreVerbProvider(
    private val pins: DevicePinStore,
    private val source: () -> String?,
    private val resolves: (ObjectRef) -> Boolean,
) {
    fun describe(ref: ObjectRef, context: VerbContext): ObjectVerbSnapshot? {
        if (!pins.hasReadableState) return null
        val scoped = scoped(ref) ?: return null
        val facets = pins.facets(scoped)
        val pinned = facets.pinned == true
        val noun = when (ref.kind) { "agent" -> "agent"; "conversation" -> "chat"; "project" -> "project"; else -> "item" }
        val availability = if (pins.availability.value) VerbAvailability("enabled")
            else VerbAvailability("disabled", "Pin storage is unavailable")
        return ObjectVerbSnapshot(1, ref, context, VerbContribution("core", KINDS), facets, listOf(
            ObjectVerb(PIN, "${if (pinned) "Unpin" else "Pin"} $noun", if (pinned) "pin-off" else "pin",
                availability, VerbToggleState(pinned = pinned)),
        ))
    }

    fun observe(ref: ObjectRef, context: VerbContext): Flow<ObjectVerbSnapshot?> =
        combine(pins.journal, pins.availability) { _, _ -> describe(ref, context) }

    fun invoke(request: VerbInvocation): VerbOutcome {
        if (request.verbId != PIN) return VerbOutcome.Unavailable("Verb provider unavailable")
        val scoped = scoped(request.objectRef) ?: return VerbOutcome.Unavailable("Object or source unavailable")
        val desired = (request.input?.get("pinned") as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeUnless { it.isString }?.booleanOrNull
        if (desired == null || request.input?.keys != setOf("pinned")) {
            return VerbOutcome.InvalidInput(mapOf("pinned" to "Exactly one boolean pinned property is required"))
        }
        val result = runCatching {
            pins.setPinned(scoped, desired)
            VerbOutcome.Updated(checkNotNull(describe(request.objectRef, request.context)))
        }
        result.exceptionOrNull()?.let { error ->
            if (error is CancellationException || error !is Exception) throw error
        }
        return result.getOrElse { VerbOutcome.Failed("Pin persistence failed", retryable = true) }
    }

    private fun scoped(ref: ObjectRef): ScopedObjectRef? {
        if (ref.kind !in KINDS || ref.id.isBlank()) return null
        if (!resolves(ref)) return null
        val namespace = if (ref.kind == "shortcut") ScopedObjectRef.DEVICE_SOURCE else source() ?: return null
        return ScopedObjectRef(namespace, ref)
    }

    companion object {
        const val PIN = "core.pin"
        val KINDS: Set<String> = setOf("agent", "conversation", "project", "canvas", "shortcut")
    }
}

/** One shared registry. An absent provider degrades to no verbs or facets and cannot mutate pins. */
class VerbRegistry(private val core: CoreVerbProvider?) {
    fun describe(ref: ObjectRef, context: VerbContext): ObjectVerbSnapshot? = core?.describe(ref, context)
    fun invoke(request: VerbInvocation): VerbOutcome = core?.invoke(request) ?: VerbOutcome.Unavailable("Provider unavailable")
}
