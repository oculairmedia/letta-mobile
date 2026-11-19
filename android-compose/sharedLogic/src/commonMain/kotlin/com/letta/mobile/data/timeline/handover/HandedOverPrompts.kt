package com.letta.mobile.data.timeline.handover

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The prompts the live overlay keeps drawing after they leave the pending store.
 *
 * A sent prompt draws from its pending record until its durable copy is on screen. The pending
 * store lets the record go as soon as the ledger holds the durable copy, which is before Paging has
 * presented the page that draws it. A turn whose stream echoed the prompt still had the echo in the
 * overlay to cover that gap; one whose stream never echoed it (the App Server, a voice turn) had
 * nothing, so the prompt left the screen for a frame at settle and came back from the settled page.
 *
 * A record that leaves the store is held here until its otid is resident, or until resident history
 * reaches past the moment it was sent (if it were durable it would be drawn by then, so a record
 * dropped for any other reason is not held forever). A prompt the person dismissed ([forget]) is
 * never held: it is meant to leave. Records are read through [otidOf] and [sentAtMillisOf] (epoch
 * millis, null when unknown), so this holds any record type without depending on the timeline's.
 */
internal class HandedOverPrompts<R : Any>(
    private val otidOf: (R) -> String,
    private val sentAtMillisOf: (R) -> Long?,
) {
    private var previous: List<R> = emptyList()
    private val held = LinkedHashMap<String, R>()

    // Written from the dismiss caller, read by the projection: a thread-safe set.
    private val dismissed = MutableStateFlow<Set<String>>(emptySet())

    /** The person dismissed [otid]: when its record leaves, it is not held. */
    fun forget(otid: String) = dismissed.update { it + otid }

    /**
     * [pending] plus the held prompts, given the otids now resident ([residentOtids]) and the newest
     * resident row's time ([residentNewest], epoch millis).
     */
    fun hold(pending: List<R>, residentOtids: Set<String>, residentNewest: Long): List<R> {
        val current = pending.mapTo(HashSet(), otidOf)
        val forgotten = dismissed.value
        previous.filter { otidOf(it) !in current && otidOf(it) !in forgotten }.forEach { held[otidOf(it)] = it }
        previous = pending
        held.values.removeAll { record ->
            val otid = otidOf(record)
            otid in current || otid in residentOtids || (sentAtMillisOf(record) ?: 0L) < residentNewest
        }
        if (forgotten.isNotEmpty()) dismissed.update { it.intersect(current) }
        return if (held.isEmpty()) pending else pending + held.values
    }
}
