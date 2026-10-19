package com.letta.mobile.ui.mascot.frame

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import com.letta.mobile.avatar.core.AvatarState
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.rive.MASCOT_MODEL
import com.letta.mobile.avatar.rive.RiveAvatarContract
import com.letta.mobile.avatar.rive.RiveAvatarRuntime
import com.letta.mobile.avatar.rive.RiveInputSink
import com.letta.mobile.avatar.rive.RiveWrite
import com.letta.mobile.avatar.rive.RiveWriteSink
import com.letta.mobile.ui.mascot.MascotEntries
import com.letta.mobile.ui.mascot.MascotEntry
import com.letta.mobile.ui.mascot.MascotHost
import com.letta.mobile.ui.mascot.MascotStills
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A live mascot drawn through the frame pipeline: this agent's offscreen scene plus the shared
 * runtime and director over it. The scene does not exist until the first surface draws the agent:
 * [load] brings it up on the renderer's dispatcher and [scene] turns non-null - Compose state, so
 * the surface that was waiting recomposes into it. Input writes made before then are kept in order
 * and replayed onto the scene, so the identity written at creation is the first thing the scene
 * sees, before its first advance (a body drawn before it has a colour is black).
 */
class FrameMascotEntry private constructor(
    private val renderer: RiveFrameRenderer,
    identity: MascotIdentity,
    private val sink: DeferredSink,
    private val rive: RiveAvatarRuntime,
) : MascotEntry(rive, identity, applyState = { state: AvatarState -> rive.applyState(state) }) {
    private constructor(renderer: RiveFrameRenderer, identity: MascotIdentity, sink: DeferredSink) :
        this(renderer, identity, sink, RiveAvatarRuntime(sink.sink))

    constructor(renderer: RiveFrameRenderer, identity: MascotIdentity) : this(renderer, identity, DeferredSink())

    /** The scene once [load] has brought it up; null (nothing drawn) until then. */
    var scene: RiveFrameScene? by mutableStateOf(null)
        private set

    /** The scene could not be created: the host stops offering this entry and surfaces draw their fallback. */
    var failed: Boolean by mutableStateOf(false)
        private set

    init {
        // Queued in the deferred sink: the first write the scene will see once it exists.
        RiveAvatarContract.applyIdentity(sink.sink, identity)
    }

    override suspend fun load() {
        val created = withContext(renderer.dispatcher) {
            runCatching { renderer.createScene() }
                .onFailure { println("[mascot] scene failed to load: $it") }
                .getOrNull()
        }
        if (created == null) {
            failed = true
            return
        }
        // Back on the UI thread, where every write is made: attaching replays them in order, the
        // identity first, ahead of the first advance the frame loop queues.
        sink.attach(created.inputSink)
        rive.load(MASCOT_MODEL)
        scene = created
    }

    override fun writeIdentity(identity: MascotIdentity) = RiveAvatarContract.applyIdentity(sink.sink, identity)

    override fun dispose() {
        scene?.close()
        scene = null
    }
}

/**
 * The shared contribution of the frame pipeline to the mascot: an offscreen scene per agent,
 * rendered by [renderer] and painted by [RiveFrameSurface] as a Compose node. Desktop and the web
 * bind it with their own renderer; presence, time, gaze and sizing stay the shared
 * [com.letta.mobile.ui.mascot.MascotAvatar]'s, and nothing here is policy.
 */
class FrameMascotHost(private val renderer: RiveFrameRenderer) : MascotHost {
    private val entries = MascotEntries { identity -> FrameMascotEntry(renderer, identity) }
    private val capture = FrameStillCapture(renderer)
    private var cachedStills: MascotStills? = null

    override val available: Boolean get() = renderer.available

    /** [sceneKey] is the scene's key (an agent's id, or a candidate's shape key - see MascotLive). */
    override fun entry(sceneKey: String, identity: MascotIdentity): MascotEntry? =
        if (available) entries.get(sceneKey, identity)?.takeUnless { it.failed } else null

    @Composable
    override fun Surface(entry: MascotEntry, modifier: Modifier, playing: Boolean) {
        // Nothing until the scene is up; the entry's state flips and this recomposes into it.
        val scene = (entry as FrameMascotEntry).scene ?: return
        RiveFrameSurface(scene, renderer.dispatcher, modifier, playing = playing)
    }

    /** Captured once per identity (see [MascotStills]); none until the renderer is available. */
    override val stills: MascotStills?
        get() {
            if (!renderer.available) return null
            return cachedStills ?: MascotStills(
                assetVersion = renderer.assetVersion,
                store = renderer.stillStore,
                capture = capture::capture,
            ).also { cachedStills = it }
        }

    fun closeAll() = entries.closeAll()
}

/**
 * Renders identities offscreen as stills, in one scene kept for every capture: a new scene per
 * capture was a full bring-up each time, where a capture in a warm scene is a few milliseconds.
 * Each capture writes the next identity over the last and settles; the body's transition completes
 * well inside the settle. The scene is only touched on the renderer's dispatcher.
 */
internal class FrameStillCapture(private val renderer: RiveFrameRenderer) {
    private var scene: RiveFrameScene? = null

    private companion object {
        /** One simulated settling frame. */
        val SETTLE_STEP = RiveFrameStep(MascotStills.FRAME_SECONDS)
    }

    /**
     * [identity] as PNG: written into the capture scene, its state machine run
     * [MascotStills.SETTLE_FRAMES] simulated frames so the view-model bindings take effect, one
     * frame rendered, encoded off the render thread.
     */
    suspend fun capture(identity: MascotIdentity): ByteArray? {
        val size = MascotStills.SIZE_PX
        val image = withContext(renderer.dispatcher) {
            runCatching {
                val warm = scene ?: renderer.createScene().also { scene = it }
                RiveAvatarContract.applyIdentity(warm.inputSink, identity)
                repeat(MascotStills.SETTLE_FRAMES) { warm.advance(SETTLE_STEP) }
                warm.renderImage(IntSize(size, size))
            }.onFailure { failure ->
                println("[mascot] still capture failed: $failure")
                // A broken scene is dropped, so the next capture starts clean.
                scene?.close()
                scene = null
            }.getOrNull()
        } ?: return null
        return withContext(Dispatchers.Default) {
            try {
                renderer.encodePng(image)
            } finally {
                image.close()
            }
        }
    }
}

/**
 * An input sink that records writes until a scene exists, then replays them in order and forwards
 * everything after. Lets the runtime and director be built - and written to - before the scene has
 * been created. UI thread only: every write and the attach are made there.
 */
internal class DeferredSink {
    private var target: RiveInputSink? = null
    private val pending = ArrayList<RiveWrite>()

    /** Where the runtime writes: queued until [attach], forwarded after. */
    val sink: RiveInputSink = RiveWriteSink(::write)

    fun attach(scene: RiveInputSink) {
        pending.forEach { it.applyTo(scene) }
        pending.clear()
        target = scene
    }

    private fun write(write: RiveWrite) {
        val t = target
        if (t != null) write.applyTo(t) else pending += write
    }
}
