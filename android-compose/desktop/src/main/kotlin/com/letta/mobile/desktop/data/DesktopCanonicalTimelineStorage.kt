package com.letta.mobile.desktop.data

import com.letta.mobile.data.timeline.CanonicalTimelineCoordinator
import com.letta.mobile.data.timeline.TimelineDurableCheckpoint
import com.letta.mobile.data.timeline.TimelineDurableCheckpointCodec
import com.letta.mobile.data.timeline.TimelineTransport
import java.nio.file.Path

/** Construction performs no disk IO and does not acquire a conversation. */
fun createDesktopCanonicalTimelineCoordinator(
    ledgerDirectory: Path,
    transport: TimelineTransport,
): CanonicalTimelineCoordinator {
    val codec = object : DesktopTimelineCheckpointCodec {
        override fun encode(value: TimelineDurableCheckpoint): ByteArray = TimelineDurableCheckpointCodec.encode(value)
        override fun decode(bytes: ByteArray): TimelineDurableCheckpoint = TimelineDurableCheckpointCodec.decode(bytes)
    }
    return CanonicalTimelineCoordinator(DesktopTimelineBoundedStore(ledgerDirectory, codec), transport)
}
