package com.letta.mobile.desktop.lookdev

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.singleWindowApplication
import com.letta.mobile.desktop.initializeDesktopLifecycleMainThread
import com.letta.mobile.ui.ambient.AMBIENT_GLOW_MAIN_PREMULTIPLIED
import com.letta.mobile.ui.ambient.AMBIENT_GLOW_SHADER_SOURCE
import com.letta.mobile.ui.ambient.AmbientMotion
import com.letta.mobile.ui.ambient.AmbientMotionStatus
import org.jetbrains.skia.Paint as SkiaPaint
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import kotlin.math.PI

/**
 * Realtime lookdev for the ambient agent-status shader, hosted as a SECOND
 * window inside the running app:
 *
 *   LETTA_SHADER_LOOKDEV=1 ./gradlew :desktop:run
 *
 * Live-edit the SkSL on the left (recompiles per keystroke — Skia compiles in
 * milliseconds; the last GOOD effect keeps rendering through syntax errors),
 * drag uniforms, and click status presets that load the production
 * [AmbientMotion] values. The preview draws over a fake chat column so
 * readability is judged against real content, using the exact production
 * pipeline (RuntimeEffect + nativeCanvas). When the look lands, port the SkSL
 * back to AmbientShaderSource.kt and the numbers to AmbientMotion.kt.
 *
 * Also embeddable in the running app as a second window
 * (LETTA_SHADER_LOOKDEV=1 with :desktop:run) for tuning against the real
 * theme.
 */
fun main() {
    // Without this, Compose 1.11 + lifecycle deadlocks the EDT in
    // MainDispatcherChecker (runBlocking onto Dispatchers.Main from the main
    // thread) before the window ever shows — the app's Main.kt documents the
    // same workaround.
    initializeDesktopLifecycleMainThread()
    singleWindowApplication(title = "Ambient Shader Lookdev") {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(modifier = Modifier.fillMaxSize()) { LookdevRoot() }
        }
    }
}

@Composable
internal fun ShaderLookdevWindow() {
    var open by remember { mutableStateOf(true) }
    if (!open) return
    Window(
        onCloseRequest = { open = false },
        title = "Ambient Shader Lookdev",
        state = rememberWindowState(width = 1280.dp, height = 860.dp),
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(modifier = Modifier.fillMaxSize()) { LookdevRoot() }
        }
    }
}

private class LookdevState {
    var speed by mutableFloatStateOf(1.7f)
    var agitation by mutableFloatStateOf(1.6f)
    var envelope by mutableFloatStateOf(1f)
    var alpha by mutableFloatStateOf(1f)
    var tint by mutableStateOf(Color(0xFF3FE0C0))
    var source by mutableStateOf(AMBIENT_GLOW_SHADER_SOURCE + AMBIENT_GLOW_MAIN_PREMULTIPLIED)
    var compileError by mutableStateOf<String?>(null)
}

@Composable
private fun LookdevRoot() {
    val state = remember { LookdevState() }

    // Recompile on every source change; keep the last good effect on errors.
    var builder by remember { mutableStateOf<RuntimeShaderBuilder?>(null) }
    LaunchedEffect(state.source) {
        runCatching { RuntimeShaderBuilder(RuntimeEffect.makeForShader(state.source)) }
            .onSuccess { builder = it; state.compileError = null }
            .onFailure { state.compileError = it.message }
    }

    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) phase += ((now - last) / 1_000_000_000f) * BaseRate * state.speed
                last = now
            }
        }
    }

    Row(Modifier.fillMaxSize()) {
        ControlsColumn(state)
        PreviewPane(state, { builder }, { phase })
    }
}

@Composable
private fun ControlsColumn(state: LookdevState) {
    Column(
        modifier = Modifier
            .width(400.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Status presets (production AmbientMotion values)", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AmbientMotionStatus.entries.forEach { status ->
                Button(onClick = {
                    val spec = AmbientMotion.spec(status)
                    state.speed = spec.speed
                    state.agitation = spec.agitation
                    state.envelope = spec.bloomEnvelope
                }) { Text(status.name, fontSize = 10.sp) }
            }
        }
        LabeledSlider("speed", state.speed, 0f..4f) { state.speed = it }
        LabeledSlider("agitation (uAgitation)", state.agitation, 0f..3f) { state.agitation = it }
        LabeledSlider("envelope (uEnvelope)", state.envelope, 0f..3f) { state.envelope = it }
        LabeledSlider("tint alpha (uColor.a)", state.alpha, 0f..1f) { state.alpha = it }

        Text("Tint", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                Color(0xFF3FE0C0), // teal (running)
                Color(0xFFE0457B), // pink identity
                Color(0xFFF0A03C), // amber identity
                Color(0xFFFF5449), // error red
                Color(0xFF8E7CFF), // violet identity
            ).forEach { color ->
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(color)
                        .clickable { state.tint = color },
                )
            }
        }

        Text("SkSL (recompiles as you type)", style = MaterialTheme.typography.labelLarge)
        state.compileError?.let { error ->
            Text(
                text = error.take(600),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedTextField(
            value = state.source,
            onValueChange = { state.source = it },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 420.dp),
        )
    }
}

@Composable
private fun PreviewPane(
    state: LookdevState,
    builder: () -> RuntimeShaderBuilder?,
    phase: () -> Float,
) {
    val paint = remember { SkiaPaint() }
    Box(
        Modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .background(Color(0xFF0A0A0A)),
    ) {
        // Fake chat content so glow strength is judged against readability.
        Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            repeat(8) { index ->
                Text(
                    text = if (index % 3 == 2) {
                        "The quick brown fox verified the wire contract and reported 14 memory blocks."
                    } else {
                        "Assistant narration line $index — glow must never wash this out while streaming."
                    },
                    color = Color(0xFFB8C2C4),
                    fontSize = 14.sp,
                )
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val active = builder() ?: return@Canvas
            active.uniform("uSize", size.width, size.height)
            active.uniform("uTime", phase())
            active.uniform("uAgitation", state.agitation)
            active.uniform("uEnvelope", state.envelope)
            active.uniform("uColor", state.tint.red, state.tint.green, state.tint.blue, state.alpha)
            paint.shader = active.makeShader(null)
            drawIntoCanvas { it.nativeCanvas.drawRect(SkiaRect.makeWH(size.width, size.height), paint) }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Text("$label = ${"%.2f".format(value)}", style = MaterialTheme.typography.bodySmall)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

private const val BaseRate = (2 * PI).toFloat() * 1000f / AmbientMotion.BASE_PERIOD_MILLIS
