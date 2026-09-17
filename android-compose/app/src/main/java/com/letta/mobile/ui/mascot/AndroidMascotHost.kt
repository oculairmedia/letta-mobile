package com.letta.mobile.ui.mascot

import android.content.res.Resources
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalLifecycleOwner
import app.rive.core.ComposeFrameTicker
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.rive.RiveFile
import app.rive.RiveFileSource
import app.rive.core.RiveWorker
import app.rive.rememberRiveWorkerOrNull
import com.letta.mobile.avatar.core.AvatarState
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.rive.AndroidMascotScene
import com.letta.mobile.avatar.rive.MASCOT_MODEL
import com.letta.mobile.avatar.rive.R
import com.letta.mobile.avatar.rive.RiveAvatarContract
import com.letta.mobile.avatar.rive.RiveAvatarRuntime
import com.letta.mobile.avatar.rive.RiveMascotSurface

/** A live mascot on Android: this agent's Rive scene plus the shared runtime and director over it. */
class AndroidMascotEntry(val scene: AndroidMascotScene, runtime: RiveAvatarRuntime, identity: MascotIdentity) :
    MascotEntry(runtime, identity, applyState = { state: AvatarState -> runtime.applyState(state) }) {
    private val rive = runtime

    override suspend fun load() = rive.load(MASCOT_MODEL)

    override fun writeIdentity(identity: MascotIdentity) = RiveAvatarContract.applyIdentity(scene.sink, identity)

    override fun dispose() = scene.close()
}

/**
 * Android's contribution to the shared mascot: one loaded `.riv` on one Rive worker for the
 * activity, one scene per agent kept for the activity's life, painted by the Rive composable.
 * Presence, time, gaze and sizing are the shared [MascotAvatar]'s; nothing here is policy.
 *
 * An instance, not an object: the worker and the entries belong to the activity that provided
 * them through [LocalMascotHost] (see [rememberAndroidMascotHost]) and go away with it.
 */
class AndroidMascotHost(
    private val worker: RiveWorker,
    private val resources: Resources,
) : MascotHost {
    /** Compose state so every tile drawing its fallback recomposes into the live mascot once the file lands. */
    private var file by mutableStateOf<RiveFile?>(null)
    private val entries = MascotEntries<AndroidMascotEntry>(create = ::create)

    /**
     * Whether the worker's message poll must run. The runtime's default polls on every vsync for
     * the life of the activity, which keeps Compose's frame clock busy on every screen whether a
     * mascot is drawn or not; here it runs only while a Rive surface is composed (a live companion,
     * a still being captured) or the file is loading (the load completes through a polled message).
     */
    val pollNeeded: Boolean get() = loading || activeSurfaces > 0
    private var loading by mutableStateOf(false)
    private var activeSurfaces by mutableIntStateOf(0)

    /** Loads the shipped mascot once; a failure leaves every avatar on its fallback rather than crashing. */
    suspend fun load() {
        if (file != null) return
        loading = true
        try {
            file = runCatching { RiveFile.load(RiveFileSource.RawRes(R.raw.mascot, resources), worker) }
                .onFailure { Log.w(TAG, "mascot file failed to load; avatars fall back", it) }
                .getOrNull()
        } finally {
            loading = false
        }
    }

    /** A Rive surface for the duration of [content]'s composition, counted so the poll runs only then. */
    @Composable
    private fun CountedSurface(content: @Composable () -> Unit) {
        DisposableEffect(Unit) {
            activeSurfaces++
            onDispose { activeSurfaces-- }
        }
        content()
    }

    override val available: Boolean get() = file != null

    override fun entry(agentId: String, identity: MascotIdentity): MascotEntry? =
        if (file == null) null else entries.get(agentId, identity)

    /**
     * Stills by identity and pixel width: a still is one Rive frame captured from the first tile that
     * asked for it, then an [Image] for every tile after - a list of forty rows costs one GL surface
     * per identity and size, not forty. Compose state so tiles waiting on a capture redraw with it.
     */
    private val stills = mutableStateMapOf<Pair<MascotIdentity, Int>, ImageBitmap>()

    /**
     * Which surface drives each agent's scene. The Rive composable advances its own state machine
     * every frame, so two live surfaces of one agent would be two clocks over one character: the
     * first surface that asks to play owns the entry, every other request for it draws the still,
     * and ownership passes on when the driver leaves composition. Compose state so the waiting
     * surface upgrades itself the moment the driver is gone.
     */
    private val liveDrivers = mutableStateMapOf<AndroidMascotEntry, LiveSurfaceToken>()

    @Composable
    override fun Surface(entry: MascotEntry, modifier: Modifier, playing: Boolean) {
        val live = entry as AndroidMascotEntry
        val token = remember { LiveSurfaceToken() }
        val driver = liveDrivers[live]
        val drives = playing && (driver == null || driver === token)
        DisposableEffect(live, playing) {
            if (playing && liveDrivers[live] == null) liveDrivers[live] = token
            onDispose { if (liveDrivers[live] === token) liveDrivers.remove(live) }
        }
        // A surface that has been live freezes where it is when it stops (same Rive surface, no
        // more advancing) rather than swapping to the cached first frame: the header chip keeps
        // the pose the character was in when the run started. One call site, so the bound
        // artboard + state machine survive the flip. Surfaces that were never live draw the still.
        var wasLive by remember { mutableStateOf(false) }
        if (drives) wasLive = true
        if (drives || wasLive) {
            CountedSurface { RiveMascotSurface(live.scene, modifier, playing = drives) }
        } else {
            MascotStill(live, modifier)
        }
    }

    @Composable
    private fun MascotStill(entry: AndroidMascotEntry, modifier: Modifier) {
        BoxWithConstraints(modifier) {
            val key = entry.identity to constraints.maxWidth
            val still = stills[key]
            if (still != null) {
                Image(still, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Fit)
            } else {
                CountedSurface {
                    RiveMascotSurface(
                        entry.scene,
                        modifier = Modifier.matchParentSize(),
                        playing = false,
                        onFirstFrame = { getBitmap -> runCatching { stills[key] = getBitmap().asImageBitmap() } },
                    )
                }
            }
        }
    }

    fun close() {
        liveDrivers.clear()
        stills.clear()
        entries.closeAll()
        file?.let { runCatching { it.close() } }
        file = null
    }

    private fun create(identity: MascotIdentity): AndroidMascotEntry? {
        val loaded = file ?: return null
        val scene = AndroidMascotScene.create(loaded, identity) ?: return null
        return AndroidMascotEntry(scene, RiveAvatarRuntime(scene.sink), identity)
    }

    private companion object {
        const val TAG = "AndroidMascotHost"
    }
}

/** Identity of one composed surface; compared by instance so two tiles of one agent don't share a clock. */
private class LiveSurfaceToken

/**
 * The activity's [MascotHost]: brings up the Rive runtime and a worker bound to this composition's
 * lifecycle, loads the mascot after initial display, and releases everything when the root
 * leaves composition. [NoMascotHost] until two vsyncs have passed (and when the native runtime
 * or the worker is unavailable), so TTFD is not the Rive JNI init / worker poll and a device
 * that cannot draw the mascot draws the orbs it always drew.
 *
 * One `withFrameNanos` is not enough: that continuation can run in the same Choreographer
 * callback that reports timeToInitialDisplay. Warm startup then sits on the perf-gate
 * ceiling (487ms). Two frames plus a third before [AndroidMascotHost.load] keeps JNI and
 * [RiveWorker.beginPolling] off that path.
 */
@Composable
fun rememberAndroidMascotHost(): MascotHost {
    val context = LocalContext.current
    var afterFirstFrame by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        afterFirstFrame = true
    }
    if (!afterFirstFrame) return NoMascotHost
    // The worker calls into JNI as soon as it exists; the runtime must be initialised first.
    val runtimeReady = remember {
        runCatching { app.rive.runtime.kotlin.core.Rive.init(context.applicationContext) }
            .onFailure { Log.w("AndroidMascotHost", "Rive runtime unavailable; avatars fall back", it) }
            .isSuccess
    }
    if (!runtimeReady) return NoMascotHost
    val workerError = remember { mutableStateOf<Throwable?>(null) }
    // autoPoll off: the host decides when the per-frame poll runs (see [AndroidMascotHost.pollNeeded]).
    val worker = rememberRiveWorkerOrNull(workerError, autoPoll = false) ?: return NoMascotHost
    val host = remember(worker) { AndroidMascotHost(worker, context.resources) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(host) {
        snapshotFlow { host.pollNeeded }.collectLatest { needed ->
            if (needed) runCatching { worker.beginPolling(lifecycle, ComposeFrameTicker) }
        }
    }
    LaunchedEffect(host) {
        withFrameNanos { }
        host.load()
    }
    DisposableEffect(host) { onDispose { host.close() } }
    return host
}
