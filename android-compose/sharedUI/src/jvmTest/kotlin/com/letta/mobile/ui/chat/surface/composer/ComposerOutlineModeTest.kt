package com.letta.mobile.ui.chat.surface.composer

import com.letta.mobile.data.presence.RunPhase
import com.letta.mobile.data.voice.session.VoiceModeState
import kotlin.test.Test
import kotlin.test.assertEquals

class ComposerOutlineModeTest {
    @Test
    fun conversationPhasesAndVoicePrecedence() {
        assertEquals(ComposerOutlineMode.Idle, composerOutlineMode(null, RunPhase.IDLE, false))
        assertEquals(ComposerOutlineMode.Thinking, composerOutlineMode(null, RunPhase.QUEUED, false))
        assertEquals(ComposerOutlineMode.Thinking, composerOutlineMode(null, RunPhase.REASONING, false))
        listOf(RunPhase.WORKING, RunPhase.DELEGATING, RunPhase.RESPONDING, RunPhase.INTERRUPTING).forEach {
            assertEquals(ComposerOutlineMode.Working, composerOutlineMode(null, it, false))
        }
        assertEquals(ComposerOutlineMode.Listening, composerOutlineMode(VoiceModeState.Listening, RunPhase.WORKING, true))
        assertEquals(ComposerOutlineMode.Working, composerOutlineMode(VoiceModeState.WaitingForReply, RunPhase.REASONING, false))
        assertEquals(ComposerOutlineMode.Working, composerOutlineMode(null, null, true))
    }
}
