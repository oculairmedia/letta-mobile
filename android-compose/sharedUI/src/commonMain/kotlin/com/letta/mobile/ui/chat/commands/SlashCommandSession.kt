package com.letta.mobile.ui.chat.commands

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.data.chat.routing.SlashCommandBinding
import com.letta.mobile.data.chat.routing.SlashCommandSessionPort

/** Binds host-command submission to the page lifetime while reading the host's current target. */
@Composable
fun rememberSlashCommandSessionPort(delegate: ChatSessionPort, binding: SlashCommandBinding): ChatSessionPort {
    val scope = rememberCoroutineScope()
    val currentBinding by rememberUpdatedState(binding)
    return remember(delegate, scope) { SlashCommandSessionPort(delegate, scope) { currentBinding.capture() } }
}
