package com.letta.mobile.desktop.avatar.rive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The bench's half of MOTION-PIPELINE section 6, tier 2: the probe numbers a `--probe` build of the
 * scene mirrors into `telemetry*` view-model properties, read back every frame through
 * [RiveDesktopScene.getNumber] and shown as the same instruments the CLI tools print - a sparkline,
 * the value and its per-frame delta, a spacing class, and on demand a motion signature in the JSON
 * shape `probe.py` writes.
 *
 * Nothing here touches the production avatar path; it is the sweatbox's instrument panel.
 */

/** The prefix `gen_scene.py --probe` gives every telemetry property it injects. */
const val TELEMETRY_PREFIX: String = "telemetry"

/** Below this a property is not moving, and asking how it is spaced is meaningless. */
private const val STILL_EPSILON = 1e-4f

/** Section 2's rule: one step taking more than this share of the recent travel is a pop. */
private const val POP_SHARE = 0.45f

/** How far apart the two halves' mean step must be before the motion reads as eased at all. */
private const val EASE_RATIO = 1.25f

/** A property is settled once it is within this share of its own travel of where it ends. */
private const val SETTLE_SHARE = 0.02f

/** Eight levels, the same alphabet the text sparklines in the CLI tools use. */
private const val SPARK_LEVELS = 8

/**
 * How a run of samples is spaced, in the animator's vocabulary MOTION-PIPELINE section 1 uses:
 * `ease-in` is the slow-in *to* the target (big steps first), `ease-out` the accelerating exit.
 */
enum class Spacing(val label: String) {
    STILL("still"),
    POP("pop"),
    EASE_IN("ease-in"),
    EASE_OUT("ease-out"),
    LINEAR("linear"),
}

/** Classifies a window of samples. See [Spacing]; the pop rule is section 2's. */
fun spacingOf(samples: List<Float>): Spacing {
    if (samples.size < 3) return Spacing.STILL
    val steps = samples.zipWithNext { a, b -> abs(b - a) }
    val travel = steps.sum()
    if (travel < STILL_EPSILON) return Spacing.STILL
    if (steps.max() > POP_SHARE * travel) return Spacing.POP
    val half = steps.size / 2
    val early = steps.take(half).average().toFloat()
    val late = steps.drop(steps.size - half).average().toFloat()
    return when {
        early > EASE_RATIO * late -> Spacing.EASE_IN
        late > EASE_RATIO * early -> Spacing.EASE_OUT
        else -> Spacing.LINEAR
    }
}

/** The ten numbers section 2 says describe a beat, for one property. */
data class MotionSignature(
    val peak: Float,
    val peakFrame: Int,
    val overshootPct: Float,
    val settleFrame: Int,
    val maxDelta: Float,
    val spacing: Spacing,
)

/** `probe.py`'s FLAT: a step or a range this small is no movement at all. */
private const val SIGNATURE_FLAT = 1e-6f

/** `probe.py`: overshoot is only read off a move whose rest-to-end step is at least this share of its range. */
private const val STEP_SHARE_OF_RANGE = 0.2f

/**
 * A signature over one scenario's samples, by `probe.py`'s rules so a bench signature and a CLI
 * golden agree: rest is where the run starts, the peak is the furthest it travels from rest,
 * overshoot is read only off a real step to a new resting value, and the settle frame is the last
 * one still outside [SETTLE_SHARE] of the whole range from where the run ends.
 */
fun signatureOf(samples: List<Float>): MotionSignature {
    if (samples.isEmpty()) return MotionSignature(0f, 0, 0f, 0, 0f, Spacing.STILL)
    val rest = samples.first()
    val end = samples.last()
    val range = samples.max() - samples.min()
    val peakFrame = samples.indices.maxBy { abs(samples[it] - rest) }
    val peak = samples[peakFrame]
    val tolerance = max(range * SETTLE_SHARE, SIGNATURE_FLAT)
    val settle = samples.indices.lastOrNull { abs(samples[it] - end) > tolerance } ?: 0
    val maxDelta = samples.zipWithNext { a, b -> abs(b - a) }.maxOrNull() ?: 0f
    return MotionSignature(peak, peakFrame, overshootPct(rest, end, peak, range), settle, maxDelta, spacingOf(samples))
}

/**
 * How far past the rest-to-end step the peak went, in percent, never below zero. A self-returning
 * beat ends where it started and a drifting loop ends a hair away from it: neither is a step, so
 * both read 0 rather than a meaningless thousands of percent.
 */
private fun overshootPct(rest: Float, end: Float, peak: Float, range: Float): Float {
    val step = end - rest
    if (abs(step) <= SIGNATURE_FLAT || abs(step) < STEP_SHARE_OF_RANGE * range) return 0f
    return max(0f, ((peak - rest) / step - 1f) * 100f)
}

/** `probe.py`'s shape: `{property: {peak, peak_frame, overshoot_pct, settle_frame, max_delta, spacing}}`. */
fun signatureJson(signatures: Map<String, MotionSignature>): String =
    signatures.entries.joinToString(prefix = "{", postfix = "}") { (name, s) ->
        // Locale.ROOT: a decimal-comma locale would otherwise write `"peak": 1,250`, which is not JSON.
        val body = """"peak": %.3f, "peak_frame": %d, "overshoot_pct": %.1f, "settle_frame": %d, """
            .format(Locale.ROOT, s.peak, s.peakFrame, s.overshootPct, s.settleFrame) +
            """"max_delta": %.3f, "spacing": "%s"""".format(Locale.ROOT, s.maxDelta, s.spacing.label)
        """"$name": {$body}"""
    }

/**
 * One probed property's rolling window. The samples are snapshot state, so the panel redraws as the
 * scene advances without the bench having to copy a list every frame.
 */
class TelemetryTrack(val name: String, private val capacity: Int) {
    private val ring = mutableStateListOf<Float>()

    val samples: List<Float> get() = ring
    var value: Float by mutableFloatStateOf(0f)
        private set
    var delta: Float by mutableFloatStateOf(0f)
        private set

    fun push(sample: Float) {
        delta = if (ring.isEmpty()) 0f else sample - value
        value = sample
        ring.add(sample)
        while (ring.size > capacity) ring.removeAt(0)
    }

    fun clear() {
        ring.clear()
        value = 0f
        delta = 0f
    }
}

/**
 * Collects one scenario's samples for every probed property and turns them into signatures. Driven
 * by the bench's frame loop; it holds no scope and no timer of its own.
 */
class SignatureRecorder {
    private val runs = LinkedHashMap<String, MutableList<Float>>()
    var scenario: String = ""
        private set

    fun start(label: String) {
        runs.clear()
        scenario = label
    }

    fun push(name: String, sample: Float) {
        runs.getOrPut(name) { mutableListOf() }.add(sample)
    }

    val frames: Int get() = runs.values.firstOrNull()?.size ?: 0

    fun signatures(): Map<String, MotionSignature> = runs.mapValues { (_, samples) -> signatureOf(samples) }
}

/** Eight-level bar heights for a window, normalised over the window's own range. */
private fun sparkLevels(samples: List<Float>): List<Int> {
    if (samples.isEmpty()) return emptyList()
    val lo = samples.min()
    val hi = samples.max()
    val span = hi - lo
    return samples.map { if (span < STILL_EPSILON) 1 else 1 + ((it - lo) / span * (SPARK_LEVELS - 1)).roundToInt() }
}

/** The X-sheet strip: one sparkline, value, delta and spacing class per probed property. */
@Composable
fun TelemetryPanel(tracks: List<TelemetryTrack>, windowSeconds: Float, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        Text(
            "telemetry - ${tracks.size} probed properties, last %.0f s".format(windowSeconds),
            color = Color(0xFFBBBBBB),
            style = MaterialTheme.typography.labelLarge,
        )
        if (tracks.isEmpty()) {
            Text(
                "no `$TELEMETRY_PREFIX*` properties in this file - build one with `gen_scene.py --probe`",
                color = Color(0xFF808080),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        tracks.forEach { track -> TelemetryRow(track) }
    }
}

@Composable
private fun TelemetryRow(track: TelemetryTrack) {
    val samples = track.samples.toList()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair)) {
        Text(
            "${track.name.removePrefix(TELEMETRY_PREFIX).trimStart('_', '.')}  %+.2f  d %+.3f  ${spacingOf(samples).label}"
                .format(track.value, track.delta),
            color = Color(0xFFDDDDDD),
            style = MaterialTheme.typography.labelSmall,
        )
        Sparkline(samples, Modifier.fillMaxWidth().height(LettaDimens.Space.xxl).padding(vertical = LettaDimens.Space.hair))
    }
}

/** Eight-level bars: a snap reads as a vertical wall, an ease as a curve. */
@Composable
private fun Sparkline(samples: List<Float>, modifier: Modifier) {
    val levels = sparkLevels(samples)
    Canvas(modifier) {
        if (levels.isEmpty()) return@Canvas
        val barWidth = size.width / levels.size
        levels.forEachIndexed { i, level ->
            val fraction = level / SPARK_LEVELS.toFloat()
            val height = size.height * fraction
            drawRect(
                color = Color(0xFF6FA8FF).copy(alpha = 0.35f + 0.65f * (i / max(1f, levels.size - 1f))),
                topLeft = Offset(i * barWidth, size.height - height),
                size = Size(max(1f, barWidth - 1f), height),
            )
        }
    }
}
