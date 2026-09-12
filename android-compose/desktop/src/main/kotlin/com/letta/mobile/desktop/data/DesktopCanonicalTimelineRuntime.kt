package com.letta.mobile.desktop.data

import com.letta.mobile.data.timeline.CanonicalTimelineCoordinator
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.data.timeline.TimelineTransport
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Desktop's production binding for the canonical windowed timeline. One runtime per captured
 * transport generation; it owns the conversation writers, and a presentation is only a viewport
 * lease over them. Closing a view detaches that lease and never retires the writer or drops
 * indexed durable rows — only [close] does, when the transport generation itself goes away.
 *
 * Desktop has no legacy Room ledger to migrate from, so there is no ownership authority and no
 * copy/convert/validate cutover here: the indexed file store IS the canonical store from the first
 * open. That is the whole reason this is ~60 lines where Android's equivalent is ~300.
 */
class DesktopCanonicalTimelineRuntime internal constructor(
    private val coordinator: CanonicalTimelineCoordinator,
    private val backendId: String,
) {
    private val mutex = Mutex()
    private val owners = mutableMapOf<TimelineScope, CanonicalTimelineCoordinator.Owner>()
    private var closed = false

    /**
     * Reuses the conversation's existing writer when one is already open. Acquiring a second owner
     * for a scope would retire the first, which is exactly the "stale handle" failure the
     * coordinator's isolation contract forbids — so the map is the point, not a cache.
     */
    suspend fun open(
        agentId: String,
        conversationId: String,
        uiScope: CoroutineScope,
        target: String? = null,
    ): CanonicalTimelinePresentation {
        val scope = TimelineScope(backendId, conversationId, agentId)
        val owner = mutex.withLock {
            check(!closed) { "Desktop canonical timeline runtime is retired" }
            owners.getOrPut(scope) { coordinator.acquire(scope) }
        }
        return openDesktopCanonicalPresentation(coordinator, owner, uiScope, target)
    }

    /** Retires every conversation writer this runtime opened. Not reversible. */
    suspend fun close() {
        val retiring = mutex.withLock {
            if (closed) return
            closed = true
            owners.values.toList().also { owners.clear() }
        }
        withContext(NonCancellable) {
            retiring.forEach { coordinator.retire(it) }
            coordinator.revoke()
        }
    }
}

/**
 * Returns null when the canonical route is disabled, which is the default. The route is read-only
 * until the external transport writer is bound to Desktop's send/stream path
 * (bead letta-mobile-x13xi.12.1), so it must not become the default by accident.
 */
fun createDesktopCanonicalTimelineRuntime(
    transport: TimelineTransport,
    backendId: String,
    ledgerDirectory: Path = defaultDesktopTimelineLedgerDirectory(),
    enabled: Boolean = desktopCanonicalTimelineEnabled(),
): DesktopCanonicalTimelineRuntime? {
    val coordinator = createDesktopCanonicalTimelineCoordinator(ledgerDirectory, transport, enabled)
        ?: return null
    return DesktopCanonicalTimelineRuntime(coordinator, backendId)
}

/**
 * Deliberately NOT the legacy snapshot directory: the indexed ledger is a separate generation and
 * must never be written into the directory the confirmed-timeline snapshots live in.
 */
fun defaultDesktopTimelineLedgerDirectory(): Path =
    Path.of(System.getProperty("user.home"), ".letta-mobile", "timeline-ledger")

internal fun desktopCanonicalTimelineEnabled(): Boolean =
    System.getenv("LETTA_DESKTOP_CANONICAL_TIMELINE")?.trim()?.lowercase() in ENABLED_VALUES

private val ENABLED_VALUES = setOf("1", "true", "on", "yes")
