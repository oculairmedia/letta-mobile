package com.letta.mobile.data.runtime

import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.TurnCommand

/**
 * letta-mobile-by4vm.7: one `runTurn` call as its exit-path steps see it: the command, the lease it
 * holds, the release record every exit writes, and the sink its drafts go to.
 */
internal class TurnRun(
    val command: TurnCommand,
    val lease: LeaseRef,
    val release: TurnReleaseRecord,
    val emit: suspend (RuntimeEventDraft) -> Unit,
)
