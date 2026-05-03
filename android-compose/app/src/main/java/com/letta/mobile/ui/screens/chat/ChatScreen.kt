package com.letta.mobile.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.ui.components.FloatingBanner
import com.letta.mobile.ui.components.MessageSkeletonList
import com.letta.mobile.ui.components.StarterPrompts
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.ChatBackground
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.LocalWindowSizeClass
import com.letta.mobile.ui.theme.isExpandedWidth
import kotlin.math.max

@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    chatBackground: ChatBackground = ChatBackground.Default,
    onBugCommand: (() -> Unit)? = null,
    viewModel: AdminChatViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val composerState by viewModel.composerState.collectAsStateWithLifecycle()
    val fontScale by viewModel.chatFontScale.collectAsStateWithLifecycle()

    var activeFontScale by remember { mutableFloatStateOf(fontScale) }
    LaunchedEffect(fontScale) { activeFontScale = fontScale }

    // Timeline sync loop handles live updates — no on-resume refresh needed.

    val backgroundModifier = when (chatBackground) {
        is ChatBackground.Default -> Modifier
        is ChatBackground.SolidColor -> Modifier.background(chatBackground.color)
        is ChatBackground.Gradient -> Modifier.background(chatBackground.toBrush())
    }

    LettaChatTheme(fontScale = activeFontScale) {
        var floatingBannerMessage by remember { mutableStateOf("") }
        val density = LocalDensity.current
        val windowSizeClass = LocalWindowSizeClass.current
        val imeBottomPx = WindowInsets.ime.getBottom(density)
        val navBottomPx = WindowInsets.navigationBars.getBottom(density)
        val bottomBarPx = if (windowSizeClass.isExpandedWidth) 0 else with(density) { 56.dp.roundToPx() }
        val bottomInsetDp = with(density) { max(imeBottomPx, navBottomPx + bottomBarPx).toDp() }

        LaunchedEffect(composerState.error) {
            val message = composerState.error ?: return@LaunchedEffect
            floatingBannerMessage = message
            viewModel.clearComposerError()
        }

        LaunchedEffect(floatingBannerMessage) {
            if (floatingBannerMessage.isNotBlank()) {
                kotlinx.coroutines.delay(2600)
                floatingBannerMessage = ""
            }
        }

        Box(
            modifier = modifier
                .fillMaxSize()
                .then(backgroundModifier)
                .padding(bottom = bottomInsetDp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // letta-mobile-c87t: surfaces a non-modal banner when the
                // lettabot WS gateway substituted a fresh conversation for the
                // one we asked to resume. See ClientModeConversationSwapBanner.
                val swap = state.clientModeConversationSwap
                com.letta.mobile.ui.components.ClientModeConversationSwapBanner(
                    visible = swap != null,
                    onDismiss = { viewModel.dismissClientModeConversationSwap() },
                    requestedConversationIdSuffix = swap?.requestedConversationId?.takeLast(6),
                    newConversationIdSuffix = swap?.newConversationId?.takeLast(6),
                )
                when (val conversationState = state.conversationState) {
                    ConversationState.Loading -> {
                        MessageSkeletonList(modifier = Modifier.weight(1f))
                    }
                    is ConversationState.Error -> {
                        ErrorContent(
                            message = conversationState.message,
                            onRetry = { viewModel.retryConversationLoad() },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    ConversationState.NoConversation -> {
                        // letta-mobile-qkct: a fresh Client Mode send remains
                        // in NoConversation until the gateway returns the
                        // newly-created conversation id. During that pending
                        // window the VM already owns optimistic messages and
                        // streaming flags; render the chat body instead of the
                        // empty starter prompts so the user's bubble is visible
                        // immediately.
                        if (shouldShowStarterPromptsForNoConversation(state)) {
                            StarterPrompts(
                                onPromptClick = { prompt -> viewModel.sendMessage(prompt) },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            ChatContent(
                                state = state,
                                scrollToMessageId = viewModel.scrollToMessageId,
                                onSendMessage = { viewModel.sendMessage(it) },
                                onRerunMessage = { viewModel.rerunMessage(it) },
                                onLoadOlderMessages = { viewModel.loadOlderMessages() },
                                onSubmitApproval = { requestId, toolCallIds, approve, reason ->
                                    viewModel.submitApproval(requestId, toolCallIds, approve, reason)
                                },
                                onToggleRunCollapsed = viewModel::toggleRunCollapsed,
                                onToggleReasoningExpanded = viewModel::toggleReasoningExpanded,
                                onOpenLocationPicker = viewModel::openClientModeLocationPicker,
                                activeFontScale = activeFontScale,
                                onActiveFontScaleChange = { activeFontScale = it },
                                onFontScaleChange = { viewModel.setChatFontScale(it) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    is ConversationState.Ready -> {
                        if (state.isLoadingMessages && state.messages.isEmpty()) {
                            MessageSkeletonList(modifier = Modifier.weight(1f))
                        } else if (state.error != null && state.messages.isEmpty()) {
                            ErrorContent(
                                message = state.error!!,
                                onRetry = { viewModel.loadMessages() },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            ChatContent(
                                state = state,
                                scrollToMessageId = viewModel.scrollToMessageId,
                                onSendMessage = { viewModel.sendMessage(it) },
                                onRerunMessage = { viewModel.rerunMessage(it) },
                                onLoadOlderMessages = { viewModel.loadOlderMessages() },
                                onSubmitApproval = { requestId, toolCallIds, approve, reason ->
                                    viewModel.submitApproval(requestId, toolCallIds, approve, reason)
                                },
                                onToggleRunCollapsed = viewModel::toggleRunCollapsed,
                                onToggleReasoningExpanded = viewModel::toggleReasoningExpanded,
                                onOpenLocationPicker = viewModel::openClientModeLocationPicker,
                                activeFontScale = activeFontScale,
                                onActiveFontScaleChange = { activeFontScale = it },
                                onFontScaleChange = { viewModel.setChatFontScale(it) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                val launchPicker = rememberImageAttachmentPicker(
                    onPicked = { viewModel.addAttachment(it) },
                    onError = { viewModel.reportComposerError(it) },
                )
                ChatComposer(
                    inputText = composerState.inputText,
                    pendingAttachments = composerState.pendingAttachments,
                    inputHistory = composerState.inputHistory,
                    isStreaming = state.isStreaming,
                    canSendMessages = viewModel.canSendMessages,
                    onTextChange = { newText ->
                        if (viewModel.handleComposerTextChanged(newText) == ChatComposerEffect.OpenBugReport) {
                            onBugCommand?.invoke()
                        }
                    },
                    onSend = {
                        if (viewModel.submitComposer(it) == ChatComposerEffect.OpenBugReport) {
                            onBugCommand?.invoke()
                        }
                    },
                    onStop = { viewModel.interruptRun() },
                    onRemoveAttachment = { viewModel.removeAttachment(it) },
                    onAttachImage = launchPicker,
                )
            }

            FloatingBanner(
                visible = floatingBannerMessage.isNotBlank(),
                text = floatingBannerMessage,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
            )
        }

        if (state.clientModeFilesystemPicker.isVisible) {
            ClientModeFilesystemPickerSheet(
                state = state.clientModeFilesystemPicker,
                onDismiss = viewModel::closeClientModeLocationPicker,
                onNavigateTo = viewModel::browseClientModeLocation,
                onSelect = viewModel::selectClientModeLocation,
            )
        }
    }
}

internal fun shouldShowStarterPromptsForNoConversation(state: ChatUiState): Boolean =
    state.messages.isEmpty() && !state.isStreaming

@Composable
private fun ChatContent(
    state: ChatUiState,
    scrollToMessageId: String? = null,
    onSendMessage: (String) -> Unit,
    onRerunMessage: (com.letta.mobile.data.model.UiMessage) -> Unit,
    onLoadOlderMessages: () -> Unit,
    onSubmitApproval: (String, List<String>, Boolean, String?) -> Unit,
    onToggleRunCollapsed: (String) -> Unit,
    onToggleReasoningExpanded: (String) -> Unit,
    onOpenLocationPicker: () -> Unit,
    activeFontScale: Float = 1f,
    onActiveFontScaleChange: (Float) -> Unit = {},
    onFontScaleChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var chatMode by remember { mutableStateOf("interactive") }

    val renderModel = remember(state.messages, chatMode) {
        buildChatRenderModel(
            messages = state.messages,
            mode = chatMode.toChatDisplayMode(),
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("simple", "interactive", "debug").forEach { mode ->
                FilterChip(
                    selected = chatMode == mode,
                    onClick = { chatMode = mode },
                    label = { Text(mode.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall) },
                )
            }
            if (state.isClientModeEnabled) {
                AssistChip(
                    onClick = onOpenLocationPicker,
                    leadingIcon = { Icon(LettaIcons.Storage, contentDescription = null) },
                    label = {
                        Text(
                            text = state.clientModeLocation.displayLabel()
                                ?: stringResource(R.string.screen_chat_client_location_title),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }

        if (state.messages.isEmpty() && !state.isStreaming) {
            StarterPrompts(
                onPromptClick = onSendMessage,
                modifier = Modifier.weight(1f),
            )
        } else {
            val renderItems = renderModel.renderItems

            ChatMessageList(
                state = state,
                renderItems = renderItems,
                chatMode = chatMode,
                scrollToMessageId = scrollToMessageId,
                activeFontScale = activeFontScale,
                onActiveFontScaleChange = onActiveFontScaleChange,
                onFontScaleChange = onFontScaleChange,
                onLoadOlderMessages = onLoadOlderMessages,
                onSendMessage = onSendMessage,
                onRerunMessage = onRerunMessage,
                onSubmitApproval = onSubmitApproval,
                onToggleRunCollapsed = onToggleRunCollapsed,
                onToggleReasoningExpanded = onToggleReasoningExpanded,
                modifier = Modifier.weight(1f),
            )
        }

    }
}

@Composable
private fun ClientModeFilesystemPickerSheet(
    state: ClientModeFilesystemPickerUiState,
    onDismiss: () -> Unit,
    onNavigateTo: (String?) -> Unit,
    onSelect: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.screen_chat_client_location_picker_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = state.path ?: stringResource(R.string.screen_chat_client_location_unknown_label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { state.parent?.let(onNavigateTo) },
                    enabled = state.parent != null && !state.isLoading,
                ) {
                    Icon(LettaIcons.ArrowBack, contentDescription = null)
                    Text(stringResource(R.string.screen_chat_client_location_picker_parent))
                }
                TextButton(
                    onClick = { state.path?.let(onSelect) },
                    enabled = state.path != null && !state.isLoading,
                ) {
                    Icon(LettaIcons.Check, contentDescription = null)
                    Text(stringResource(R.string.screen_chat_client_location_picker_select))
                }
            }

            state.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (state.isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (state.entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.screen_chat_client_location_picker_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                    items(state.entries, key = { it.path }) { entry ->
                        ListItem(
                            headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(entry.path, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            leadingContent = { Icon(LettaIcons.Storage, contentDescription = null) },
                            trailingContent = { Icon(LettaIcons.ChevronRight, contentDescription = null) },
                            modifier = Modifier.clickable(enabled = !state.isLoading) { onNavigateTo(entry.path) },
                        )
                        HorizontalDivider()
                    }
                }
            }

            if (state.truncated) {
                Text(
                    text = stringResource(R.string.screen_chat_client_location_picker_truncated),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun ClientModeLocationUiState.displayLabel(): String? {
    val path = currentPath ?: lastRequestedPath ?: defaultPath ?: return null
    return path.trimEnd('/').substringAfterLast('/').ifBlank { path }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = LettaIcons.Error,
            contentDescription = "Error",
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = message, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

// NoConversationContent (the prior placeholder for ConversationState.
// NoConversation showing only "Start a conversation / Send a message to
// create a new conversation.") was removed when the empty-state for the
// in-chat "New Conversation" path was unified with the chat-list FAB
// path — both now render StarterPrompts. The strings
// screen_chat_empty_title and screen_chat_empty_subtitle remain in
// res/values/strings.xml in case a future surface needs them.
