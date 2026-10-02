@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.session.ChatSurfaceModeReducer
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.mascot.FakeMascotHost
import com.letta.mobile.ui.mascot.FakeMascotShell
import com.letta.mobile.ui.mascot.LocalMascotHost
import com.letta.mobile.ui.mascot.MascotEntry
import com.letta.mobile.ui.mascot.MascotHost
import com.letta.mobile.ui.mascot.MascotStage
import com.letta.mobile.ui.mascot.MascotTransport
import com.letta.mobile.ui.mascot.MascotTransportLayer
import com.letta.mobile.avatar.core.MascotIdentity
import androidx.compose.runtime.CompositionLocalProvider
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.collections.immutable.toPersistentList

/**
 * letta-mobile-bglj6.1: renders the whole chat page over a canvas, with a mascot under a
 * transport layer, and counts what is created and disposed while it moves between docked,
 * minimised and full screen. Every frame can be captured to PNG and its brightness measured, so
 * a blink (a frame that jumps away from the eased curve and back) shows up as numbers.
 */
internal class ChatSurfaceFlickerRig(val name: String) {
    /** Lifetimes of one kind of thing: how many were created and how many went away. */
    class Lifetimes {
        var created = 0
        var disposed = 0
        val live get() = created - disposed

        @Composable
        fun Track() {
            DisposableEffect(Unit) {
                created++
                onDispose { disposed++ }
            }
        }

        override fun toString() = "created=$created disposed=$disposed"
    }

    val canvas = Lifetimes()
    val timeline = Lifetimes()
    val composer = Lifetimes()
    val mascotScene = Lifetimes()

    val shell = FakeMascotShell(AGENT)
    var presentation by mutableStateOf(ChatSurfacePresentation.CanvasFirst)
    var geometry by mutableStateOf(ChatDockGeometry(anchorX = 0.5f, anchorY = 1f, widthDp = 460f, heightDp = 420f))

    /** One line per captured frame. */
    val frames = mutableListOf<Frame>()

    data class Frame(
        val label: String,
        val index: Int,
        val luminance: Double,
        val seated: Boolean,
        val seatHandlers: Int?,
        val activeStage: MascotStage?,
        val timelineLive: Int,
        val composerLive: Int,
        val mascotSceneLive: Int,
    )

    private val seatIdentities = LinkedHashSet<Int>()

    /** Distinct seat registrations seen for the companion stage (a new one per remount). */
    val seatRegistrations: Int get() = seatIdentities.size

    fun raise(intent: ChatSurfaceIntent) {
        presentation = ChatSurfaceModeReducer.reduce(presentation, intent)
    }

    private val messages = (0 until MESSAGE_COUNT).map { i ->
        UiMessage(
            id = "m$i",
            role = if (i % 2 == 0) "user" else "assistant",
            content = if (i % 2 == 0) "Question number $i about the kitchen plan?" else "Answer $i: **move** the island, keep the sink.",
            timestamp = "2026-09-30T18:%02d:00Z".format(i),
            runId = if (i % 2 == 0) null else "run-$i",
        )
    }

    val port = ChatSurfaceUiTest.TestPort(
        ChatUiState(
            conversationState = ConversationState.Ready("conv-1"),
            isLoadingMessages = false,
            agentName = "Meridian",
            agentId = AGENT,
            messages = messages.toPersistentList(),
        ),
        ChatComposerUiState(text = "Make it vegetarian", canSend = true),
    )

    /** Paints the mascot as a solid square so it shows in the frames, and counts its scenes. */
    private val paintedHost = object : MascotHost {
        override val available: Boolean = true

        override fun entry(agentId: String, identity: MascotIdentity): MascotEntry = FakeMascotHost.entry(agentId, identity)

        @Composable
        override fun Surface(entry: MascotEntry, modifier: Modifier, playing: Boolean) {
            mascotScene.Track()
            Box(modifier.background(MASCOT_COLOR))
        }
    }

    fun ComposeUiTest.mount(reducedMotion: Boolean = false) {
        mainClock.autoAdvance = false
        setContent {
            shell.Provide {
                CompositionLocalProvider(
                    LocalMascotHost provides paintedHost,
                    com.letta.mobile.ui.theme.LocalReducedMotion provides reducedMotion,
                ) {
                    MaterialTheme(colorScheme = lightColorScheme()) {
                        MascotTransportLayer(reducedMotion = reducedMotion) {
                            Box(Modifier.size(width = WIDTH.dp, height = HEIGHT.dp).testTag(ROOT_TAG)) {
                                ChatSurface(
                                    port = port,
                                    presentation = presentation,
                                    onIntent = ::raise,
                                    host = ChatSurfaceHost(openCanvas = {}),
                                    platform = ChatSurfacePlatform(
                                        timelineOverlay = { timeline.Track() },
                                        voiceInput = { _ -> composer.Track() },
                                    ),
                                    canvas = { _ ->
                                        canvas.Track()
                                        Box(Modifier.fillMaxSize().background(CANVAS_COLOR))
                                    },
                                    dockGeometry = geometry,
                                    onDockGeometryChange = { geometry = it },
                                )
                            }
                        }
                    }
                }
            }
        }
        frame(SETTLE_FRAMES)
    }

    /** Advances [count] frames without capturing. */
    fun ComposeUiTest.frame(count: Int = 1) {
        repeat(count) {
            mainClock.advanceTimeBy(FRAME_MILLIS)
            observeSeat()
        }
    }

    private fun observeSeat() {
        shell.transport.seat(MascotTransport.SeatKey(AGENT, MascotStage.COMPOSER_COMPANION))?.let {
            seatIdentities += System.identityHashCode(it.handlers)
        }
    }

    /**
     * Advances [count] frames, measuring each; with CHAT_FLICKER_FRAMES set in the environment
     * each is also written to `build/chat-surface-snapshots/flicker/<name>-<label>-NNN.png`.
     * [probe] runs after each frame.
     */
    fun ComposeUiTest.capture(label: String, count: Int, write: Boolean = WRITE_FRAMES, probe: () -> Unit = {}) {
        repeat(count) { i ->
            mainClock.advanceTimeBy(FRAME_MILLIS)
            observeSeat()
            probe()
            val image = onRoot().captureToImage().toAwtImage()
            if (write) ImageIO.write(image, "png", outDir.resolve("$name-$label-%03d.png".format(i)))
            val seat = shell.transport.seat(MascotTransport.SeatKey(AGENT, MascotStage.COMPOSER_COMPANION))
            frames += Frame(
                label = label,
                index = i,
                luminance = meanLuminance(image),
                seated = seat != null,
                seatHandlers = seat?.let { System.identityHashCode(it.handlers) },
                activeStage = shell.transport.activeStage(AGENT),
                timelineLive = timeline.live,
                composerLive = composer.live,
                mascotSceneLive = mascotScene.live,
            )
        }
    }

    /**
     * Frames whose brightness jumps against both neighbours in the same direction by more than
     * [threshold] (0..1): a blink rather than a step along the eased curve.
     */
    fun blinks(label: String, threshold: Double = BLINK_THRESHOLD): List<Frame> {
        val series = frames.filter { it.label == label }
        return series.indices.drop(1).dropLast(1).map { series[it] }.filter { f ->
            val i = series.indexOf(f)
            val before = f.luminance - series[i - 1].luminance
            val after = f.luminance - series[i + 1].luminance
            (before > threshold && after > threshold) || (before < -threshold && after < -threshold)
        }
    }

    fun report(): String = buildString {
        appendLine("== $name ==")
        appendLine("canvas $canvas, timeline $timeline, composer $composer, mascot scene $mascotScene, seat registrations $seatRegistrations")
        frames.forEach { f ->
            appendLine(
                "%-10s %03d lum=%.4f seated=%-5s seat=%-10s stage=%-20s timeline=%d composer=%d scene=%d".format(
                    f.label, f.index, f.luminance, f.seated, f.seatHandlers, f.activeStage, f.timelineLive, f.composerLive, f.mascotSceneLive,
                ),
            )
        }
    }

    fun writeReport() {
        outDir.resolve("$name-report.txt").writeText(report())
    }

    private val outDir: File get() = File("build/chat-surface-snapshots/flicker").apply { mkdirs() }

    companion object {
        const val AGENT = "agent-1"
        const val ROOT_TAG = "flicker-root"
        const val WIDTH = 1000
        const val HEIGHT = 800
        const val FRAME_MILLIS = 16L
        const val SETTLE_FRAMES = 60
        const val MESSAGE_COUNT = 20
        /** A mascot-sized square vanishing for a frame moves the mean by about 0.01. */
        const val BLINK_THRESHOLD = 0.004
        val WRITE_FRAMES = System.getenv("CHAT_FLICKER_FRAMES") != null
        val CANVAS_COLOR = Color(0xFF3355AA)
        val MASCOT_COLOR = Color(0xFFFF00FF)

        fun meanLuminance(image: BufferedImage): Double {
            var sum = 0.0
            val step = 2
            var n = 0
            for (y in 0 until image.height step step) {
                for (x in 0 until image.width step step) {
                    val rgb = image.getRGB(x, y)
                    val r = (rgb shr 16) and 0xFF
                    val g = (rgb shr 8) and 0xFF
                    val b = rgb and 0xFF
                    sum += (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
                    n++
                }
            }
            return sum / n
        }
    }
}
