package com.letta.mobile.ui.mascot

import android.content.res.Resources
import android.util.Log
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.security.MessageDigest
import java.io.File
import java.io.ByteArrayOutputStream
import com.letta.mobile.ui.mascot.MascotStills
import com.letta.mobile.ui.mascot.MascotStillStore
import com.letta.mobile.avatar.rive.MascotStillRenderer
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalLifecycleOwner
import app.rive.core.ComposeFrameTicker
import kotlinx.coroutines.flow.collectLatest
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
    /** Where captured stills are kept between launches (the app's cache directory). */
    stillsDirectory: File,
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
    val pollNeeded: Boolean get() = loading || activeSurfaces > 0 || capturing > 0
    private var loading by mutableStateOf(false)
    private var activeSurfaces by mutableIntStateOf(0)
    private var capturing by mutableIntStateOf(0)

    /**
     * One still per identity, rendered offscreen once with the state machine settled and kept on
     * disk (see [MascotStills]); every mascot that is not moving draws it as an image.
     */
    override val stills: MascotStills = MascotStills(
        assetVersion = mascotAssetVersion(resources),
        store = AndroidMascotStillStore(File(stillsDirectory, "mascot-stills")),
        capture = ::captureStill,
    )

    /** The one offscreen scene stills are rendered in; main thread only (see [MascotStillRenderer]). */
    private var stillRenderer: MascotStillRenderer? = null

    private suspend fun captureStill(identity: MascotIdentity): ByteArray? {
        val loaded = file ?: return null
        capturing++
        try {
            // On the main thread, which drives the worker; one small offscreen render per identity, ever.
            val bitmap = withContext(Dispatchers.Main) {
                val renderer = stillRenderer ?: MascotStillRenderer(
                    loaded,
                    worker,
                    sizePx = MascotStills.SIZE_PX,
                    settleFrames = MascotStills.SETTLE_FRAMES,
                    frameSeconds = MascotStills.FRAME_SECONDS,
                ).also { stillRenderer = it }
                renderer.render(identity)
            } ?: return null
            return withContext(Dispatchers.Default) {
                ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            }
        } finally {
            capturing--
        }
    }

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

    /** A surface of an agent that is live elsewhere: its captured still, or nothing until it exists. */
    @Composable
    private fun MascotStill(entry: AndroidMascotEntry, modifier: Modifier) {
        val identity = entry.identity
        LaunchedEffect(identity) { stills.ensure(identity) }
        stills.get(identity)?.let { Image(it, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit) }
    }

    fun close() {
        liveDrivers.clear()
        stillRenderer?.close()
        stillRenderer = null
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
    val host = remember(worker) { AndroidMascotHost(worker, context.resources, context.cacheDir) }
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

/** A short, stable fingerprint of the shipped mascot: a new file means new stills. */
private fun mascotAssetVersion(resources: Resources): String = runCatching {
    val bytes = resources.openRawResource(R.raw.mascot).use { it.readBytes() }
    MessageDigest.getInstance("SHA-256").digest(bytes).take(6).joinToString("") { "%02x".format(it) }
}.getOrDefault("unknown")

/** Stills in the app's cache directory as `<key>.png`: the system may clear it, and they are recaptured. */
private class AndroidMascotStillStore(private val directory: File) : MascotStillStore {
    override suspend fun read(key: String): ByteArray? = withContext(Dispatchers.IO) {
        File(directory, "$key.png").takeIf { it.isFile }?.readBytes()
    }

    override suspend fun write(key: String, png: ByteArray) {
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            val temp = File(directory, "$key.tmp")
            temp.writeBytes(png)
            if (!temp.renameTo(File(directory, "$key.png"))) temp.delete()
        }
    }
}
