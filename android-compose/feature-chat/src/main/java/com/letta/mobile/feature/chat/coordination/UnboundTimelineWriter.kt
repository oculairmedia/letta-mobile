package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import java.lang.reflect.Proxy

/**
 * letta-mobile-by4vm.15: the writer a send pipeline holds while no runtime is captured. The canonical
 * ledger is the only timeline, so there is no process-wide writer to fall back to: every write fails
 * loudly (the send coordinators turn that into a failed bubble) and a reconcile reports a skip.
 * A proxy, because each of the interface's members would otherwise be spelled out only to throw.
 */
internal val UnboundTimelineWriter: TimelineExternalTransportWriter = Proxy.newProxyInstance(
    TimelineExternalTransportWriter::class.java.classLoader,
    arrayOf(TimelineExternalTransportWriter::class.java),
) { _, method, _ -> unbound(method.name) } as TimelineExternalTransportWriter

private fun unbound(member: String): RecentMessagesReconcileOutcome = when (member) {
    "reconcileRecentMessages" -> RecentMessagesReconcileOutcome.Skipped("no_runtime")
    else -> error("No chat runtime is captured for this backend ($member)")
}
