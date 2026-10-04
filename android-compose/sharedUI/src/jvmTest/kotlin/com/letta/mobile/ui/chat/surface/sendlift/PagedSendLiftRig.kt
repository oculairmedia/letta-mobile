package com.letta.mobile.ui.chat.surface.sendlift

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.ui.chat.surface.timeline.ManualMainDispatcher
import com.letta.mobile.ui.chat.surface.timeline.TimelineDomainRig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

/**
 * letta-mobile-86njl.1: the canonical Touch route's timeline under the send-lift page: the real
 * coordinator and presentation of [TimelineDomainRig], opened on some history. The sent prompt
 * goes in as the optimistic record the canonical route keeps under its otid. [pump] gives the
 * domain side real time between frames and delivers Paging's pages on the manual Main dispatcher.
 */
internal class PagedSendLiftRig private constructor(
    private val rig: TimelineDomainRig,
    private val main: ManualMainDispatcher,
) : AutoCloseable {
    val presentation: CanonicalTimelinePresentation get() = rig.presentation

    fun pump() {
        Thread.sleep(REAL_MILLIS_PER_FRAME)
        main.drain()
    }

    /** The owner's send: the optimistic prompt, carrying the otid its row is keyed by. */
    fun send(text: String, otid: String) = runBlocking {
        rig.send(UserMessage(id = "pending-$otid", contentRaw = JsonPrimitive(text), date = SENT_AT, otid = otid))
    }

    override fun close() = runBlocking { rig.close() }

    companion object {
        private const val REAL_MILLIS_PER_FRAME = 12L
        private const val SENT_AT = "2026-09-25T04:00:00.000Z"
        private val scope = TimelineScope("backend", "conv-send-lift", "agent")

        fun open(main: ManualMainDispatcher, pairs: Int): PagedSendLiftRig = runBlocking {
            val rig = TimelineDomainRig.open(scope)
            rig.openOn(history(pairs))
            PagedSendLiftRig(rig, main)
        }

        private fun history(pairs: Int): List<LettaMessage> = (0 until pairs).flatMap { i ->
            val minute = "%02d".format(i)
            listOf(
                AssistantMessage(id = "old-reply-$i", contentRaw = JsonPrimitive("Answer number $i, a sentence long."), date = "2026-09-25T03:$minute:01.000Z"),
                UserMessage(id = "old-prompt-$i", contentRaw = JsonPrimitive("Question number $i"), date = "2026-09-25T03:$minute:00.000Z", otid = "cm-old-$i"),
            )
        }
    }
}
