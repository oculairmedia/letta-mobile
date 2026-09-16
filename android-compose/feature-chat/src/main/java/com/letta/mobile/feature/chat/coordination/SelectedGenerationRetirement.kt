package com.letta.mobile.feature.chat.coordination

/**
 * Generation-switch retirement used by [com.letta.mobile.feature.chat.screen.AdminChatViewModel].
 * Presentation must stop before the pipeline, send owner, and captured runtime retire so an
 * in-flight deferred observer cannot write into the replacement generation.
 */
internal suspend fun retireSelectedGeneration(
    stopPresentation: suspend () -> Unit,
    pipeline: ChatPipelineLifetime,
    owner: SelectedChatSendOwner?,
    runtime: SelectedChatRuntime?,
) {
    stopPresentation()
    pipeline.retire()
    owner?.retire()
    runtime?.retire()
}
