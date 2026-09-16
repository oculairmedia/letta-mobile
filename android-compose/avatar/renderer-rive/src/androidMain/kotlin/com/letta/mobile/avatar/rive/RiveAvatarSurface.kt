package com.letta.mobile.avatar.rive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.rive.Result
import app.rive.RiveFileSource
import app.rive.rememberRiveFile
import app.rive.rememberRiveWorker
import com.letta.mobile.avatar.core.AvatarState
import com.letta.mobile.avatar.core.MascotIdentity

/**
 * The 2D mascot, rendered by Rive and driven by the director's arbitrated [state] - the bench
 * form, which owns its own worker, file and scene. The app draws mascots through the shared
 * `MascotHost` instead, where one scene per agent outlives the view; this composable is what the
 * debug bench and previews use.
 *
 * Everything this composable decides is lifecycle: acquire the worker, load the file, instantiate
 * the scene. What to write into that scene is not decided here - it goes through
 * [RiveAvatarRuntime], the same shared runtime the desktop surface uses, so the two cannot drift
 * into disagreeing about what SPEAKING looks like.
 *
 * A failure renders nothing rather than a broken frame. The caller already has a fallback for an
 * avatar that will not load: the agent orb, which is what the director's FAILED state is for.
 */
@Composable
fun RiveAvatarSurface(
    state: AvatarState,
    modifier: Modifier = Modifier,
    identity: MascotIdentity = MascotIdentity.DEFAULT,
    onRuntime: (RiveAvatarRuntime) -> Unit = {},
) {
    val worker = rememberRiveWorker()
    val file = rememberRiveFile(RiveFileSource.RawRes.from(R.raw.mascot), worker)
    if (file !is Result.Success) return

    val scene = remember(file.value) { AndroidMascotScene.create(file.value, identity) } ?: return
    DisposableEffect(scene) { onDispose { scene.close() } }
    val runtime = remember(scene) { RiveAvatarRuntime(scene.sink) }

    // The runtime reports LOADING then IDLE as it loads, so the first frame the mascot draws is
    // already a state it declares rather than whatever the asset happened to be authored in.
    LaunchedEffect(runtime) {
        runtime.load(MASCOT_MODEL)
        onRuntime(runtime)
    }
    LaunchedEffect(runtime, state) { runtime.applyState(state) }

    RiveMascotSurface(scene, modifier)
}
