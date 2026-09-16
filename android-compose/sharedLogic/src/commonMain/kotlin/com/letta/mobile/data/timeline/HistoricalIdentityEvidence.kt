package com.letta.mobile.data.timeline

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentSet

/**
 * Reference identity checkpoint for shadow parity checks, not a resident page cache.
 * Keys use the existing reducer's exact equality, never a content hash alone.
 * Semantic keys can contain text; the durable implementation must not load this
 * whole index into memory. Matching identity does not imply an unchanged body.
 */
internal class HistoricalIdentityEvidence private constructor(
    private val ownersByKey: PersistentMap<String, PersistentSet<String>>,
) {
    fun ownersFor(event: TimelineEvent): PersistentSet<String> = event.identityKeys()
        .flatMap { ownersByKey[it].orEmpty() }
        .toPersistentSet()

    companion object {
        fun checkpoint(timeline: Timeline): HistoricalIdentityEvidence {
            val owners = persistentMapOf<String, PersistentSet<String>>().builder()
            for (event in timeline.events) {
                for (key in event.identityKeys()) {
                    owners[key] = (owners[key].orEmpty() + event.otid).toPersistentSet()
                }
            }
            return HistoricalIdentityEvidence(owners.build())
        }
    }
}
