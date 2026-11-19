@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.loop.VoiceEngines
import com.letta.mobile.data.voice.loop.VoiceLoopRuntime
import com.letta.mobile.data.voice.output.SpokenSegment
import com.letta.mobile.data.voice.session.MicrophoneAccess
import com.letta.mobile.data.voice.session.VoiceChatSessions
import com.letta.mobile.data.voice.session.VoiceEngineFactory
import com.letta.mobile.data.voice.session.VoiceEngineSet
import com.letta.mobile.data.voice.session.VoiceModeController
import com.letta.mobile.data.voice.session.VoiceModeState
import com.letta.mobile.data.voice.session.VoiceSetupStore
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatPermissionModeUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.chat.surface.timeline.ChatTimeline
import com.letta.mobile.ui.chat.surface.timeline.rows.LocalRowComposed
import com.letta.mobile.ui.chat.voice.LocalVoiceMode
import com.letta.mobile.ui.chat.voice.VoiceModeHandle
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * While voice mode speaks a reply, what changes every frame (the playback level the glow follows, the
 * card's clock, the reply's lit edge) is drawn, not composed: over one second of frames the timeline's
 * rows, the composer, the dock and the card's root never recompose, the timer recomposes as its second
 * ticks over, and the reply's text never recomposes (its lit edge moves at draw time).
 */
class VoiceRecompositionTest {
    private val counts = mutableMapOf<String, Int>()
    private val count: (String) -> Unit = { key -> counts[key] = (counts[key] ?: 0) + 1 }

    private val reply = "I can help refactor code, draft pull requests, or summarize long documents. Which one first?"
    private val messages = listOf(
        UiMessage(id = "u1", role = "user", content = "What can you do?", timestamp = "2026-11-18T09:00:00Z"),
        UiMessage(id = "a1", role = "assistant", content = reply, timestamp = "2026-11-18T09:00:02Z"),
    )

    @Test
    fun aSecondOfSpeakingRecomposesOnlyTheTimer() = runComposeUiTest {
        val feed = FakeVoiceCardFeed(clock = 1_000)
        val playback = MutableStateFlow(0.2f)
        mainClock.autoAdvance = false
        show(feed, playback)
        // The reply starts playing: its first piece, an estimated 4 s long.
        feed.spokenSegments.value = listOf(
            SpokenSegment(
                0,
                VoiceText("I can help refactor code, draft pull requests,"),
                startedAtMs = 1_200,
                audioDurationMs = 4_000,
                finishedAtMs = null,
            ),
        )
        repeat(SETTLE_FRAMES) { mainClock.advanceTimeByFrame() }
        waitForIdle()
        counts.clear()

        repeat(FRAMES_PER_SECOND) { frame ->
            feed.clock += FRAME_MS
            playback.value = if (frame % 2 == 0) 0.9f else 0.1f
            mainClock.advanceTimeByFrame()
        }
        waitForIdle()

        println("recompositions over one second of speaking: $counts")
        val rows = counts.filterKeys { it !in VOICE_SCOPES }.values.sum()
        assertEquals(0, rows, "the timeline's rows never recompose: $counts")
        assertEquals(0, counts[PROBE_COMPOSER] ?: 0, "the composer never recomposes: $counts")
        assertEquals(0, counts[PROBE_DOCK] ?: 0, "the dock never recomposes: $counts")
        assertEquals(0, counts[PROBE_CARD] ?: 0, "the card's root never recomposes: $counts")
        assertEquals(0, counts[PROBE_SPOKEN] ?: 0, "the reply's text never recomposes: $counts")
        assertEquals(0, counts[PROBE_BODY] ?: 0, "the card's body never recomposes: $counts")
        assertTrue((counts[PROBE_TIMER] ?: 0) <= 2, "the timer recomposes as its second ticks over, no more: $counts")
    }

    /** Live partials (one per stretch of voiced audio) recompose the card's body and nothing above it. */
    @Test
    fun livePartialsRecomposeOnlyTheCardsBody() = runComposeUiTest {
        val feed = FakeVoiceCardFeed(clock = 1_000)
        val words = MutableStateFlow<VoiceText?>(null)
        mainClock.autoAdvance = false
        show(feed, MutableStateFlow(0.3f), VoiceModeState.Hearing, words)
        repeat(SETTLE_FRAMES) { mainClock.advanceTimeByFrame() }
        waitForIdle()
        counts.clear()

        val partials = listOf("can you", "can you check the", "can you check the build", "can you check the build again")
        partials.forEach { partial ->
            words.value = VoiceText(partial)
            repeat(FRAMES_PER_PARTIAL) {
                feed.clock += FRAME_MS
                mainClock.advanceTimeByFrame()
            }
        }
        waitForIdle()

        println("recompositions over ${partials.size} live partials: $counts")
        val rows = counts.filterKeys { it !in VOICE_SCOPES }.values.sum()
        assertEquals(0, rows, "the timeline's rows never recompose: $counts")
        assertEquals(0, counts[PROBE_COMPOSER] ?: 0, "the composer never recomposes: $counts")
        assertEquals(0, counts[PROBE_DOCK] ?: 0, "the dock never recomposes: $counts")
        assertEquals(0, counts[PROBE_CARD] ?: 0, "the card's root never recomposes: $counts")
        assertTrue((counts[PROBE_BODY] ?: 0) <= partials.size, "the body recomposes once per partial at most: $counts")
    }

    private fun ComposeUiTest.show(
        feed: FakeVoiceCardFeed,
        playback: MutableStateFlow<Float>,
        voice: VoiceModeState = VoiceModeState.Speaking,
        words: MutableStateFlow<VoiceText?> = MutableStateFlow(null),
    ) = setContent {
        val scope = rememberCoroutineScope()
        val handle = remember {
            val engines = VoiceEngineFactory { VoiceEngineSet(VoiceEngines(CardQuietEngines, CardQuietEngines, CardQuietEngines)) {} }
            val controller = VoiceModeController(
                VoiceChatSessions { null },
                VoiceSetupStore(InMemorySecureSettingsStore(), null),
                engines,
                MicrophoneAccess.Granted,
                VoiceLoopRuntime(scope, Dispatchers.Default),
            )
            VoiceModeHandle(
                controller,
                state = MutableStateFlow(voice),
                playbackLevel = playback,
                microphoneLevel = playback,
                partialTranscript = words,
                cardFeed = feed,
            )
        }
        val state = remember {
            ChatUiState(
                conversationState = ConversationState.Ready("c1"),
                isLoadingMessages = false,
                messages = messages.toPersistentList(),
                agentName = "Scout",
            )
        }
        val actions = remember { RecordingChatActions() }
        MaterialTheme {
            CompositionLocalProvider(
                LocalVoiceMode provides handle,
                LocalRowComposed provides count,
                LocalRecompositionProbe provides count,
            ) {
                Column(Modifier.size(width = 900.dp, height = 900.dp)) {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        ChatTimeline(
                            state = state,
                            pagedTimeline = null,
                            actions = actions,
                            capabilities = ChatSurfaceCapabilities.Default,
                            host = ChatSurfaceHost(),
                            appearance = ChatSurfaceAppearance(),
                        )
                    }
                    ChatComposerPanel(
                        ComposerInputs(
                            composer = COMPOSER,
                            actions = actions,
                            uiState = state,
                        ),
                    )
                }
            }
        }
    }

    private companion object {
        const val SETTLE_FRAMES = 30
        const val FRAMES_PER_SECOND = 60
        const val FRAME_MS = 16L
        const val FRAMES_PER_PARTIAL = 36
        val VOICE_SCOPES = setOf(PROBE_COMPOSER, PROBE_DOCK, PROBE_CARD, PROBE_SPOKEN, PROBE_TIMER, PROBE_BODY)
        val COMPOSER = ChatComposerUiState(
            text = "",
            permissionMode = ChatPermissionModeUiState(selected = AppServerPermissionMode.Unrestricted),
        )
    }
}
