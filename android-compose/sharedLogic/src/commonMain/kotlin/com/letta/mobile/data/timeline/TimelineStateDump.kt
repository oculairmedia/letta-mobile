package com.letta.mobile.data.timeline

import com.letta.mobile.util.attr
import com.letta.mobile.util.Telemetry

/**
 * Diagnostic dump for duplicate timeline events during hydration.
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
 *  - source (LETTA_SERVER)
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
        "conversationId" attr conversationId,
        "eventCount" attr timeline.events.size,
        "liveCursor" attr (timeline.liveCursor ?: "<null>"),
        level = Telemetry.Level.DEBUG,
    )
    timeline.events.forEachIndexed { idx, event ->
        when (event) {
            is TimelineEvent.Local -> Telemetry.event(
                "TimelineState", "$phase.event",
                "conversationId" attr conversationId,
                "idx" attr idx,
                "kind" attr "Local",
                "position" attr event.position,
                "otid" attr event.otid,
                "serverId" attr "<none>",
                "messageType" attr event.messageType.name,
                "role" attr event.role.name,
                "runId" attr "<none>",
                "stepId" attr "<none>",
                "source" attr event.source.name,
                "contentLen" attr event.content.length,
                "contentPrefix" attr event.content.take(CONTENT_PREFIX_LEN).replace('\n', ' '),
                level = Telemetry.Level.DEBUG,
            )
            is TimelineEvent.Confirmed -> Telemetry.event(
                "TimelineState", "$phase.event",
                "conversationId" attr conversationId,
                "idx" attr idx,
                "kind" attr "Confirmed",
                "position" attr event.position,
                "otid" attr event.otid,
                "serverId" attr event.serverId,
                "messageType" attr event.messageType.name,
                "role" attr "<n/a>",
                "runId" attr (event.runId ?: "<null>"),
                "stepId" attr (event.stepId ?: "<null>"),
                "source" attr event.source.name,
                "contentLen" attr event.content.length,
                "contentPrefix" attr event.content.take(CONTENT_PREFIX_LEN).replace('\n', ' '),
                level = Telemetry.Level.DEBUG,
            )
        }
    }
}

private const val CONTENT_PREFIX_LEN = 40
