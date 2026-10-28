package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineFreshAssistantRegressionTest {
    @Test
    fun distinctStampedReplyStartingWithEarlierPrefixIsPreserved() {
        val earlier = AssistantMessage(
            id = "assistant-earlier",
            contentRaw = JsonPrimitive("flq longer reply"),
            runId = "run-shared",
            otid = "otid-earlier",
            logicalMessageId = "logical-earlier",
            seqId = 31,
            textSeq = 4,
        )
        val fresh = AssistantMessage(
            id = "assistant-fresh",
            contentRaw = JsonPrimitive("flq"),
            runId = "run-shared",
            otid = "otid-fresh",
            logicalMessageId = "logical-fresh",
            seqId = 32,
            textSeq = 1,
        )

        val afterEarlier = reduceStreamFrame(
            TimelineReducerInput(Timeline("conversation"), earlier, persistentMapOf()),
        ).next
        val afterFresh = reduceStreamFrame(
            TimelineReducerInput(afterEarlier, fresh, persistentMapOf()),
        ).next

        assertEquals(
            listOf("assistant-earlier", "assistant-fresh"),
            afterFresh.events.filterIsInstance<TimelineEvent.Confirmed>().map { it.serverId },
        )
        assertEquals("flq", (afterFresh.events.last() as TimelineEvent.Confirmed).content)
    }
}
