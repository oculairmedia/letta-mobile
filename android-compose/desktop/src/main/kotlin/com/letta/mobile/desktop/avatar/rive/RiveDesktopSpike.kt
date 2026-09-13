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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
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
 *    identity (shape x colour), gaze and mouth sliders, and the surround - page colour, a frame
 *    in several shapes with its own colour - so the mascot can be judged in the places it will sit.
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
        state = rememberWindowState(width = 1320.dp, height = 860.dp),
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
    var pageColor by remember { mutableStateOf(0xFF1A1A1A.toInt()) }
    var frameShape by remember { mutableStateOf(FrameShape.NONE) }
    var frameColor by remember { mutableStateOf(0xFF2E2E33.toInt()) }
    var frameSize by remember { mutableFloatStateOf(360f) }
    var loaded by remember { mutableStateOf(false) }

    fun setState(state: AvatarState) {
        current = state
        runtime.applyState(state)
    }

    fun setIdentity(value: MascotIdentity) {
        identity = value
        RiveAvatarContract.applyIdentity(scene.inputSink, value)
    }

    fun setLook(x: Float, y: Float) {
        lookX = x; lookY = y
        // Screen space is 0..1; the runtime maps it to the contract's -1..1.
        runtime.setLookTarget(AvatarLookTarget.Screen((x + 1f) / 2f, (y + 1f) / 2f))
    }

    // Identity before the first frame (see rive/mascot/README.md, host rules), then the runtime.
    LaunchedEffect(runtime) {
        RiveAvatarContract.applyIdentity(scene.inputSink, identity)
        runtime.load(MASCOT_MODEL)
        loaded = true
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
        // ---- stage: page colour behind, an optional frame around the mascot ----
        var pagePalette by remember { mutableStateOf(false) }
        var framePalette by remember { mutableStateOf(false) }
        Box(
            Modifier.weight(1f).fillMaxHeight().background(Color(pageColor)).clickable { pagePalette = true },
            contentAlignment = Alignment.Center,
        ) {
            PalettePopup(pagePalette, { pagePalette = false }, pageColor) { pageColor = it }
            val frame = Modifier.size(frameSize.dp).let { m ->
                if (frameShape == FrameShape.NONE) m
                else m.clip(frameShape.shape).background(Color(frameColor)).clickable { framePalette = true }
            }
            Box(frame, contentAlignment = Alignment.Center) {
                PalettePopup(framePalette, { framePalette = false }, frameColor) { frameColor = it }
                RiveDesktopSurface(scene, Modifier.fillMaxSize())
            }
            Text(
                "click the page or the frame to pick its colour",
                color = if (isDark(pageColor)) Color(0x66FFFFFF) else Color(0x66000000),
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
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
            Section("lookX %.2f".format(lookX))
            Slider(lookX, valueRange = -1f..1f, onValueChange = { setLook(it, lookY) })
            Section("lookY %.2f".format(lookY))
            Slider(lookY, valueRange = -1f..1f, onValueChange = { setLook(lookX, it) })

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
            Section("frame colour  ${hex(frameColor)}")
            SwatchRow(SURROUND_COLORS, frameColor) { frameColor = it }
            Section("page colour  ${hex(pageColor)}")
            SwatchRow(SURROUND_COLORS, pageColor) { pageColor = it }
        }
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
