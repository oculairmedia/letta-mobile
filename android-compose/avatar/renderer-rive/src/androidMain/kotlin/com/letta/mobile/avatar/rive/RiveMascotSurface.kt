package com.letta.mobile.avatar.rive

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.rive.Artboard
import app.rive.Result
import app.rive.Rive
import app.rive.RiveFile
import app.rive.RivePointerInputMode
import app.rive.ViewModelInstance
import app.rive.ViewModelSource
import app.rive.rememberArtboardResult
import app.rive.rememberStateMachineResult
import com.letta.mobile.avatar.core.MascotIdentity

/**
 * One agent's mascot on Android: the loaded `.riv` (owned by whoever loaded it; shared by every
 * agent) plus this agent's own view-model instance, which is where identity, state, gaze and the
 * triggers live. Surfaces bind their own artboard + state machine to this instance, so two tiles of
 * the same agent on one screen draw the same character in the same state without either advancing
 * the other's clock - the Rive composable advances its own state machine, the inputs are shared.
 *
 * Identity is written here, before any state machine exists, so the first frame any surface draws
 * already has the right body and colour (the desktop host's "identity before the first advance").
 */
class AndroidMascotScene private constructor(
    val file: RiveFile,
    val viewModelInstance: ViewModelInstance,
) {
    /** The shared runtime's write path into this agent's inputs. */
    val sink: RiveInputSink = ViewModelInstanceInputSink(viewModelInstance)

    /** Releases the view-model instance; the file stays with its owner. */
    fun close() {
        runCatching { viewModelInstance.close() }
    }

    companion object {
        /**
         * Instantiates the mascot's default view model from [file] and writes [identity] into it.
         * Null when the file does not carry the contract's artboard or view model; the caller draws
         * its fallback.
         */
        fun create(file: RiveFile, identity: MascotIdentity): AndroidMascotScene? = runCatching {
            // The default artboard names the default view model; the artboard itself is throwaway -
            // each surface instantiates its own so it can be sized to its own node.
            val probe = Artboard.fromFile(file)
            val instance = try {
                ViewModelInstance.fromFile(file, ViewModelSource.DefaultForArtboard(probe).defaultInstance())
            } finally {
                runCatching { probe.close() }
            }
            AndroidMascotScene(file, instance).also { RiveAvatarContract.applyIdentity(it.sink, identity) }
        }.getOrNull()
    }
}

/**
 * Paints [scene] into [modifier]'s bounds. Owns an artboard and a state machine for this node only,
 * bound to the scene's shared view-model instance; both are released when the node leaves
 * composition, and the Rive composable stops advancing while the lifecycle is not RESUMED or the
 * state machine has settled.
 *
 * [playing] false draws the scene once and skips the advance loop - a still.
 *
 * Pointer input passes through: the tile's own click (and the composer beneath a companion) must
 * keep working; the rig's drag listeners are a desktop pet affordance.
 */
@Composable
fun RiveMascotSurface(
    scene: AndroidMascotScene,
    modifier: Modifier = Modifier,
    playing: Boolean = true,
    /** Called once the first frame is on the surface; the getter is valid only while the surface lives. */
    onFirstFrame: ((getBitmap: () -> Bitmap) -> Unit)? = null,
) {
    val artboard = rememberArtboardResult(scene.file)
    if (artboard !is Result.Success) return
    val stateMachine = rememberStateMachineResult(artboard.value, RiveAvatarContract.STATE_MACHINE)
    if (stateMachine !is Result.Success) return
    Rive(
        file = scene.file,
        modifier = modifier,
        artboard = artboard.value,
        stateMachine = stateMachine.value,
        viewModelInstance = scene.viewModelInstance,
        playing = playing,
        pointerInputMode = RivePointerInputMode.PassThrough,
        onBitmapAvailable = onFirstFrame,
    )
}
