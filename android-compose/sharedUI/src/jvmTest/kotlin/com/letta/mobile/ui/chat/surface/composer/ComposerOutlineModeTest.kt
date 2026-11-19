package com.letta.mobile.ui.chat.surface.composer

import com.letta.mobile.data.presence.RunPhase
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.session.VoiceModeState
import kotlin.test.Test
import kotlin.test.assertEquals

class ComposerOutlineModeTest {
    @Test
    fun everyPhaseCrossesEveryVoiceRoleAndFallback() {
        val phases = mapOf(
            RunPhase.IDLE to ComposerOutlineMode.Idle,
            RunPhase.QUEUED to ComposerOutlineMode.Thinking,
            RunPhase.REASONING to ComposerOutlineMode.Thinking,
            RunPhase.WORKING to ComposerOutlineMode.Working,
            RunPhase.DELEGATING to ComposerOutlineMode.Working,
            RunPhase.RESPONDING to ComposerOutlineMode.Working,
            RunPhase.INTERRUPTING to ComposerOutlineMode.Working,
            RunPhase.AWAITING_INPUT to ComposerOutlineMode.Idle,
            RunPhase.FAILED to ComposerOutlineMode.Idle,
            RunPhase.DONE to ComposerOutlineMode.Idle,
        )
        assertEquals(RunPhase.entries.toSet(), phases.keys, "every run phase has an independent expectation")
        val voices = mapOf(
            null to null,
            VoiceModeState.Off to null,
            VoiceModeState.Listening to ComposerOutlineMode.Voice,
            VoiceModeState.Hearing to ComposerOutlineMode.Voice,
            VoiceModeState.WaitingForReply to ComposerOutlineMode.Working,
            // The spoken reply follows its playback level in the agent's glow.
            VoiceModeState.Speaking to ComposerOutlineMode.Voice,
            VoiceModeState.Error(VoiceErrorCode.ENGINE_FAILED) to ComposerOutlineMode.Error,
            VoiceModeState.TurnFailed(VoiceErrorCode.TRANSCRIPTION_FAILED, retryable = true) to ComposerOutlineMode.Error,
        )
        voices.forEach { (voice, voiceMode) ->
            listOf(false, true).forEach { fallback ->
                phases.forEach { (phase, phaseMode) ->
                    assertEquals(voiceMode ?: phaseMode, composerOutlineMode(voice, phase, fallback), "$voice / $phase / $fallback")
                }
                val fallbackMode = if (fallback) ComposerOutlineMode.Working else ComposerOutlineMode.Idle
                assertEquals(voiceMode ?: fallbackMode, composerOutlineMode(voice, null, fallback), "$voice / unknown / $fallback")
            }
        }
    }

    @Test
    fun conversationPhasesAndVoicePrecedence() {
        listOf(RunPhase.IDLE, RunPhase.AWAITING_INPUT, RunPhase.FAILED, RunPhase.DONE).forEach {
            assertEquals(ComposerOutlineMode.Idle, composerOutlineMode(null, it, true))
        }
        assertEquals(ComposerOutlineMode.Thinking, composerOutlineMode(null, RunPhase.QUEUED, false))
        assertEquals(ComposerOutlineMode.Thinking, composerOutlineMode(null, RunPhase.REASONING, false))
        listOf(RunPhase.WORKING, RunPhase.DELEGATING, RunPhase.RESPONDING, RunPhase.INTERRUPTING).forEach {
            assertEquals(ComposerOutlineMode.Working, composerOutlineMode(null, it, false))
        }
        assertEquals(ComposerOutlineMode.Voice, composerOutlineMode(VoiceModeState.Listening, RunPhase.WORKING, true))
        assertEquals(ComposerOutlineMode.Working, composerOutlineMode(VoiceModeState.WaitingForReply, RunPhase.REASONING, false))
        assertEquals(ComposerOutlineMode.Working, composerOutlineMode(null, null, true))
        assertEquals(ComposerOutlineMode.Idle, composerOutlineMode(null, null, false))
    }
}
