package com.letta.mobile.desktop.avatar.rive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.letta.mobile.avatar.core.AvatarState
import com.letta.mobile.avatar.rive.RiveAvatarRuntime
import kotlinx.coroutines.delay

/**
 * `-Drive.spike.record=enter-thinking` presses "record signature" on that scenario once the file has
 * loaded, so a signature can be taken from a script - or by an agent that cannot click the window.
 */
private val RECORD_ON_START: String? = System.getProperty("rive.spike.record")?.takeIf { it.isNotBlank() }

/** `-Drive.spike.onion=true` opens the bench with the onion skin already on. */
internal val ONION_ON_START = System.getProperty("rive.spike.onion").toBoolean()

/** The telemetry window: four seconds at the frame clock's nominal rate (MOTION-PIPELINE section 6). */
internal const val TELEMETRY_WINDOW_SECONDS = 4f
private const val NOMINAL_FPS = 60
private const val TELEMETRY_CAPACITY = (TELEMETRY_WINDOW_SECONDS * NOMINAL_FPS).toInt()

/** How long "record signature" watches a scenario before it prints: long enough for a beat to settle. */
internal const val RECORD_MILLIS = 2500L

/** How long an Enter recording waits in idle first, so the entry it records starts from StateIdle. */
private const val RESET_MILLIS = 1200L

/**
 * A named thing the bench can play and record: a state entry, or one of the gesture triggers the
 * state buttons already fire. Same list `scenarios.py` will carry on the CLI side.
 */
internal sealed interface BenchScenario {
    val label: String

    data class Enter(val state: AvatarState) : BenchScenario {
        override val label: String get() = "enter-${state.name.lowercase()}"
    }

    data class Gesture(val gesture: String) : BenchScenario {
        override val label: String get() = gesture
    }
}

internal val BENCH_SCENARIOS: List<BenchScenario> =
    AvatarState.entries.map(BenchScenario::Enter) + BenchScenario.Gesture(RiveAvatarRuntime.BLINK_GESTURE)

/** The sweatbox instruments' state: onion skin, telemetry tracks and signatures (MOTION-PIPELINE section 6). */
@Stable
internal class BenchInstruments {
    var onionOn by mutableStateOf(ONION_ON_START)
    var onionFrames by mutableIntStateOf(8)
    var onionStride by mutableIntStateOf(3)
    var onionTint by mutableStateOf(true)

    var tracks by mutableStateOf(emptyList<TelemetryTrack>())
        private set
    val recorder = SignatureRecorder()
    var recording by mutableStateOf(false)
        private set
    var scenario by mutableStateOf<BenchScenario>(BenchScenario.Enter(AvatarState.IDLE))
    var recordToken by mutableIntStateOf(0)
        private set
    var lastSignature by mutableStateOf("")
        private set

    /** The onion skin the surface draws, or null while it is off. */
    val onion: RiveOnionSkin?
        get() = if (onionOn) RiveOnionSkin(frames = onionFrames, stride = onionStride, tint = onionTint) else null

    fun requestRecording() {
        recordToken++
    }

    /**
     * Which probes the loaded file carries. A file built without `gen_scene.py --probe` has none, and
     * an older bridge DLL has no readback at all; both come back as an empty list.
     */
    fun discoverProbes(scene: RiveDesktopScene) {
        val probeNames = scene.numberNames().filter { it.startsWith(TELEMETRY_PREFIX) }.sorted()
        tracks = probeNames.map { TelemetryTrack(it, TELEMETRY_CAPACITY) }
        println(
            "rive-spike telemetry: readback=${RiveBridgeNative.PROBE_READBACK} " +
                "artboardByName=${RiveBridgeNative.ARTBOARD_BY_NAME} probes=${probeNames.size} $probeNames",
        )
        recordOnStart()
    }

    /** `-Drive.spike.record`: only ever the first time, so a re-run of the effect never records twice. */
    private fun recordOnStart() {
        val requested = RECORD_ON_START?.takeIf { recordToken == 0 } ?: return
        val chosen = BENCH_SCENARIOS.firstOrNull { it.label == requested }
        if (chosen == null) {
            println("rive-spike signature: no scenario '$requested'; have ${BENCH_SCENARIOS.map { it.label }}")
            return
        }
        scenario = chosen
        recordToken++
    }

    /** One read per property per frame, on the same frame clock the surface renders on. */
    suspend fun sample(scene: RiveDesktopScene) {
        val sampled = tracks
        if (sampled.isEmpty()) return
        while (true) {
            withFrameNanos { sampled.forEach { track -> read(scene, track) } }
        }
    }

    private fun read(scene: RiveDesktopScene, track: TelemetryTrack) {
        val value = scene.getNumber(track.name) ?: return
        track.push(value)
        if (recording) recorder.push(track.name, value)
    }

    /** "Record signature": play the chosen scenario, watch it settle, print probe.py's JSON. */
    suspend fun record(play: (BenchScenario) -> Unit) {
        val chosen = scenario
        if (chosen is BenchScenario.Enter && chosen.state != AvatarState.IDLE) {
            // Writing `state` does not restart the machine, and the Expression layer has no
            // self-transition: a run that already sits in the target state would record flat
            // tracks. Settle in idle first, the way probe.py's driver scenarios start.
            play(BenchScenario.Enter(AvatarState.IDLE))
            delay(RESET_MILLIS)
        }
        recorder.start(chosen.label)
        recording = true
        play(chosen)
        delay(RECORD_MILLIS)
        recording = false
        val json = signatureJson(recorder.signatures())
        lastSignature = json
        println("rive-spike signature: scenario=${recorder.scenario} frames=${recorder.frames} $json")
    }
}

/** The instruments' three effects: probe discovery on load, per-frame sampling, and recording on request. */
@Composable
internal fun BenchInstrumentEffects(
    scene: RiveDesktopScene,
    loaded: Boolean,
    bench: BenchInstruments,
    play: (BenchScenario) -> Unit,
) {
    LaunchedEffect(loaded) {
        if (loaded) bench.discoverProbes(scene)
    }
    LaunchedEffect(bench.tracks) { bench.sample(scene) }
    LaunchedEffect(bench.recordToken) {
        if (bench.recordToken != 0) bench.record(play)
    }
}
