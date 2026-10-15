package com.letta.mobile.data.plugin.wire

import com.letta.mobile.plugin.api.LcpDirection
import com.letta.mobile.plugin.api.LcpMethod
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// How the wire reads `:plugin-api`'s LcpMethod, the one registry of LCP v1 methods (wire name,
// direction, SPI member, notification, deadline) shared with the SPI and the conformance kit.

/** The side a peer plays. */
enum class LcpSide { HOST, PLUGIN }

/** The side opposite this one. */
fun LcpSide.other(): LcpSide = if (this == LcpSide.HOST) LcpSide.PLUGIN else LcpSide.HOST

/** Whether a peer on [side] may receive a method travelling this way. */
fun LcpDirection.deliversTo(side: LcpSide): Boolean = when (this) {
    LcpDirection.HOST_TO_PLUGIN -> side == LcpSide.PLUGIN
    LcpDirection.PLUGIN_TO_HOST -> side == LcpSide.HOST
    LcpDirection.EITHER -> true
}

/** The spelling the reference doc uses for a direction. */
val LcpDirection.label: String
    get() = when (this) {
        LcpDirection.HOST_TO_PLUGIN -> "host->plugin"
        LcpDirection.PLUGIN_TO_HOST -> "plugin->host"
        LcpDirection.EITHER -> "either"
    }

/** Whether the method is answered. */
val LcpMethod.isRequest: Boolean get() = !notification

/** How long the receiver gets, or null for the peer's default. */
val LcpMethod.deadline: Duration? get() = deadlineMillis?.milliseconds
