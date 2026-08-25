package com.letta.mobile.feature.chat

import com.letta.mobile.feature.chat.screen.messagelist.ChatRestorationState
import com.letta.mobile.feature.chat.screen.messagelist.initialRestorationState
import com.letta.mobile.feature.chat.screen.messagelist.nextRestorationStateAfterViewport
import com.letta.mobile.feature.chat.screen.messagelist.restorationStateAfterUserInteraction
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportRestorationStateTest {
    @Test
    fun `restoration waits for both snapshot and first layout`() {
        assertEquals(ChatRestorationState.AwaitingSnapshot, initialRestorationState(hasItems = false))
        assertEquals(ChatRestorationState.AwaitingFirstLayout, initialRestorationState(hasItems = true))
        assertEquals(
            ChatRestorationState.AwaitingFirstLayout,
            nextRestorationStateAfterViewport(ChatRestorationState.AwaitingFirstLayout, followLatest = false),
        )
        assertEquals(ChatRestorationState.UserControlled, restorationStateAfterUserInteraction())
    }

    @Test
    fun `restoration preserves user position until tail is followed again`() {
        assertEquals(
            ChatRestorationState.UserControlled,
            nextRestorationStateAfterViewport(ChatRestorationState.Restored, followLatest = false),
        )
        assertEquals(
            ChatRestorationState.UserControlled,
            nextRestorationStateAfterViewport(ChatRestorationState.UserControlled, followLatest = false),
        )
        assertEquals(
            ChatRestorationState.FollowTail,
            nextRestorationStateAfterViewport(ChatRestorationState.UserControlled, followLatest = true),
        )
    }
}
