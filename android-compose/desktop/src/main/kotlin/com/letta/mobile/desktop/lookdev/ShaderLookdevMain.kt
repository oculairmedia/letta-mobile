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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds
import java.io.File
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

/**
 * PROPOSED desktop tuning, preloaded so the window opens showing a living glow
 * instead of near-black. The production source has a fatal band overlap: the
 * blob is anchored at uv.y=0.82 but upperFade zeroes everything above
 * uv.y=0.78 — the glow's own center sits in the killed zone, and the usable
 * band peaks at ~2-5% of an already-0.18 alpha (effective ~0.005: invisible
 * everywhere, phone included). This variant uses one bottom-weighted vertical
 * shape instead of two fighting bands, and a livable base intensity.
 * Reset to production via the button; port back to AmbientShaderSource.kt
 * once a look is approved.
 */
private val PROPOSED_DESKTOP_SKSL = """
uniform float2 uSize;
uniform float uTime;
uniform float uAgitation;
uniform float uEnvelope;
uniform vec4 uColor;

float hash(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453123);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(hash(i + float2(0.0, 0.0)), hash(i + float2(1.0, 0.0)), u.x),
        mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x),
        u.y
    );
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / max(uSize, float2(1.0, 1.0));
    float aspect = uSize.x / max(uSize.y, 1.0);
    float2 p = float2((uv.x - 0.50) * aspect, uv.y - 0.95);

    float breath = 0.5 + 0.5 * sin(uTime);
    float2 warp = float2(
        noise(uv * 3.1 + float2(uTime * 0.11, 0.0)),
        noise(uv * 3.7 + float2(0.0, -uTime * 0.07))
    );
    float2 driftUv = float2(uv.x * 2.2 + uTime * 0.08, uv.y * 1.6 - uTime * 0.05)
        + (warp - 0.5) * (0.9 * uAgitation);
    float drift = 0.65 * noise(driftUv) + 0.35 * noise(driftUv * 2.4 + warp * 1.7);
    float radius = mix(0.55, 0.75, breath) + (drift - 0.5) * (0.10 + 0.08 * uAgitation);
    float glow = 1.0 - smoothstep(0.0, radius, length(p));

    // One bottom-weighted shape: full strength at the composer, gone by ~55%
    // up the pane. No second band fighting it.
    float verticalShape = smoothstep(0.45, 0.95, uv.y);
    float alpha = glow * verticalShape * 0.38 * uEnvelope * uColor.a;
    half4 c = half4(uColor.rgb, alpha);
    return half4(c.rgb * c.a, c.a);
}
"""

private data class ShaderVariant(val name: String, val source: String, val mtime: Long)

/**
 * Variant library: every `*.sksl` file in `desktop/lookdev-shaders/`, polled
 * live — drop a new file (or overwrite one) while the window is open and the
 * carousel updates within ~a second. This is the design-injection loop: the
 * agent authors variants as files, the human scrolls the carousel.
 */
private fun resolveVariantDir(): File? =
    sequenceOf(
        System.getProperty("lookdev.shaderDir")?.let(::File),
        File("lookdev-shaders"),
        File("desktop/lookdev-shaders"),
        File("android-compose/desktop/lookdev-shaders"),
    ).filterNotNull().firstOrNull { it.isDirectory }

private fun loadVariants(dir: File?): List<ShaderVariant> =
    dir?.listFiles { f -> f.isFile && f.name.endsWith(".sksl") }
        ?.sortedBy { it.name }
        ?.mapNotNull { file ->
            runCatching {
                ShaderVariant(
                    name = file.name.removeSuffix(".sksl").substringAfter('-'),
                    source = file.readText(),
                    mtime = file.lastModified(),
                )
            }.getOrNull()
        }
        .orEmpty()

private class LookdevState {
    var speed by mutableFloatStateOf(1.7f)
    var agitation by mutableFloatStateOf(1.6f)
    var envelope by mutableFloatStateOf(1f)
    var alpha by mutableFloatStateOf(1f)
    var tint by mutableStateOf(Color(0xFF3FE0C0))
    var source by mutableStateOf(PROPOSED_DESKTOP_SKSL)
    var compileError by mutableStateOf<String?>(null)
    var variants by mutableStateOf(listOf<ShaderVariant>())
    var invert by mutableFloatStateOf(0f)
    var scale by mutableFloatStateOf(1f)
    var selectedVariant by mutableStateOf<String?>(null)
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

    // Poll the variant folder: new/changed .sksl files appear in the carousel
    // live; if the file behind the SELECTED variant changes on disk, hot-swap
    // it into the editor — overwrite-and-see, no restart.
    LaunchedEffect(Unit) {
        val dir = resolveVariantDir()
        while (true) {
            val fresh = withContext(Dispatchers.IO) { loadVariants(dir) }
            if (fresh != state.variants) {
                val selected = state.selectedVariant
                val previous = state.variants.firstOrNull { it.name == selected }
                val updated = fresh.firstOrNull { it.name == selected }
                state.variants = fresh
                if (previous != null && updated != null && updated.mtime != previous.mtime) {
                    state.source = updated.source
                }
            }
            delay(1.seconds)
        }
    }

    Row(Modifier.fillMaxSize()) {
        ControlsColumn(state)
        PreviewPane(state, { builder }, { phase })
    }
}

/** Compact dev-tool chip: 22dp tall, quiet colors — not a Material pill. */
@Composable
private fun Chip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(5.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ControlsColumn(state: LookdevState) {
    Column(
        modifier = Modifier
            .width(360.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "Variants — drop .sksl files into desktop/lookdev-shaders/ (live)",
            style = MaterialTheme.typography.labelLarge,
        )
        // Diagnostic: which folder is actually being watched, and what it sees.
        Text(
            text = "watching: ${resolveVariantDir()?.absolutePath ?: "NO FOLDER RESOLVED"} · ${state.variants.size} variants",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.variants, key = { it.name }) { variant ->
                Chip(
                    label = variant.name,
                    selected = state.selectedVariant == variant.name,
                ) {
                    state.selectedVariant = variant.name
                    state.source = variant.source
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Chip("proposed") {
                state.selectedVariant = null
                state.source = PROPOSED_DESKTOP_SKSL
            }
            Chip("production") {
                state.selectedVariant = null
                state.source = AMBIENT_GLOW_SHADER_SOURCE + AMBIENT_GLOW_MAIN_PREMULTIPLIED
            }
            Chip(if (state.invert > 0.5f) "mask: inverted" else "mask: normal", selected = state.invert > 0.5f) {
                state.invert = if (state.invert > 0.5f) 0f else 1f
            }
        }
        Text("Status presets", style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AmbientMotionStatus.entries.forEach { status ->
                Chip(status.name.lowercase()) {
                    val spec = AmbientMotion.spec(status)
                    state.speed = spec.speed
                    state.agitation = spec.agitation
                    state.envelope = spec.bloomEnvelope
                }
            }
        }
        LabeledSlider("speed", state.speed, 0f..4f) { state.speed = it }
        LabeledSlider("agitation (uAgitation)", state.agitation, 0f..3f) { state.agitation = it }
        LabeledSlider("envelope (uEnvelope)", state.envelope, 0f..3f) { state.envelope = it }
        LabeledSlider("tint alpha (uColor.a)", state.alpha, 0f..1f) { state.alpha = it }
        LabeledSlider("overlay scale (uScale)", state.scale, 0.25f..3f) { state.scale = it }

        Text("Tint", style = MaterialTheme.typography.labelSmall)
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

        Text("SkSL (recompiles as you type)", style = MaterialTheme.typography.labelSmall)
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
        // Glow FIRST, text over it — the same order production uses
        // (DesktopAmbientChatBackground draws its canvas before `content()`).
        // Painting the shader last made the lookdev overstate text tinting, so
        // opacity picked here would have read differently in the shipped chat.
        Canvas(Modifier.fillMaxSize()) {
            val active = builder() ?: return@Canvas
            active.uniform("uSize", size.width, size.height)
            active.uniform("uTime", phase())
            active.uniform("uAgitation", state.agitation)
            active.uniform("uEnvelope", state.envelope)
            if (state.source.contains("uniform float uStreamEnergy")) {
                active.uniform("uStreamEnergy", 0f)
            }
            active.uniform("uColor", state.tint.red, state.tint.green, state.tint.blue, state.alpha)
            if (state.source.contains("uniform float uInvert")) {
                active.uniform("uInvert", state.invert)
            }
            if (state.source.contains("uniform float uScale")) {
                active.uniform("uScale", state.scale)
            }
            val frameShader = active.makeShader(null)
            paint.shader = frameShader
            drawIntoCanvas { it.nativeCanvas.drawRect(SkiaRect.makeWH(size.width, size.height), paint) }
            paint.shader = null
            frameShader.close()
        }
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
        Text("$label = ${"%.2f".format(value)}", fontSize = 10.sp)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

private const val BaseRate = (2 * PI).toFloat() * 1000f / AmbientMotion.BASE_PERIOD_MILLIS
