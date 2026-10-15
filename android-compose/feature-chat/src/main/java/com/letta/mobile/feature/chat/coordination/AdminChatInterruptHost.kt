package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.chat.runtime.ChatRunInterruptHost
import com.letta.mobile.feature.chat.state.ChatBannerController

/**
 * letta-mobile-by4vm.14: the chat screen's side of the shared [com.letta.mobile.data.chat.runtime.ChatRunInterrupter]:
 * every step of a stop lands on [ChatUiState][com.letta.mobile.ui.chat.render.ChatUiState] through the banner.
 */
internal class AdminChatInterruptHost(
    private val banner: ChatBannerController,
    /** Starts the ghost-resume guard once a cancelled turn's terminal has landed. */
    private val onTerminalAfterCancel: () -> Unit,
) : ChatRunInterruptHost<String> {
    override fun onCancelling(key: String) = banner.beginCancelling()

    override fun onForcedClear(key: String, reason: String) = banner.forceClearStreamingAfterInterrupt()

    override fun onSettled(key: String) {
        banner.clearCancelling()
        onTerminalAfterCancel()
    }

    override fun onAbortFailed(key: String, failure: Exception) = banner.showMappedError(failure, "Failed to stop run")
}
