package com.letta.mobile.data.timeline

import com.letta.mobile.util.Telemetry

/**
 * Diagnostic dump for the hydration-duplication bug family
 * (letta-mobile-1ar3u / 3j6 / 16li).
 *
 * Emits one Telemetry event per [TimelineEvent] in [timeline], tagged with
 * [phase] and [conversationId] so a captured ring buffer can be diffed
 * across hydrate → reconcile → stream-ingest transitions to find where
 * duplicate-content events get appended.
 *
 * Gated on [Telemetry.isTimelineDumpEnabled] — when off, this is a single
 * volatile read and an early return. When on, each event becomes a
 * `TimelineState/<phase>.event` at DEBUG level carrying:
 *
 *  - position, kind (Local | Confirmed)
 *  - otid, serverId (Confirmed only), messageType
 *  - runId, stepId (Confirmed only)
 *  - source (LETTA_SERVER | CLIENT_MODE_HARNESS)
 *  - role (Local only — Confirmed role is implied by messageType)
 *  - contentPrefix: first [CONTENT_PREFIX_LEN] chars of `content`, with
 *    newlines normalized to spaces so it stays on one log line
 *  - contentLen: full length of `content` (so the reader can tell whether
 *    the prefix was truncated)
 *
 * Toggle from the TelemetryScreen, or without rebuild:
 * `adb shell setprop log.tag.LettaTimelineDump VERBOSE`.
 */
internal fun dumpTimelineState(
    phase: String,
    conversationId: String,
    timeline: Timeline,
) {
    if (!Telemetry.isTimelineDumpEnabled()) return
    Telemetry.event(
        "TimelineState", "$phase.summary",
        "conversationId" to conversationId,
        "eventCount" to timeline.events.size,
        "liveCursor" to (timeline.liveCursor ?: "<null>"),
        level = Telemetry.Level.DEBUG,
    )
    timeline.events.forEachIndexed { idx, event ->
        when (event) {
            is TimelineEvent.Local -> Telemetry.event(
                "TimelineState", "$phase.event",
                "conversationId" to conversationId,
                "idx" to idx,
                "kind" to "Local",
                "position" to event.position,
                "otid" to event.otid,
                "serverId" to "<none>",
                "messageType" to event.messageType.name,
                "role" to event.role.name,
                "runId" to "<none>",
                "stepId" to "<none>",
                "source" to event.source.name,
                "contentLen" to event.content.length,
                "contentPrefix" to event.content.take(CONTENT_PREFIX_LEN).replace('\n', ' '),
                level = Telemetry.Level.DEBUG,
            )
            is TimelineEvent.Confirmed -> Telemetry.event(
                "TimelineState", "$phase.event",
                "conversationId" to conversationId,
                "idx" to idx,
                "kind" to "Confirmed",
                "position" to event.position,
                "otid" to event.otid,
                "serverId" to event.serverId,
                "messageType" to event.messageType.name,
                "role" to "<n/a>",
                "runId" to (event.runId ?: "<null>"),
                "stepId" to (event.stepId ?: "<null>"),
                "source" to event.source.name,
                "contentLen" to event.content.length,
                "contentPrefix" to event.content.take(CONTENT_PREFIX_LEN).replace('\n', ' '),
                level = Telemetry.Level.DEBUG,
            )
        }
    }
}

private const val CONTENT_PREFIX_LEN = 40
