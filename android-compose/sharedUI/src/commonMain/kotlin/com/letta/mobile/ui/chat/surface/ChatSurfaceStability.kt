package com.letta.mobile.ui.chat.surface

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.letta.mobile.data.chat.projection.CanvasArtifactReceipt
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
internal fun ChatSurfaceHost.affordanceShape(): Int {
    return presenceMask(
        listOf(
            openCanvas, openAgent, resolveAgentName, openSubagent, openModelPicker, pickWorkingDirectory, openAgentPane, editAgent,
            showOnCanvas,
            openAgentSwitcher,
        ),
    )
}

private fun ChatSurfacePlatform.slotShape(): Int {
    return presenceMask(listOf(voiceInput, pageBackground, timelineOverlay, onComposerHeightChange))
}

/** Bit i set when [members]`[i]` is non-null. */
private fun presenceMask(members: List<Any?>): Int {
    return members.foldIndexed(0) { index, mask, member -> if (member != null) mask or (1 shl index) else mask }
}

internal fun forwardingHost(current: State<ChatSurfaceHost>): ChatSurfaceHost {
    val host = current.value
    return ChatSurfaceHost(
        openCanvas = forwardIfSet<() -> Unit>(host.openCanvas) { { current.value.openCanvas?.invoke() } },
        showOnCanvas = forwardedShowOnCanvas(current),
        openAgent = forwardIfSet<(String) -> Unit>(host.openAgent) { { agentId -> current.value.openAgent?.invoke(agentId) } },
        resolveAgentName = host.resolveAgentName,
        openSubagent = forwardIfSet<(String, String?, String) -> Unit>(host.openSubagent) {
            { toolCallId, subagentAgentId, description ->
                current.value.openSubagent?.invoke(toolCallId, subagentAgentId, description)
            }
        },
        openModelPicker = forwardIfSet<() -> Unit>(host.openModelPicker) { { current.value.openModelPicker?.invoke() } },
        pickWorkingDirectory = forwardIfSet<() -> Unit>(host.pickWorkingDirectory) { { current.value.pickWorkingDirectory?.invoke() } },
        openAgentPane = forwardIfSet<() -> Unit>(host.openAgentPane) { { current.value.openAgentPane?.invoke() } },
        editAgent = forwardIfSet<() -> Unit>(host.editAgent) { { current.value.editAgent?.invoke() } },
        openAgentSwitcher = forwardIfSet<() -> Unit>(host.openAgentSwitcher) { { current.value.openAgentSwitcher?.invoke() } },
    )
}

/** Null when the host leaves [member] unset (the affordance stays hidden), else the [forwarder] it builds. */
private inline fun <T : Any> forwardIfSet(member: T?, forwarder: () -> T): T? {
    return if (member == null) null else forwarder()
}

/** The host's "Show on canvas" (letta-mobile-bglj6.13), calling whatever the host passed last; null when it passes none. */
private fun forwardedShowOnCanvas(current: State<ChatSurfaceHost>): ((CanvasArtifactReceipt) -> Unit)? {
    return forwardIfSet<(CanvasArtifactReceipt) -> Unit>(current.value.showOnCanvas) { { receipt -> current.value.showOnCanvas?.invoke(receipt) } }
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
