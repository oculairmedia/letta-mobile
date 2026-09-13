package com.letta.mobile.desktop.avatar.rive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.letta.mobile.avatar.core.AvatarLookTarget
import com.letta.mobile.avatar.core.AvatarState
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.core.MascotPalette
import com.letta.mobile.avatar.core.MascotShape
import com.letta.mobile.avatar.rive.MASCOT_MODEL
import com.letta.mobile.avatar.rive.RiveAvatarContract
import com.letta.mobile.avatar.rive.RiveAvatarRuntime
import java.io.File
import kotlinx.coroutines.delay

/** `-Drive.spike.selfTest=true` starts with the auto-cycle on (states, identities, triggers). */
private val SELF_TEST = System.getProperty("rive.spike.selfTest").toBoolean()

/**
 * letta-mobile-0s5bi spike window. Two native Rive scenes side by side, both plain Compose nodes:
 *
 *  - Left (optional): any `.riv` (`-PriveFile`), clickable, with its state machine's trigger inputs
 *    exposed as buttons. This is the feathering check - the glow and blur must survive.
 *  - Right: the shipped mascot driven through the SAME [RiveAvatarRuntime] Android uses, over the
 *    desktop [RiveDesktopScene.inputSink], inside a review bench: auto-cycle toggle, every state,
 *    identity (shape x colour), gaze from the cursor or sliders, mouth, and the surround - page
 *    and frame as art-directable grounds (base colour plus draggable radial lights), the frame in
 *    several shapes at any size, the mascot scaled inside it so it can be clipped.
 *
 * Args: `<file.riv> [stateMachine] [trigger,trigger,...] [mascot.riv]`.
 */
fun main(args: Array<String>) = application {
    // Gradle passes every arg, blank when the property is unset; a blank path is "not given".
    val file = args.getOrNull(0)?.takeIf { it.isNotBlank() }?.let(::File)
    val stateMachine = args.getOrNull(1)?.takeIf { it.isNotBlank() }
    val triggers = args.getOrNull(2)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
    val mascot = args.getOrNull(3)?.takeIf { it.isNotBlank() }?.let(::File)

    Window(
        onCloseRequest = ::exitApplication,
        title = "Rive native spike (D3D11)",
        state = rememberWindowState(width = 1320.dp, height = 900.dp),
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Row(Modifier.fillMaxSize().background(Color(0xFF1A1A1A)), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                if (file != null) {
                    SceneColumn(file, stateMachine, triggers, Modifier.weight(1f).padding(16.dp))
                }
                if (mascot != null) {
                    MascotBench(mascot, Modifier.weight(if (file != null) 1.4f else 1f))
                }
            }
        }
    }
}

@Composable
private fun rememberScene(bytes: ByteArray, stateMachine: String?): RiveDesktopScene {
    val scene = remember(bytes) { RiveDesktopScene.create().also { it.load(bytes, stateMachine) } }
    DisposableEffect(scene) { onDispose { scene.close() } }
    return scene
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SceneColumn(file: File, stateMachine: String?, triggers: List<String>, modifier: Modifier) {
    val scene = rememberScene(remember(file) { file.readBytes() }, stateMachine)
    var nativeMs by remember { mutableStateOf(0.0) }
    LaunchedEffect(scene) {
        if (!SELF_TEST || triggers.isEmpty()) return@LaunchedEffect
        while (true) {
            for (name in triggers) {
                println("rive-spike self-test: trigger $name fired=${scene.fireTrigger(name)}")
                delay(2000)
            }
        }
    }
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${file.name}  |  ${scene.adapterName}  |  render+readback %.2f ms".format(nativeMs), color = Color.LightGray)
        RiveDesktopSurface(scene, Modifier.weight(1f).fillMaxSize(), onFrameStats = { nativeMs = it })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            triggers.forEach { name -> OutlinedButton(onClick = { scene.fireTrigger(name) }) { Text(name) } }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Review bench
// ---------------------------------------------------------------------------------------------

/** Surrounds worth judging the mascot against: the app's darks and lights plus the palette itself. */
private val SURROUND_COLORS: List<Int> = listOf(
    0xFF000000.toInt(), 0xFF101010.toInt(), 0xFF1A1A1A.toInt(), 0xFF242424.toInt(), 0xFF2E2E33.toInt(), 0xFF3A3A40.toInt(),
    0xFF5A5A60.toInt(), 0xFF8A8A90.toInt(), 0xFFBFBFC4.toInt(), 0xFFE6E6EA.toInt(), 0xFFF5F5F7.toInt(), 0xFFFFFFFF.toInt(),
    0xFF0F1B2D.toInt(), 0xFF1B2A1F.toInt(), 0xFF2B1B2E.toInt(), 0xFF2E2416.toInt(),
) + MascotPalette.ALL

private enum class FrameShape(val label: String, val shape: Shape) {
    NONE("none", RectangleShape),
    CIRCLE("circle", CircleShape),
    SQUIRCLE("squircle", RoundedCornerShape(30)),
    ROUNDED("rounded", RoundedCornerShape(16.dp)),
    SQUARE("square", RectangleShape),
}

private enum class Ground(val label: String) { PAGE("page"), FRAME("frame") }

/** How the host drives the gaze. JUSTIFIED is the product behaviour; CURSOR is for checking range. */
private enum class GazeMode(val label: String) { JUSTIFIED("justified"), CURSOR("cursor"), OFF("sliders") }

/**
 * What the character can be looking at. Every look has one of these, so a viewer could name
 * the reason. OWN is "its own thoughts": the host writes no gaze and the rig's per-state default
 * shows through (thinking up-left, error down, idle drifting).
 */
private enum class GazeTarget(val label: String, val reason: String) {
    OWN("own thoughts", "the rig's default for this state"),
    USER("you", "addressing the person: straight at the camera"),
    CURSOR("the cursor", "your hand moved"),
    INPUT("the input", "watching you type"),
    TIMELINE("the timeline", "reading the code / its own reply"),
}

/** One justified look: the target, how likely, how long it holds, and the pause before the next. */
private data class Look(val target: GazeTarget, val weight: Int, val dwellMs: LongRange, val gapMs: LongRange = 500L..2500L)

/** The director's gaze plan per state: who it would plausibly be looking at, and how much. */
private val GAZE_PLAN: Map<AvatarState, List<Look>> = mapOf(
    AvatarState.IDLE to listOf(Look(GazeTarget.OWN, 50, 3000L..7000L), Look(GazeTarget.USER, 25, 1500L..3500L), Look(GazeTarget.CURSOR, 25, 1500L..3000L)),
    AvatarState.LISTENING to listOf(Look(GazeTarget.INPUT, 70, 3000L..8000L, 300L..1200L), Look(GazeTarget.USER, 20, 1000L..2500L), Look(GazeTarget.CURSOR, 10, 1000L..2000L)),
    AvatarState.THINKING to listOf(Look(GazeTarget.OWN, 60, 3000L..8000L), Look(GazeTarget.TIMELINE, 30, 2000L..5000L), Look(GazeTarget.INPUT, 10, 1000L..2500L)),
    AvatarState.SPEAKING to listOf(Look(GazeTarget.USER, 55, 2500L..6000L, 300L..1500L), Look(GazeTarget.TIMELINE, 35, 1500L..4000L), Look(GazeTarget.CURSOR, 10, 1000L..2000L)),
    AvatarState.WAITING_INPUT to listOf(Look(GazeTarget.USER, 80, 4000L..9000L, 300L..1000L), Look(GazeTarget.CURSOR, 20, 1500L..3000L)),
    AvatarState.DRAGGED to listOf(Look(GazeTarget.CURSOR, 100, 10_000L..10_000L, 0L..0L)),
    AvatarState.SUCCESS to listOf(Look(GazeTarget.USER, 100, 3000L..3000L, 0L..0L)),
    AvatarState.ERROR to listOf(Look(GazeTarget.OWN, 70, 3000L..7000L), Look(GazeTarget.USER, 30, 1500L..3000L)),
    AvatarState.SLEEPING to listOf(Look(GazeTarget.OWN, 100, 60_000L..60_000L)),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MascotBench(file: File, modifier: Modifier) {
    val scene = rememberScene(remember(file) { file.readBytes() }, null)
    val runtime = remember(scene) { RiveAvatarRuntime(scene.inputSink) }

    var autoCycle by remember { mutableStateOf(SELF_TEST) }
    var current by remember { mutableStateOf(AvatarState.IDLE) }
    var identity by remember { mutableStateOf(MascotIdentity.DEFAULT) }
    var mouth by remember { mutableFloatStateOf(0f) }
    var lookX by remember { mutableFloatStateOf(0f) }
    var lookY by remember { mutableFloatStateOf(0f) }
    var gazeMode by remember { mutableStateOf(GazeMode.JUSTIFIED) }
    var trackReach by remember { mutableFloatStateOf(2.5f) }   // how far (in mascot widths) the gaze saturates
    var cursorLook by remember { mutableStateOf(0f to 0f) }    // where the cursor is, in gaze units
    var lastCursorMove by remember { mutableStateOf(0L) }
    var target by remember { mutableStateOf(GazeTarget.OWN) }  // what it is looking at right now
    var showTargets by remember { mutableStateOf(true) }
    var stageSize by remember { mutableStateOf(IntSize.Zero) }
    var mascotBounds by remember { mutableStateOf(Rect.Zero) }
    var headTarget by remember { mutableStateOf(0f to 0f) }     // where the head is turning (host facing, -1..1)
    var headLead by remember { mutableFloatStateOf(350f) }      // ms the eyes lead the head by

    var page by remember { mutableStateOf(Surround(0xFF1A1A1A.toInt())) }
    var frame by remember { mutableStateOf(Surround(0xFF2E2E33.toInt())) }
    var frameShape by remember { mutableStateOf(FrameShape.NONE) }
    var frameSize by remember { mutableFloatStateOf(360f) }
    var mascotScale by remember { mutableFloatStateOf(1f) }     // mascot box = frame x scale; > 1 clips
    var editGradients by remember { mutableStateOf(false) }
    var ground by remember { mutableStateOf(Ground.PAGE) }
    var selPage by remember { mutableIntStateOf(-1) }
    var selFrame by remember { mutableIntStateOf(-1) }
    var loaded by remember { mutableStateOf(false) }
    val tune = remember { mutableStateMapOf("tuneScale" to 0.5f, "tunePlate" to 0.5f, "tuneGlyph" to 0.5f, "tuneMouth" to 0.5f, "tuneMouthY" to 0.5f) }

    fun setState(state: AvatarState) {
        current = state
        runtime.applyState(state)
    }

    fun setIdentity(value: MascotIdentity) {
        identity = value
        RiveAvatarContract.applyIdentity(scene.inputSink, value)
    }

    fun setLook(x: Float, y: Float) {
        lookX = x.coerceIn(-1f, 1f); lookY = y.coerceIn(-1f, 1f)
        // Screen space is 0..1; the runtime maps it to the contract's -1..1.
        runtime.setLookTarget(AvatarLookTarget.Screen((lookX + 1f) / 2f, (lookY + 1f) / 2f))
    }

    // Identity before the first frame (see rive/mascot/README.md, host rules), then the runtime.
    LaunchedEffect(runtime) {
        RiveAvatarContract.applyIdentity(scene.inputSink, identity)
        runtime.load(MASCOT_MODEL)
        loaded = true
    }

    // Where the UI's things are, in gaze units from the mascot's centre. The bench fakes an input
    // field below and a timeline to the left; the product supplies the real rectangles.
    fun lookAt(px: Float, py: Float): Pair<Float, Float> {
        if (mascotBounds.isEmpty) return 0f to 0f
        val reach = mascotBounds.width * trackReach / 2f
        return ((px - mascotBounds.center.x) / reach).coerceIn(-1f, 1f) to ((py - mascotBounds.center.y) / reach).coerceIn(-1f, 1f)
    }
    val inputSpot = androidx.compose.ui.geometry.Offset(stageSize.width / 2f, stageSize.height - 60f)
    val timelineSpot = androidx.compose.ui.geometry.Offset(150f, stageSize.height / 2f - 80f)

    // Justified attention: the reference for the director's gaze rule. The rig has its own
    // per-state gaze; the host lends it a target the viewer could name - the input while they
    // type, the timeline while it reads or writes, the person when it addresses them, the cursor
    // when their hand moves - holds it for a while, then hands the gaze back.
    LaunchedEffect(gazeMode, current) {
        target = when (gazeMode) { GazeMode.CURSOR -> GazeTarget.CURSOR; else -> GazeTarget.OWN }
        if (gazeMode != GazeMode.JUSTIFIED) return@LaunchedEffect
        val plan = GAZE_PLAN[current] ?: listOf(Look(GazeTarget.OWN, 1, 60_000L..60_000L))
        val total = plan.sumOf { it.weight }
        while (true) {
            var pick = (Math.random() * total).toInt()
            val look = plan.first { pick -= it.weight; pick < 0 }
            target = look.target
            delay(look.dwellMs.random())
            target = GazeTarget.OWN
            delay(look.gapMs.random())
        }
    }
    // Eyes first, then the head. The gaze eases toward its target (~250 ms) and locks; after
    // `headLead` ms the head follows on an under-damped spring - the dramatic turn - and the eyes,
    // being carried by the head, settle back toward centre on the plate. Back to OWN, the head
    // returns to the rig's own facing (turnX/turnY -> 0).
    LaunchedEffect(gazeMode) {
        if (gazeMode == GazeMode.OFF) { setLook(0f, 0f); headTarget = 0f to 0f; return@LaunchedEffect }
        var x = lookX; var y = lookY                   // eyes
        var hx = 0f; var hy = 0f; var vx = 0f; var vy = 0f   // head position and velocity
        var wx = 0f; var wy = 0f                        // head written last
        var lastWantX = 0f; var lastWantY = 0f; var wantSince = 0L
        var last = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f); last = now
                // A cursor moving within a mascot width in the last half second demands a look
                // (unless asleep), the way a hand waved in front of a face does.
                val near = current != AvatarState.SLEEPING && kotlin.math.hypot(cursorLook.first, cursorLook.second) < 1f / trackReach && now - lastCursorMove < 500_000_000L
                val (wantX, wantY) = when {
                    gazeMode == GazeMode.CURSOR || near -> cursorLook
                    target == GazeTarget.CURSOR -> cursorLook
                    target == GazeTarget.INPUT -> lookAt(inputSpot.x, inputSpot.y)
                    target == GazeTarget.TIMELINE -> lookAt(timelineSpot.x, timelineSpot.y)
                    else -> 0f to 0f   // USER and OWN: centre; the rig's own gaze life shows through
                }
                // The head only commits once the eyes have held a direction for the lead time.
                if (kotlin.math.abs(wantX - lastWantX) > 0.15f || kotlin.math.abs(wantY - lastWantY) > 0.15f) { lastWantX = wantX; lastWantY = wantY; wantSince = now }
                if (now - wantSince > headLead * 1_000_000L) headTarget = (wantX * 0.85f) to (wantY * 0.7f)
                // Eyes: exponential ease. Head: spring (omega, zeta) with overshoot.
                val k = 1f - kotlin.math.exp(-dt / 0.25f)
                val (htx, hty) = headTarget
                // The eyes aim at the target minus what the head already covers, so they lead and then relax.
                val ex = wantX - hx * 0.6f; val ey = wantY - hy * 0.6f
                x += (ex - x) * k; y += (ey - y) * k
                val omega = 11f; val zeta = 0.5f
                vx += ((htx - hx) * omega * omega - 2f * zeta * omega * vx) * dt; hx += vx * dt
                vy += ((hty - hy) * omega * omega - 2f * zeta * omega * vy) * dt; hy += vy * dt
                if (kotlin.math.abs(x - lookX) > 0.002f || kotlin.math.abs(y - lookY) > 0.002f) setLook(x, y)
                if (kotlin.math.abs(hx - wx) > 0.002f || kotlin.math.abs(hy - wy) > 0.002f) {
                    wx = hx; wy = hy
                    scene.inputSink.setNumber("turnX", hx.coerceIn(-1f, 1f)); scene.inputSink.setNumber("turnY", hy.coerceIn(-1f, 1f))
                }
            }
        }
    }

    // Tunables are plain view-model numbers; write each on change (the map is snapshot state).
    LaunchedEffect(loaded) {
        if (!loaded) return@LaunchedEffect
        snapshotFlow { tune.toMap() }.collect { m -> m.forEach { (k, v) -> scene.inputSink.setNumber(k, v) } }
    }

    // Hands-free contract check: every state through the shared runtime, identity rotating
    // independently, mouth pulsed while speaking. Off, the bench keeps whatever is set.
    LaunchedEffect(autoCycle, loaded) {
        if (!autoCycle || !loaded) return@LaunchedEffect
        var tick = 0
        while (true) {
            for (state in AvatarState.entries) {
                setIdentity(MascotIdentity(MascotShape.entries[tick % MascotShape.entries.size], MascotPalette.ALL[tick % MascotPalette.ALL.size]))
                println("rive-spike self-test: identity=${identity.encode()}")
                tick++
                setState(state)
                mouth = if (state == AvatarState.SPEAKING) 0.9f else 0f
                runtime.setMouthOpen(mouth)
                println("rive-spike self-test: mascot state=${state.name}")
                delay(1500)
            }
        }
    }

    Row(modifier.fillMaxHeight()) {
        // ---- stage: page ground, frame ground, the mascot scaled inside the frame ----
        var pagePalette by remember { mutableStateOf(false) }
        var framePalette by remember { mutableStateOf(false) }
        SurroundLayer(
            spec = page, editing = editGradients, selected = selPage,
            onSelect = { selPage = it; ground = Ground.PAGE }, onChange = { page = it }, onTap = { pagePalette = true },
            modifier = Modifier.weight(1f).fillMaxHeight().onSizeChanged { stageSize = it }
                // Gaze from the cursor anywhere on the stage, relative to the mascot's centre.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type != PointerEventType.Move || mascotBounds.isEmpty) continue
                            val p = e.changes.firstOrNull()?.position ?: continue
                            val reach = mascotBounds.width * trackReach / 2f
                            cursorLook = ((p.x - mascotBounds.center.x) / reach).coerceIn(-1f, 1f) to ((p.y - mascotBounds.center.y) / reach).coerceIn(-1f, 1f)
                            lastCursorMove = System.nanoTime()
                        }
                    }
                },
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PalettePopup(pagePalette, { pagePalette = false }, page.base) { page = page.copy(base = it) }
                val clipShape = if (frameShape == FrameShape.NONE) RectangleShape else frameShape.shape
                val framed = frameShape != FrameShape.NONE
                Box(Modifier.size(frameSize.dp).clip(clipShape), contentAlignment = Alignment.Center) {
                    if (framed) {
                        SurroundLayer(
                            spec = frame, editing = editGradients, selected = selFrame,
                            onSelect = { selFrame = it; ground = Ground.FRAME }, onChange = { frame = it }, onTap = { framePalette = true },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                PalettePopup(framePalette, { framePalette = false }, frame.base) { frame = frame.copy(base = it) }
                                MascotBox(scene, frameSize * mascotScale) { mascotBounds = it }
                            }
                        }
                    } else {
                        MascotBox(scene, frameSize * mascotScale) { mascotBounds = it }
                    }
                }
                if (showTargets && gazeMode == GazeMode.JUSTIFIED) {
                    val ink = if (isDark(page.base)) Color(0x55FFFFFF) else Color(0x55000000)
                    Hotspot("input (you typing)", inputSpot, 260f, 44f, ink, target == GazeTarget.INPUT)
                    Hotspot("timeline / code", timelineSpot, 220f, 160f, ink, target == GazeTarget.TIMELINE)
                }
                Text(
                    if (editGradients) "drag the rings to place lights; tap a ground to pick its base colour"
                    else "looking at ${target.label}: ${target.reason}",
                    color = if (isDark(page.base)) Color(0x66FFFFFF) else Color(0x66000000),
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                )
            }
        }

        // ---- controls ----
        Column(
            Modifier.width(400.dp).fillMaxHeight().background(Color(0xFF141414)).verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(autoCycle, onCheckedChange = { autoCycle = it })
                Text(if (autoCycle) "auto-cycle: on (states + identities every 1.5 s)" else "auto-cycle: off", color = Color.LightGray)
            }

            Section("state: ${current.name.lowercase()}")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AvatarState.entries.forEach { state ->
                    val onClick = { autoCycle = false; setState(state) }
                    if (state == current) Button(onClick) { Text(state.name.lowercase()) }
                    else OutlinedButton(onClick) { Text(state.name.lowercase()) }
                }
            }

            Section("shape")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                MascotShape.entries.forEach { shape ->
                    val onClick = { autoCycle = false; setIdentity(identity.copy(shape = shape)) }
                    if (shape == identity.shape) Button(onClick) { Text(shape.name.lowercase()) }
                    else OutlinedButton(onClick) { Text(shape.name.lowercase()) }
                }
            }

            Section("colour  ${hex(identity.argb)}")
            SwatchRow(MascotPalette.ALL, identity.argb) { autoCycle = false; setIdentity(identity.copy(argb = it)) }
            HexField(identity.argb) { autoCycle = false; setIdentity(identity.copy(argb = it)) }

            Section("mouthOpen %.2f  (visible in speaking / dragged only)".format(mouth))
            Slider(mouth, onValueChange = { autoCycle = false; mouth = it; runtime.setMouthOpen(it) })

            Section("gaze: looking at ${target.label}")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GazeMode.entries.forEach { m ->
                    if (m == gazeMode) Button({ gazeMode = m }) { Text(m.label) } else OutlinedButton({ gazeMode = m }) { Text(m.label) }
                }
            }
            if (gazeMode != GazeMode.OFF) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(showTargets, onCheckedChange = { showTargets = it })
                    Text("show what it can look at", color = Color.LightGray)
                }
                Section("reach %.1f mascot widths to full gaze".format(trackReach))
                Slider(trackReach, valueRange = 0.6f..6f, onValueChange = { trackReach = it })
                Section("eyes lead the head by %.0f ms".format(headLead))
                Slider(headLead, valueRange = 0f..1200f, onValueChange = { headLead = it })
            } else {
                Section("lookX %.2f".format(lookX))
                Slider(lookX, valueRange = -1f..1f, onValueChange = { setLook(it, lookY) })
                Section("lookY %.2f".format(lookY))
                Slider(lookY, valueRange = -1f..1f, onValueChange = { setLook(lookX, it) })
            }

            Section("frame")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FrameShape.entries.forEach { f ->
                    if (f == frameShape) Button({ frameShape = f }) { Text(f.label) }
                    else OutlinedButton({ frameShape = f }) { Text(f.label) }
                }
            }
            Section("frame size ${frameSize.toInt()} dp")
            Slider(frameSize, valueRange = 22f..520f, onValueChange = { frameSize = it })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(22f, 44f, 72f, 120f, 240f, 360f).forEach { s -> OutlinedButton({ frameSize = s }) { Text("${s.toInt()}") } }
            }
            Section("mascot in frame x%.2f  (%s)".format(mascotScale, if (mascotScale > 1f) "clipped" else "padded"))
            Slider(mascotScale, valueRange = 0.4f..2.2f, onValueChange = { mascotScale = it })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.8f, 1f, 1.2f, 1.5f).forEach { s -> OutlinedButton({ mascotScale = s }) { Text("x$s") } }
            }

            // ---- rig tunables: art-direct the plate, glyph and mouth; read the numbers back into SPEC ----
            Section("rig tunables (0.5 = as shipped; numbers are what to put in SPEC)")
            TuneSlider("entity scale", tune, "tuneScale") { t -> "x%.2f".format(0.5f + t) }
            TuneSlider("plate scale", tune, "tunePlate") { t -> "x%.2f".format(0.6f + 0.8f * t) }
            TuneSlider("glyph scale", tune, "tuneGlyph") { t -> "x%.2f".format(0.4f + 1.2f * t) }
            TuneSlider("mouth scale", tune, "tuneMouth") { t -> "x%.2f".format(0.5f + t) }
            TuneSlider("mouth distance below plate", tune, "tuneMouthY") { t -> "%.0f px".format(42f + 80f * t) }
            OutlinedButton({ tune.keys.toList().forEach { tune[it] = 0.5f; scene.inputSink.setNumber(it, 0.5f) } }) { Text("reset tunables") }

            // ---- grounds: base colour plus radial lights, dragged on the canvas ----
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(editGradients, onCheckedChange = { editGradients = it })
                Text("edit lights on the canvas", color = Color.LightGray)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Ground.entries.forEach { g ->
                    if (g == ground) Button({ ground = g }) { Text(g.label) } else OutlinedButton({ ground = g }) { Text(g.label) }
                }
            }
            val spec = if (ground == Ground.PAGE) page else frame
            val sel = if (ground == Ground.PAGE) selPage else selFrame
            fun update(s: Surround) { if (ground == Ground.PAGE) page = s else frame = s }
            fun select(i: Int) { if (ground == Ground.PAGE) selPage = i else selFrame = i }

            Section("${ground.label} base  ${hex(spec.base)}")
            SwatchRow(SURROUND_COLORS, spec.base) { update(spec.copy(base = it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton({
                    // A new light starts as a soft highlight (or shadow on a light ground) at the centre.
                    val light = if (isDark(spec.base)) 0x66FFFFFF.toInt() else 0x66000000.toInt()
                    update(spec.copy(nodes = spec.nodes + GradNode(argb = light))); select(spec.nodes.size); editGradients = true
                }) { Text("+ light") }
                if (sel in spec.nodes.indices) {
                    OutlinedButton({ update(spec.copy(nodes = spec.nodes.filterIndexed { i, _ -> i != sel })); select(-1) }) { Text("remove") }
                }
                if (spec.nodes.isNotEmpty()) OutlinedButton({ update(spec.copy(nodes = emptyList())); select(-1) }) { Text("clear") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                spec.nodes.forEachIndexed { i, n ->
                    val label = "light ${i + 1}"
                    if (i == sel) Button({ select(i) }) { Text(label) } else OutlinedButton({ select(i) }) { Text(label) }
                }
            }
            if (sel in spec.nodes.indices) {
                val n = spec.nodes[sel]
                fun set(v: GradNode) = update(spec.copy(nodes = spec.nodes.toMutableList().also { it[sel] = v }))
                Section("light ${sel + 1}  colour ${hex(n.argb)}")
                SwatchRow(SURROUND_COLORS, n.argb or 0xFF000000.toInt()) { set(n.copy(argb = (it and 0x00FFFFFF) or (n.argb and 0xFF000000.toInt()))) }
                HexField(n.argb) { set(n.copy(argb = it)) }
                Section("strength %.2f".format(((n.argb ushr 24) and 0xFF) / 255f))
                Slider(((n.argb ushr 24) and 0xFF) / 255f, onValueChange = { set(n.copy(argb = (n.argb and 0x00FFFFFF) or ((it * 255).toInt() shl 24))) })
                Section("radius %.2f".format(n.radius))
                Slider(n.radius, valueRange = 0.05f..1.5f, onValueChange = { set(n.copy(radius = it)) })
                Section("stretch x%.2f".format(n.aspect))
                Slider(n.aspect, valueRange = 0.2f..4f, onValueChange = { set(n.copy(aspect = it)) })
                Section("rotation %.0f deg".format(n.rotation))
                Slider(n.rotation, valueRange = -180f..180f, onValueChange = { set(n.copy(rotation = it)) })
                Section("position %.2f, %.2f  (drag the ring on the canvas)".format(n.x, n.y))
            }
        }
    }
}

/** A labelled rectangle on the stage standing in for a piece of UI the character can look at. */
@Composable
private fun Hotspot(label: String, centre: androidx.compose.ui.geometry.Offset, w: Float, h: Float, ink: Color, hot: Boolean) {
    Box(
        Modifier.offset { IntOffset((centre.x - w / 2).toInt(), (centre.y - h / 2).toInt()) }
            .size(w.dp / LocalDensity.current.density, h.dp / LocalDensity.current.density)
            .border(if (hot) 2.dp else 1.dp, if (hot) ink.copy(alpha = 0.9f) else ink, RoundedCornerShape(8.dp))
            .padding(6.dp),
    ) { Text(label, color = ink, style = MaterialTheme.typography.labelSmall) }
}

/** One rig tunable: a 0..1 view-model number the file maps onto a pose range; `shown` renders the real value. */
@Composable
private fun TuneSlider(label: String, tune: MutableMap<String, Float>, key: String, shown: (Float) -> String) {
    val v = tune[key] ?: 0.5f
    Section("$label  ${shown(v)}")
    Slider(v, onValueChange = { tune[key] = it })
}

/** The mascot at a given size, reporting its bounds in the stage so the cursor gaze can aim at it. */
@Composable
private fun MascotBox(scene: RiveDesktopScene, sizeDp: Float, onBounds: (Rect) -> Unit) {
    Box(Modifier.size(sizeDp.dp).onGloballyPositioned { onBounds(it.boundsInParent()) }) {
        RiveDesktopSurface(scene, Modifier.fillMaxSize())
    }
}

@Composable
private fun Section(title: String) = Text(title, color = Color(0xFFBBBBBB), style = MaterialTheme.typography.labelLarge)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwatchRow(colors: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        colors.forEach { c -> Swatch(c, c == selected) { onPick(c) } }
    }
}

@Composable
private fun Swatch(argb: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(26.dp).clip(CircleShape).background(Color(argb))
            .border(if (selected) 2.dp else 1.dp, if (selected) Color.White else Color(0x55FFFFFF), CircleShape)
            .clickable(onClick = onClick),
    )
}

/** A palette anchored to whatever it sits in: swatches plus a hex field for anything else. */
@Composable
private fun PalettePopup(open: Boolean, onDismiss: () -> Unit, selected: Int, onPick: (Int) -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        Column(Modifier.padding(10.dp).width(300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SwatchRow(SURROUND_COLORS, selected) { onPick(it); onDismiss() }
            HexField(selected) { onPick(it) }
        }
    }
}

@Composable
private fun HexField(argb: Int, onPick: (Int) -> Unit) {
    var text by remember(argb) { mutableStateOf(hex(argb)) }
    OutlinedTextField(
        text,
        onValueChange = { v ->
            text = v
            parseHex(v)?.let(onPick)
        },
        singleLine = true,
        label = { Text("hex (RRGGBB or AARRGGBB)") },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun hex(argb: Int) = "#" + argb.toUInt().toString(16).padStart(8, '0').uppercase()

private fun parseHex(s: String): Int? {
    val h = s.trim().removePrefix("#")
    return when (h.length) {
        6 -> h.toUIntOrNull(16)?.let { (0xFF000000u or it).toInt() }
        8 -> h.toUIntOrNull(16)?.toInt()
        else -> null
    }
}

private fun isDark(argb: Int): Boolean {
    val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
    return 0.2126 * r + 0.7152 * g + 0.0722 * b < 128
}
