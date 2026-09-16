package com.letta.mobile.desktop.data

import com.letta.mobile.data.timeline.CanonicalTimelineCoordinator
import com.letta.mobile.data.timeline.TimelineDurableCheckpoint
import com.letta.mobile.data.timeline.TimelineDurableCheckpointCodec
import com.letta.mobile.data.timeline.TimelineTransport
import java.nio.file.Path

/**
 * Explicit dormant binding. The caller supplies a separately named ledger directory, never the
 * legacy snapshot directory. Construction performs no disk IO and does not acquire a conversation.
 * The enabled path has no ConfirmedTimelineStore dependency or whole-envelope fallback.
 */
fun createDesktopCanonicalTimelineCoordinator(
    ledgerDirectory: Path,
    transport: TimelineTransport,
    enabled: Boolean = false,
): CanonicalTimelineCoordinator? {
    if (!enabled) return null
    val codec = object : DesktopTimelineCheckpointCodec {
        override fun encode(value: TimelineDurableCheckpoint): ByteArray = TimelineDurableCheckpointCodec.encode(value)
        override fun decode(bytes: ByteArray): TimelineDurableCheckpoint = TimelineDurableCheckpointCodec.decode(bytes)
    }
    return CanonicalTimelineCoordinator(DesktopTimelineBoundedStore(ledgerDirectory, codec), transport)
}
