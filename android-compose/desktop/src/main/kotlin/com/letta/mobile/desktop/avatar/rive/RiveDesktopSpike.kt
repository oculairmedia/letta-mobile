package com.letta.mobile.desktop.avatar.rive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.letta.mobile.avatar.core.AvatarState
import com.letta.mobile.avatar.rive.MASCOT_MODEL
import com.letta.mobile.avatar.rive.RiveAvatarRuntime
import java.io.File
import kotlinx.coroutines.delay

/** `-Drive.spike.selfTest=true` cycles states and triggers without input, for unattended checks. */
private val SELF_TEST = System.getProperty("rive.spike.selfTest").toBoolean()

/**
 * letta-mobile-0s5bi spike window. Two native Rive scenes side by side, both plain Compose nodes:
 *
 *  - Left: any `.riv` (default `-Prive.file`), clickable, with its state machine's trigger inputs
 *    exposed as buttons. This is the feathering check - the glow and blur must survive.
 *  - Right: the shipped mascot driven through the SAME [RiveAvatarRuntime] Android uses, over the
 *    desktop [RiveDesktopScene.inputSink]. This is the contract check.
 *
 * Args: `<file.riv> [stateMachine] [trigger,trigger,...] [mascot.riv]`.
 */
fun main(args: Array<String>) = application {
    val file = args.getOrNull(0)?.let(::File)
    val stateMachine = args.getOrNull(1)?.takeIf { it.isNotBlank() }
    val triggers = args.getOrNull(2)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
    val mascot = args.getOrNull(3)?.let(::File)

    Window(
        onCloseRequest = ::exitApplication,
        title = "Rive native spike (D3D11)",
        state = rememberWindowState(width = 1100.dp, height = 760.dp),
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Row(Modifier.fillMaxSize().background(Color(0xFF1A1A1A)).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (file != null) {
                    SceneColumn(file, stateMachine, triggers, Modifier.weight(1f))
                }
                if (mascot != null) {
                    MascotColumn(mascot, Modifier.width(420.dp))
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MascotColumn(file: File, modifier: Modifier) {
    val scene = rememberScene(remember(file) { file.readBytes() }, null)
    val runtime = remember(scene) { RiveAvatarRuntime(scene.inputSink) }
    var current by remember { mutableStateOf(AvatarState.IDLE) }
    var mouth by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(runtime) {
        runtime.load(MASCOT_MODEL)
        if (!SELF_TEST) return@LaunchedEffect
        // Hands-free contract check: every state through the shared runtime, mouth pulsed while speaking.
        while (true) {
            for (state in AvatarState.entries) {
                current = state
                runtime.applyState(state)
                mouth = if (state == AvatarState.SPEAKING) 0.9f else 0f
                runtime.setMouthOpen(mouth)
                println("rive-spike self-test: mascot state=${state.name}")
                delay(1500)
            }
        }
    }

    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Mascot via RiveAvatarRuntime: $current", color = Color.LightGray)
        RiveDesktopSurface(scene, Modifier.weight(1f).fillMaxSize())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AvatarState.entries.forEach { state ->
                val onClick = { current = state; runtime.applyState(state) }
                if (state == current) Button(onClick) { Text(state.name.lowercase()) }
                else OutlinedButton(onClick) { Text(state.name.lowercase()) }
            }
        }
        Text("mouthOpen", color = Color.LightGray)
        Slider(mouth, onValueChange = { mouth = it; runtime.setMouthOpen(it) })
    }
}
