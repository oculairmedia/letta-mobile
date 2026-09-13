package com.letta.mobile.avatar.rive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.rive.Result
import app.rive.Rive
import app.rive.RiveFileSource
import app.rive.rememberArtboard
import app.rive.rememberRiveFile
import app.rive.rememberRiveWorker
import app.rive.rememberStateMachineResult
import app.rive.rememberViewModelInstanceResult
import com.letta.mobile.avatar.core.AvatarState

/**
 * The 2D mascot, rendered by Rive and driven by the director's arbitrated [state].
 *
 * Everything this composable decides is lifecycle: acquire the worker, load the file, instantiate
 * the artboard, the state machine and the view model. What to write into that view model is not
 * decided here - it goes through [RiveAvatarRuntime], the same shared runtime the desktop surface
 * will use, so the two cannot drift into disagreeing about what SPEAKING looks like.
 *
 * A failure renders nothing rather than a broken frame. The caller already has a fallback for an
 * avatar that will not load: the agent orb, which is what the director's FAILED state is for.
 */
@Composable
fun RiveAvatarSurface(
    state: AvatarState,
    modifier: Modifier = Modifier,
    onRuntime: (RiveAvatarRuntime) -> Unit = {},
) {
    val worker = rememberRiveWorker()
    val file = rememberRiveFile(RiveFileSource.RawRes.from(R.raw.mascot), worker)
    if (file !is Result.Success) return

    val artboard = rememberArtboard(file.value)
    val stateMachine = rememberStateMachineResult(artboard, RiveAvatarContract.STATE_MACHINE)
    val viewModel = rememberViewModelInstanceResult(file.value)
    if (stateMachine !is Result.Success || viewModel !is Result.Success) return

    val runtime = remember(viewModel.value) {
        RiveAvatarRuntime(ViewModelInstanceInputSink(viewModel.value))
    }

    // The runtime reports LOADING then IDLE as it loads, so the first frame the mascot draws is
    // already a state it declares rather than whatever the asset happened to be authored in.
    LaunchedEffect(runtime) {
        runtime.load(MASCOT_MODEL)
        onRuntime(runtime)
    }

    LaunchedEffect(runtime, state) { runtime.applyState(state) }

    Rive(
        file = file.value,
        modifier = modifier,
        artboard = artboard,
        stateMachine = stateMachine.value,
        viewModelInstance = viewModel.value,
    )
}
