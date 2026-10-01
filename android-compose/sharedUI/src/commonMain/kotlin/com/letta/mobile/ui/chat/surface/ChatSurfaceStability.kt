package com.letta.mobile.ui.chat.surface

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent

/**
 * letta-mobile-bglj6.1: identity-stable versions of the page's host inputs.
 *
 * Hosts naturally build `onIntent`, [ChatSurfaceHost] and [ChatSurfacePlatform] from fresh
 * lambdas on every recomposition (a keystroke on Android, a stream token on desktop). Those
 * reach every timeline row through its callbacks, so an unstable one recomposes the whole
 * timeline per keystroke. These wrappers keep ONE instance whose lambdas forward to the latest
 * value; they change only when the set of affordances (which members are null) changes.
 *
 * [ChatSurfaceHost.resolveAgentName] is the exception: rows call it WHILE they compose, so a
 * forwarder would make every row read the host and recompose whenever it changes. It is passed
 * through as given (a host keeps it stable, e.g. remembered on its name roster), and a new
 * resolver is a new host, so the labels follow a roster that changes.
 */
@Composable
internal fun rememberLatestIntent(onIntent: (ChatSurfaceIntent) -> Unit): (ChatSurfaceIntent) -> Unit {
    val current = rememberUpdatedState(onIntent)
    return remember { { intent -> current.value(intent) } }
}

@Composable
internal fun rememberStableHost(host: ChatSurfaceHost): ChatSurfaceHost {
    val current = rememberUpdatedState(host)
    return remember(host.affordanceShape(), host.resolveAgentName) { forwardingHost(current) }
}

@Composable
internal fun rememberStablePlatform(platform: ChatSurfacePlatform): ChatSurfacePlatform {
    val current = rememberUpdatedState(platform)
    return remember(platform.slotShape(), platform.showKeyboardHints, platform.topChromeInset) { forwardingPlatform(current) }
}

/** Which of [ChatSurfaceHost]'s members are set, as a bit mask. */
internal fun ChatSurfaceHost.affordanceShape(): Int = listOf(
    openCanvas, openAgent, resolveAgentName, openSubagent, openModelPicker, pickWorkingDirectory, openAgentPane, editAgent,
    showOnCanvas,
).foldIndexed(0) { index, mask, member -> if (member != null) mask or (1 shl index) else mask }

private fun ChatSurfacePlatform.slotShape(): Int = listOf(voiceInput, pageBackground, timelineOverlay, onComposerHeightChange)
    .foldIndexed(0) { index, mask, member -> if (member != null) mask or (1 shl index) else mask }

internal fun forwardingHost(current: State<ChatSurfaceHost>): ChatSurfaceHost {
    val host = current.value
    return ChatSurfaceHost(
        openCanvas = if (host.openCanvas == null) null else { { current.value.openCanvas?.invoke() } },
        showOnCanvas = if (host.showOnCanvas == null) null else { { receipt -> current.value.showOnCanvas?.invoke(receipt) } },
        openAgent = if (host.openAgent == null) null else { { agentId -> current.value.openAgent?.invoke(agentId) } },
        resolveAgentName = host.resolveAgentName,
        openSubagent = if (host.openSubagent == null) {
            null
        } else {
            { toolCallId, subagentAgentId, description ->
                current.value.openSubagent?.invoke(toolCallId, subagentAgentId, description)
            }
        },
        openModelPicker = if (host.openModelPicker == null) null else { { current.value.openModelPicker?.invoke() } },
        pickWorkingDirectory = if (host.pickWorkingDirectory == null) {
            null
        } else {
            { current.value.pickWorkingDirectory?.invoke() }
        },
        openAgentPane = if (host.openAgentPane == null) null else { { current.value.openAgentPane?.invoke() } },
        editAgent = if (host.editAgent == null) null else { { current.value.editAgent?.invoke() } },
    )
}

private fun forwardingPlatform(current: State<ChatSurfacePlatform>): ChatSurfacePlatform {
    val platform = current.value
    return ChatSurfacePlatform(
        voiceInput = if (platform.voiceInput == null) {
            null
        } else {
            @Composable { onDictated -> current.value.voiceInput?.invoke(onDictated) }
        },
        pageBackground = if (platform.pageBackground == null) {
            null
        } else {
            @Composable { content ->
                val background = current.value.pageBackground
                if (background != null) background(content) else content()
            }
        },
        showKeyboardHints = platform.showKeyboardHints,
        topChromeInset = platform.topChromeInset,
        timelineOverlay = if (platform.timelineOverlay == null) null else { @Composable { current.value.timelineOverlay?.invoke() } },
        onComposerHeightChange = if (platform.onComposerHeightChange == null) {
            null
        } else {
            { height -> current.value.onComposerHeightChange?.invoke(height) }
        },
    )
}
