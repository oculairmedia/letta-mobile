package com.letta.mobile.ui.mascot

import android.content.res.Resources
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    private val entries = MascotEntries<AndroidMascotEntry>(
        create = ::create,
        applyIdentity = { entry, identity -> RiveAvatarContract.applyIdentity(entry.scene.sink, identity) },
    )

    /** Loads the shipped mascot once; a failure leaves every avatar on its fallback rather than crashing. */
    suspend fun load() {
        if (file != null) return
        file = runCatching { RiveFile.load(RiveFileSource.RawRes(R.raw.mascot, resources), worker) }
            .onFailure { Log.w(TAG, "mascot file failed to load; avatars fall back", it) }
            .getOrNull()
    }

    override fun entry(agentId: String, identity: MascotIdentity): MascotEntry? =
        if (file == null) null else entries.get(agentId, identity)

    /**
     * Stills by identity and pixel width: a still is one Rive frame captured from the first tile that
     * asked for it, then an [Image] for every tile after - a list of forty rows costs one GL surface
     * per identity and size, not forty. Compose state so tiles waiting on a capture redraw with it.
     */
    private val stills = mutableStateMapOf<Pair<MascotIdentity, Int>, ImageBitmap>()

    @Composable
    override fun Surface(entry: MascotEntry, modifier: Modifier, playing: Boolean) {
        val scene = (entry as AndroidMascotEntry).scene
        if (playing) {
            RiveMascotSurface(scene, modifier, playing = true)
            return
        }
        BoxWithConstraints(modifier) {
            val key = entry.identity to constraints.maxWidth
            val still = stills[key]
            if (still != null) {
                Image(still, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Fit)
            } else {
                RiveMascotSurface(
                    scene,
                    modifier = Modifier.matchParentSize(),
                    playing = false,
                    onFirstFrame = { getBitmap -> runCatching { stills[key] = getBitmap().asImageBitmap() } },
                )
            }
        }
    }

    fun close() {
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

/**
 * The activity's [MascotHost]: brings up the Rive runtime and a worker bound to this composition's
 * lifecycle, loads the mascot from the first frame on, and releases everything when the root
 * leaves composition. [NoMascotHost] when the native runtime or the worker is unavailable, so a
 * device that cannot draw the mascot draws the orbs it always drew.
 */
@Composable
fun rememberAndroidMascotHost(): MascotHost {
    val context = LocalContext.current
    // The worker calls into JNI as soon as it exists; the runtime must be initialised first.
    val runtimeReady = remember {
        runCatching { app.rive.runtime.kotlin.core.Rive.init(context.applicationContext) }
            .onFailure { Log.w("AndroidMascotHost", "Rive runtime unavailable; avatars fall back", it) }
            .isSuccess
    }
    if (!runtimeReady) return NoMascotHost
    val workerError = remember { mutableStateOf<Throwable?>(null) }
    val worker = rememberRiveWorkerOrNull(workerError) ?: return NoMascotHost
    val host = remember(worker) { AndroidMascotHost(worker, context.resources) }
    LaunchedEffect(host) { host.load() }
    DisposableEffect(host) { onDispose { host.close() } }
    return host
}
