package com.letta.mobile.ui.screens.chat

import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
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
        val snackbarHostState = remember { SnackbarHostState() }
        val density = LocalDensity.current
        val windowSizeClass = LocalWindowSizeClass.current
        val imeBottomPx = WindowInsets.ime.getBottom(density)
        val navBottomPx = WindowInsets.navigationBars.getBottom(density)
        val bottomBarPx = if (windowSizeClass.isExpandedWidth) 0 else with(density) { 56.dp.roundToPx() }
        val bottomInsetDp = with(density) { max(imeBottomPx, navBottomPx + bottomBarPx).toDp() }

        LaunchedEffect(composerState.error) {
            val message = composerState.error ?: return@LaunchedEffect
            snackbarHostState.showSnackbar(message)
            viewModel.clearComposerError()
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
                        // letta-mobile: empty-state parity between
                        // "New Conversation" entry points. Both the chat-
                        // list FAB (eager server-side create -> lands in
                        // ConversationState.Ready with empty messages) and
                        // the in-chat switcher's "New Conversation" button
                        // (lazy-create on first send -> lands in
                        // ConversationState.NoConversation) should show the
                        // same starter prompts. AdminChatViewModel.sendMessage
                        // already lazy-creates the conversation server-side
                        // when convId is null (fresh-route branch), so
                        // tapping a chip here transparently creates the
                        // Letta conversation and sends the prompt.
                        com.letta.mobile.ui.components.StarterPrompts(
                            onPromptClick = { prompt -> viewModel.sendMessage(prompt) },
                            modifier = Modifier.weight(1f),
                        )
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
                                onLoadOlderMessages = { viewModel.loadOlderMessages() },
                                onSubmitApproval = { requestId, toolCallIds, approve, reason ->
                                    viewModel.submitApproval(requestId, toolCallIds, approve, reason)
                                },
                                onToggleRunCollapsed = viewModel::toggleRunCollapsed,
                                onToggleReasoningExpanded = viewModel::toggleReasoningExpanded,
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
                    onRemoveAttachment = { viewModel.removeAttachment(it) },
                    onAttachImage = launchPicker,
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun ChatContent(
    state: ChatUiState,
    scrollToMessageId: String? = null,
    onSendMessage: (String) -> Unit,
    onLoadOlderMessages: () -> Unit,
    onSubmitApproval: (String, List<String>, Boolean, String?) -> Unit,
    onToggleRunCollapsed: (String) -> Unit,
    onToggleReasoningExpanded: (String) -> Unit,
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("simple", "interactive", "debug").forEach { mode ->
                FilterChip(
                    selected = chatMode == mode,
                    onClick = { chatMode = mode },
                    label = { Text(mode.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall) },
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
                onSubmitApproval = onSubmitApproval,
                onToggleRunCollapsed = onToggleRunCollapsed,
                onToggleReasoningExpanded = onToggleReasoningExpanded,
                modifier = Modifier.weight(1f),
            )
        }

    }
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
