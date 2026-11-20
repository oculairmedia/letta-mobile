package com.letta.mobile.data.shellstate.shared

import kotlinx.atomicfu.atomic

/** Where a request was issued: the ledger's generation and the link's connection identity at that moment. */
internal class Issue(val generation: Long, val connection: String?)

/** What to do with a document offered for adoption. */
internal enum class Admission {
    /** It was issued on another generation or connection: drop it. */
    DROP,

    /** The shown document, from this generation and connection, is at least as new: keep it. */
    KEEP_SHOWN,

    /** Show the offered document. */
    REPLACE,
}

/**
 * The connection bookkeeping of [SharedShellState]: the generation (bumped by every connection transition), the
 * generation and connection identity the shown document was adopted on, and the rules that decide, from those
 * alone, whether a reply is current and whether an offered document may replace the shown one. It holds no
 * state flow and takes no lock: the owner calls it only while holding its model lock, so each decision and the
 * change it leads to are one step. [currentConnection] reads the link's identity synchronously.
 */
internal class ConnectionLedger(private val currentConnection: () -> String?) {
    // Atomic so that [issue] can record where a push was received without taking the owner's lock.
    private val generation = atomic(0L)
    private var shownGeneration = NO_GENERATION
    private var shownConnection: String? = null

    /** Where a request issued now is issued. */
    fun issue() = Issue(generation.value, currentConnection())

    /** Whether [issued] is still the current generation and connection. */
    fun isCurrent(issued: Issue): Boolean = issued.generation == generation.value && issued.connection == currentConnection()

    /** Whether the shown document was read on the connection that is up now. */
    fun shownOnCurrentConnection(): Boolean = shownConnection == currentConnection()

    /** Starts a new generation. With no document shown, nothing is remembered about the connection it came from. */
    fun advance(documentShown: Boolean) {
        generation.incrementAndGet()
        if (!documentShown) shownConnection = null
    }

    /** Decides what to do with a document of [offeredRevision] against a shown one of [shownRevision]; [issued] is where it was issued or received. */
    fun admit(issued: Issue, offeredRevision: Long, shownRevision: Long): Admission {
        if (!isCurrent(issued)) return Admission.DROP
        val shownHere = shownGeneration == generation.value && shownOnCurrentConnection()
        return if (shownHere && shownRevision >= offeredRevision) Admission.KEEP_SHOWN else Admission.REPLACE
    }

    /**
     * Records that the shown document comes from where [admitted] was issued or received: the identity [admit]
     * just checked, never a fresh read of the generation or the link, which could already have moved on.
     */
    fun recordShown(admitted: Issue) {
        shownGeneration = admitted.generation
        shownConnection = admitted.connection
    }

    private companion object {
        const val NO_GENERATION = -1L
    }
}
