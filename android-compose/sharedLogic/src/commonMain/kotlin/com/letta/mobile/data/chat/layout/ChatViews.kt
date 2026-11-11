package com.letta.mobile.data.chat.layout

import com.letta.mobile.data.model.view.RuntimeViewId

/** Chat content modules; presentation chrome and gestures remain owned by the chat frame. */
object ChatViews {
    /** The conversation's readable transcript; it cannot leave the chat lane. */
    val TRANSCRIPT = RuntimeViewId("chat.transcript")

    /** The live run's status line, collected only where it is drawn. */
    val STATUS = RuntimeViewId("chat.status")

    /** The conversation's input; it cannot leave the chat lane. */
    val COMPOSER = RuntimeViewId("chat.composer")

    /** Today's full-page order; compact canvas presentations keep the composer docked. */
    val Default: List<RuntimeViewId> = listOf(TRANSCRIPT, STATUS, COMPOSER)
}
