@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.presence.RunPhase
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.engine.SpeechSegment

import com.letta.mobile.data.voice.engine.VoiceEar
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.engine.VoiceMouth
import com.letta.mobile.data.voice.engine.VoiceMouthEvent
import com.letta.mobile.data.voice.engine.VoiceReply
import com.letta.mobile.data.voice.engine.VoiceResponder
import com.letta.mobile.data.voice.loop.VoiceEngines
import com.letta.mobile.data.voice.loop.VoiceLoopRuntime
import com.letta.mobile.data.voice.session.MicrophoneAccess
import com.letta.mobile.data.voice.session.VoiceChatSessions
import com.letta.mobile.data.voice.session.VoiceEngineFactory
import com.letta.mobile.data.voice.session.VoiceEngineSet
import com.letta.mobile.data.voice.session.VoiceModeController
import com.letta.mobile.data.voice.session.VoiceModeState
import com.letta.mobile.data.voice.session.VoiceSetupStore
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.voice.LocalVoiceMode
import com.letta.mobile.ui.chat.voice.VoiceModeHandle
import com.letta.mobile.ui.components.beam.LettaBeamDefaults
import com.letta.mobile.ui.components.beam.LettaBeamSpec
import com.letta.mobile.ui.components.beam.lettaBorderBeam
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.theme.SharedMaterialTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** Pixel and composition oracles for the prompt's shared treatment; no fake transport is involved. */
class ComposerOutlineRenderTest {
    @Test
    fun movingOutlineDoesNotRecordItsParentAgain() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        mainClock.autoAdvance = false
        var parentDraws = 0
        setContent {
            SharedMaterialTheme {
                Box(Modifier.fillMaxSize().drawWithContent {
                    parentDraws++
                    drawContent()
                }) {
                    Box(Modifier.fillMaxSize().composerOutline(SHAPE, ChatUiState(runPhase = RunPhase.WORKING)))
                }
            }
        }
        waitForIdle()
        onRoot().captureToImage()
        val before = parentDraws
        mainClock.advanceTimeBy(MOVEMENT_MS)
        waitForIdle()
        onRoot().captureToImage()
        assertEquals(before, parentDraws, "a moving composer must not record the surrounding page again")
    }

    @Test
    fun voiceModesKeepTheirRoleAndReportTheSelectedTreatment() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        val voice = MutableStateFlow<VoiceModeState>(VoiceModeState.Off)
        setContent {
            val handle = pinnedVoice(voice)
            SharedMaterialTheme {
                CompositionLocalProvider(LocalVoiceMode provides handle, LocalReducedMotion provides true) {
                    Box(
                        Modifier.testTag(PROBE).fillMaxSize().background(Color.Black)
                            .composerOutline(SHAPE, ChatUiState(runPhase = RunPhase.WORKING)),
                    )
                }
            }
        }
        waitForIdle()
        val workingEnergy = onRoot().captureToImage().toPixelMap().energy()
        listOf(
            Triple(VoiceModeState.Listening, ComposerOutlineMode.Voice, VoiceGlowRole.You),
            Triple(VoiceModeState.Hearing, ComposerOutlineMode.Voice, VoiceGlowRole.You),
            Triple(VoiceModeState.WaitingForReply, ComposerOutlineMode.Working, VoiceGlowRole.Agent),
            Triple(VoiceModeState.Speaking, ComposerOutlineMode.Voice, VoiceGlowRole.Agent),
            Triple(VoiceModeState.Error(VoiceErrorCode.ENGINE_FAILED), ComposerOutlineMode.Error, VoiceGlowRole.Error),
        ).forEach { (state, mode, role) ->
            voice.value = state
            waitForIdle()
            val semantics = onNodeWithTag(PROBE).fetchSemanticsNode().config
            assertEquals(mode, semantics[ComposerOutlineModeKey])
            assertEquals(role, semantics[VoiceOutlineRoleKey])
            val energy = onRoot().captureToImage().toPixelMap().energy()
            assertTrue(energy > 0f, "$mode draws an outline")
            if (mode == ComposerOutlineMode.Working) assertEquals(workingEnergy, energy, "working never stacks voice strokes on the beam")
        }
    }

    /** The controller is never started: all unused engine operations reject calls. */
    @Composable
    private fun pinnedVoice(state: MutableStateFlow<VoiceModeState>): VoiceModeHandle {
        val scope = rememberCoroutineScope()
        return remember {
            val store = VoiceSetupStore(InMemorySecureSettingsStore(), InMemorySecureSettingsStore())
            val ear = object : VoiceEar {
                override fun listen(): Flow<VoiceEarEvent> = error("unused visual fixture")
                override fun commitTurn(): Unit = error("unused visual fixture")
            }
            val mouth = object : VoiceMouth {
                override val events: Flow<VoiceMouthEvent> = emptyFlow()
                override fun speak(segment: SpeechSegment): Unit = error("unused visual fixture")
                override fun flush(): Unit = error("unused visual fixture")
                override suspend fun stop() = Unit
            }
            val responder = object : VoiceResponder {
                override fun respond(utterance: VoiceUtterance): VoiceReply = error("unused visual fixture")
            }
            val engines = VoiceEngineFactory { VoiceEngineSet(VoiceEngines(ear, responder, mouth)) {} }
            val controller = VoiceModeController(
                VoiceChatSessions { null }, store, engines, MicrophoneAccess.Granted,
                VoiceLoopRuntime(scope, Dispatchers.Default),
            )
            VoiceModeHandle(controller, state = state)
        }
    }

    @Test
    fun phaseModesHavePixelsAndSemantics() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        var phase by mutableStateOf(RunPhase.IDLE)
        setContent {
            SharedMaterialTheme {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Box(Modifier.testTag(PROBE).fillMaxSize().background(Color.Black).composerOutline(SHAPE, ChatUiState(runPhase = phase)))
                }
            }
        }
        fun energyFor(value: RunPhase, mode: ComposerOutlineMode): Float {
            phase = value
            waitForIdle()
            assertEquals(mode, onNodeWithTag(PROBE).fetchSemanticsNode().config[ComposerOutlineModeKey])
            val pixels = onRoot().captureToImage().toPixelMap()
            assertEquals(Color.Black, pixels[WIDTH / 2, HEIGHT / 2], "the card's middle stays untouched")
            return pixels.energy()
        }
        assertEquals(0f, energyFor(RunPhase.IDLE, ComposerOutlineMode.Idle))
        val thinking = energyFor(RunPhase.REASONING, ComposerOutlineMode.Thinking)
        val working = energyFor(RunPhase.WORKING, ComposerOutlineMode.Working)
        assertTrue(thinking > 0f, "thinking has a visible thin edge")
        assertTrue(thinking < working, "thinking energy $thinking is lighter than working $working")
        assertEquals(0f, energyFor(RunPhase.AWAITING_INPUT, ComposerOutlineMode.Idle))
    }

    @Test
    fun activeComposerLightStaysAtTopEdge() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        var phase by mutableStateOf(RunPhase.REASONING)
        setContent {
            SharedMaterialTheme {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Box(Modifier.fillMaxSize().background(Color.Black).composerOutline(SHAPE, ChatUiState(runPhase = phase)))
                }
            }
        }
        for (value in listOf(RunPhase.REASONING, RunPhase.WORKING)) {
            phase = value
            waitForIdle()
            val pixels = onRoot().captureToImage().toPixelMap()
            var outside = 0f
            for (y in 16 until HEIGHT) for (x in 0 until WIDTH) {
                val color = pixels[x, y]
                outside += color.red + color.green + color.blue
            }
            assertTrue(outside < 1f, "$value light below top band: $outside")
        }
    }

    @Test
    fun changingModeFadesInsteadOfSnapping() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        mainClock.autoAdvance = false
        var phase by mutableStateOf(RunPhase.REASONING)
        setContent {
            SharedMaterialTheme {
                Box(Modifier.testTag(PROBE).fillMaxSize().background(Color.Black).composerOutline(SHAPE, ChatUiState(runPhase = phase)))
            }
        }
        waitForIdle()
        val lit = onRoot().captureToImage().toPixelMap().energy()
        phase = RunPhase.IDLE
        mainClock.advanceTimeByFrame()
        waitForIdle()
        val starting = onRoot().captureToImage().toPixelMap().energy()
        mainClock.advanceTimeBy(FADE_SAMPLE_MS)
        waitForIdle()
        val fading = onRoot().captureToImage().toPixelMap().energy()
        mainClock.advanceTimeBy(FADE_FINISH_MS)
        waitForIdle()
        val gone = onRoot().captureToImage().toPixelMap().energy()
        assertTrue(starting > 0f, "the old outline survives the state change")
        assertTrue(fading > 0f && fading < lit, "intermediate pixels fade: $lit -> $fading")
        assertEquals(0f, gone)
    }

    @Test
    fun workingFramesNeverRecomposeAndReducedMotionIsStill() {
        listOf(false, true).forEach { reduced ->
            runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
                mainClock.autoAdvance = false
                var compositions = 0
                var draws = 0
                var outlines = 0
                val countingShape = object : Shape {
                    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
                        outlines++
                        return SHAPE.createOutline(size, layoutDirection, density)
                    }
                }
                setContent {
                    SharedMaterialTheme {
                        CompositionLocalProvider(LocalReducedMotion provides reduced) {
                            Box(Modifier.testTag(PROBE).fillMaxSize().background(Color.Black)
                                .composerOutline(countingShape, ChatUiState(runPhase = RunPhase.WORKING)).drawWithContent {
                                draws++
                                drawContent()
                            })
                            SideEffect { compositions++ }
                        }
                    }
                }
                waitForIdle()
                val before = onRoot().captureToImage().toPixelMap()
                val baseline = compositions
                val initialDraws = draws
                val initialOutlines = outlines
                mainClock.advanceTimeBy(MOVEMENT_MS)
                waitForIdle()
                assertEquals(initialOutlines, outlines, "moving frames reuse outline geometry")
                if (reduced) assertEquals(initialDraws, draws, "reduced motion schedules no drawing frames")
                val after = onRoot().captureToImage().toPixelMap()
                assertEquals(baseline, compositions, "a beam frame never recomposes its composer")
                val changed = (0 until WIDTH).any { x -> (0 until HEIGHT).any { y -> before[x, y] != after[x, y] } }
                assertEquals(!reduced, changed, "motion preference $reduced")
            }
        }
    }

    @Test
    fun statefulShapeChangesInvalidateCachedBeamGeometry() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        var corner by mutableStateOf(16.dp)
        val shape = object : Shape {
            override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
                RoundedCornerShape(corner).createOutline(size, layoutDirection, density)
        }
        setContent {
            SharedMaterialTheme {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Box(
                        Modifier.fillMaxSize().background(Color.Black)
                            .lettaBorderBeam(LettaBeamSpec(LettaBeamDefaults.colors(), shape)),
                    )
                }
            }
        }
        waitForIdle()
        val rounded = onRoot().captureToImage().toPixelMap()
        corner = 0.dp
        waitForIdle()
        val square = onRoot().captureToImage().toPixelMap()
        assertTrue((0 until WIDTH).any { x -> (0 until HEIGHT).any { y -> rounded[x, y] != square[x, y] } },
            "a stateful shape changes the beam's pixels without replacing its instance")
    }

    private fun PixelMap.energy(): Float {
        var sum = 0f
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = this[x, y]
                sum += pixel.red + pixel.green + pixel.blue
            }
        }
        return sum
    }

    private companion object {
        const val PROBE = "composer-outline-probe"
        const val WIDTH = 360
        const val HEIGHT = 100
        const val FADE_SAMPLE_MS = 120L
        const val FADE_FINISH_MS = 2000L
        const val MOVEMENT_MS = 1000L
        val SHAPE = RoundedCornerShape(16.dp)
    }
}
