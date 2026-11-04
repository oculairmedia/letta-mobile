package com.letta.mobile.data.lanes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationLaneProjectionTest {
    private val inbox = "inbox"
    private val agent = "main"

    @Test
    fun boundArrivalsCountOnceAndOnlyDisplayedIdentitiesAreRead() = runTest {
        val frames = MutableSharedFlow<LaneArrival>()
        val owner = ConversationLaneProjection(backgroundScope) { frames }
        runCurrent()
        assertTrue(owner.available)
        frames.emit(arrival("message-1"))
        frames.emit(arrival("message-1"))
        frames.emit(arrival("message-2"))
        runCurrent()
        assertEquals(2, owner.activity.value[inbox]?.unreadCount, "a replayed source identity is not new activity")
        owner.displayed(inbox, "other", setOf("message-1", "message-2"))
        assertEquals(2, owner.activity.value[inbox]?.unreadCount, "another agent's transcript cannot read this inbox")
        owner.displayed("home", agent, setOf("message-1", "message-2"))
        assertEquals(2, owner.activity.value[inbox]?.unreadCount, "reading another conversation acknowledges nothing")
        owner.displayed(inbox, agent, setOf("message-1"))
        assertEquals(1, owner.activity.value[inbox]?.unreadCount, "an arrival not yet displayed stays unread")
        frames.emit(arrival("message-1"))
        runCurrent()
        assertEquals(1, owner.activity.value[inbox]?.unreadCount, "a replay of a read identity stays read")
        owner.displayed(inbox, agent, setOf("message-1", "message-2"))
        assertEquals(0, owner.activity.value[inbox]?.unreadCount)
    }

    @Test
    fun unboundProducerIsUnknownNotZero() = runTest {
        val owner = ConversationLaneProjection(backgroundScope, source = null)
        assertFalse(owner.available)
        assertNull(owner.activity.value[inbox])
    }

    @Test
    fun foreignOwnerCannotTouchStateAndOverflowIsMarkedIncomplete() = runTest {
        val owner = ConversationLaneProjection(backgroundScope, source = null)
        owner.accept(arrival("message-1"))
        owner.accept(LaneArrival("foreign", inbox, "forged"))
        assertEquals(1, owner.activity.value[inbox]?.unreadCount)
        (0..ConversationLaneProjection.MAX_MESSAGES).forEach { owner.accept(arrival("bulk-$it")) }
        assertEquals(ConversationLaneProjection.MAX_MESSAGES, owner.activity.value[inbox]?.unreadCount)
        assertTrue(owner.activity.value[inbox]?.incomplete == true)
    }

    @Test
    fun conversationsAreBoundedLeastRecentlyTouchedFirst() = runTest {
        val owner = ConversationLaneProjection(backgroundScope, source = null)
        (0..ConversationLaneProjection.MAX_CONVERSATIONS).forEach { owner.accept(LaneArrival(agent, "c$it", "m")) }
        assertEquals(ConversationLaneProjection.MAX_CONVERSATIONS, owner.activity.value.size)
        assertNull(owner.activity.value["c0"])
    }

    @Test
    fun exactTagsPreserveUnknownAndReferencesWithoutGrantingTargets() {
        assertEquals(ConversationPurpose.UNTAGGED, ConversationLane.fromTags(emptyList()).purpose)
        assertEquals(ConversationPurpose.UNKNOWN, ConversationLane.fromTags(listOf("lane:custom")).purpose)
        assertEquals(ConversationPurpose.UNKNOWN, ConversationLane.fromTags(listOf("lane:home", "lane:work")).purpose)
        val work = ConversationLane.fromTags(listOf("lane:work", "bead:x60fz", "run:run-1"))
        assertFalse(work.canTarget)
        assertFalse(work.isHumanFallback)
        assertFalse(ConversationLane.fromTags(listOf("lane:inbox")).isHumanFallback)
        assertEquals(listOf("x60fz"), work.beads)
        assertEquals(listOf("run-1"), work.runs)
    }

    private fun arrival(id: String) = LaneArrival(agent, inbox, id)
}
