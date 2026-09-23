package com.letta.mobile.desktop.avatar.rive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.letta.mobile.avatar.core.AvatarState
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.rive.MASCOT_MODEL
import com.letta.mobile.avatar.rive.RiveAvatarContract
import com.letta.mobile.avatar.rive.RiveAvatarRuntime
import com.letta.mobile.ui.mascot.MascotEntries
import com.letta.mobile.ui.mascot.MascotEntry
import com.letta.mobile.ui.mascot.MascotHost
import com.letta.mobile.ui.mascot.MascotStills
import kotlinx.coroutines.withContext

/**
 * A live mascot on desktop: this agent's native scene plus the shared runtime and director over it.
 * The scene does not exist until the first surface draws the agent: [load] brings it up on the
 * Rive thread (device, file, identity, a random head start) and [scene] turns non-null - Compose
 * state, so the surface that was waiting recomposes into it. Input writes made before then are
 * kept in order and replayed onto the scene, so the identity written at creation is the first
 * thing the scene sees, before its first advance (a body drawn before it has a colour is black).
 */

class DesktopMascotEntry private constructor(
    private val bytes: ByteArray,
    identity: MascotIdentity,
    private val sink: DeferredSink,
    private val rive: RiveAvatarRuntime,
) : MascotEntry(rive, identity, applyState = { state: AvatarState -> rive.applyState(state) }) {
    private constructor(bytes: ByteArray, identity: MascotIdentity, sink: DeferredSink) :
        this(bytes, identity, sink, RiveAvatarRuntime(sink))

    constructor(bytes: ByteArray, identity: MascotIdentity) : this(bytes, identity, DeferredSink())

    /** The native scene once [load] has brought it up; null (nothing drawn) until then. */
    var scene: RiveDesktopScene? by mutableStateOf(null)
        private set

    /** The scene could not be created: the host stops offering this entry and surfaces draw their fallback. */
    var failed: Boolean by mutableStateOf(false)
        private set

    init {
        // Queued in the deferred sink: the first write the scene will see once it exists.
        RiveAvatarContract.applyIdentity(sink, identity)
    }

    override suspend fun load() {
        val created = withContext(RiveThread.dispatcher) {
            runCatching {
                val scene = RiveDesktopScene.create()
                runCatching {
                    scene.load(bytes)
                    // Host rule: identity before the first advance, or the first frame is a black body -
                    // attaching replays every write made so far, the identity first among them.
                    sink.attach(scene.inputSink)
                }.onFailure { scene.close() }.getOrThrow() // a scene that failed after creation is released, not leaked
                scene
            }.onFailure { System.err.println("[mascot] scene failed to load: ${'$'}it") }.getOrNull()
        }
        if (created == null) {
            failed = true
            return
        }
        rive.load(MASCOT_MODEL)
        scene = created
    }

    override fun writeIdentity(identity: MascotIdentity) = RiveAvatarContract.applyIdentity(sink, identity)

    override fun dispose() {
        scene?.close()
        scene = null
    }
}

/**
 * Desktop's contribution to the shared mascot: bring up a native scene per agent (the bridge
 * DLL + the shipped `.riv`) and paint it as a Compose node. Presence, time, gaze and sizing are
 * the shared [com.letta.mobile.ui.mascot.MascotAvatar]'s; nothing here is policy.
 */
object DesktopMascotHost : MascotHost {
    private val entries = MascotEntries<DesktopMascotEntry>(create = ::create)

    /** The shipped mascot, packaged as a JVM resource by :avatar:renderer-rive (the same bytes Android bundles as res/raw). */
    private val mascotBytes: ByteArray? by lazy {
        RiveAvatarContract::class.java.getResourceAsStream("/mascot/mascot.riv")?.use { it.readBytes() }
    }

    override val available: Boolean get() = RiveBridgeNative.AVAILABLE && mascotBytes != null

    override fun entry(agentId: String, identity: MascotIdentity): MascotEntry? =
        if (available) entries.get(agentId, identity)?.takeUnless { it.failed } else null

    @Composable
    override fun Surface(entry: MascotEntry, modifier: Modifier, playing: Boolean) {
        // Nothing until the scene is up; the entry's state flips and this recomposes into it.
        val scene = (entry as DesktopMascotEntry).scene ?: return
        RiveDesktopSurface(scene, modifier, playing = playing)
    }

    /** Captured once per identity and kept on disk; see [MascotStills]. */
    override val stills: MascotStills? by lazy {
        val bytes = mascotBytes
        if (bytes == null || !RiveBridgeNative.AVAILABLE) {
            null
        } else {
            MascotStills(
                assetVersion = mascotAssetVersion(bytes),
                store = DesktopMascotStillStore(),
                capture = { identity -> captureDesktopMascotStill(bytes, identity) },
            )
        }
    }

    fun closeAll() = entries.closeAll()

    /** Cheap: the native scene is created by [DesktopMascotEntry.load], from the first surface that draws it. */
    private fun create(identity: MascotIdentity): DesktopMascotEntry? {
        val bytes = mascotBytes ?: return null
        return DesktopMascotEntry(bytes, identity)
    }
}
