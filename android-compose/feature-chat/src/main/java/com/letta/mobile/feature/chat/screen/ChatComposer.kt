package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.letta.mobile.feature.chat.R
import androidx.hilt.navigation.compose.hiltViewModel
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.SlashCommand
import com.letta.mobile.data.model.Tool
import com.letta.mobile.ui.components.LettaInputBar
import com.letta.mobile.ui.components.ToolAffordanceRow
import com.letta.mobile.ui.components.rememberFloatingControlPressScale
import com.letta.mobile.ui.components.rememberReducedMotionEnabled
import com.letta.mobile.ui.components.audio.HoldToDictateButton
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.image.decodeImageBitmap
import com.letta.mobile.feature.chat.voice.VoiceInputUiState
import com.letta.mobile.feature.chat.voice.VoiceInputViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import com.letta.mobile.ui.theme.LettaSpacing
import com.letta.mobile.ui.chat.render.buildToolCallTemplate

// letta-mobile-awbf.1: composer sizing now references the design system tokens
internal val ChatComposerAttachButtonSize = LettaSpacing.COMPOSER_ATTACH_BUTTON_SIZE
private val ChatComposerActionTargetSize = 48.dp
private val ChatComposerAttachIconSize = LettaSpacing.COMPOSER_ATTACH_ICON_SIZE
private val ChatComposerInputHorizontalPadding = LettaSpacing.SM
private val ChatComposerInputVerticalPadding = LettaSpacing.XS
private val ChatComposerInputItemSpacing = LettaSpacing.XS

internal object ChatComposerTestTags {
    const val ATTACHMENT_THUMBNAIL = "chat-composer-attachment-thumbnail"
    const val ATTACHMENT_THUMBNAIL_IMAGE = "chat-composer-attachment-thumbnail-image"
    const val ATTACHMENT_THUMBNAIL_PLACEHOLDER = "chat-composer-attachment-thumbnail-placeholder"
    const val ATTACHMENT_THUMBNAIL_REMOVE_BUTTON = "chat-composer-attachment-thumbnail-remove"
    const val ATTACHMENT_PREVIEW_DIALOG = "chat-composer-attachment-preview-dialog"
    const val ATTACHMENT_PREVIEW_IMAGE = "chat-composer-attachment-preview-image"
}

private data class ChatComposerUiModel(
    val inputText: String,
    val pendingAttachments: ImmutableList<MessageContentPart.Image>,
    val isStreaming: Boolean,
    val canSendMessages: Boolean,
    val slashCommands: ImmutableList<SlashCommand>,
    val availableTools: List<Tool>,
)

private data class ChatComposerCallbacks(
    val onTextChange: (String) -> Unit,
    val onSend: (String) -> Unit,
    val onStop: () -> Unit,
    val onRemoveAttachment: (Int) -> Unit,
    val onAttachImage: () -> Unit,
    val onSlashCommandSelected: (SlashCommand) -> Unit,
    val onSlashCommandUninstall: (SlashCommand) -> Unit,
)

private data class ChatComposerVoice(
    val viewModel: VoiceInputViewModel?,
    val state: VoiceInputUiState,
    val enabled: Boolean,
)

private data class ChatComposerInputState(
    val model: ChatComposerUiModel,
    val voice: ChatComposerVoice,
    val showAction: Boolean,
)

/**
 * The chat input composer: text bar + staged attachment thumbnails + attach
 * button. Extracted from [ChatScreen] to keep the rendering + wiring layer
 * focused and to make the composer independently testable.
 */
@Composable
internal fun ChatComposer(
    inputText: String,
    pendingAttachments: ImmutableList<MessageContentPart.Image>,
    isStreaming: Boolean,
    canSendMessages: Boolean,
    onTextChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onAttachImage: () -> Unit,
    modifier: Modifier = Modifier,
    slashCommands: ImmutableList<SlashCommand> = kotlinx.collections.immutable.persistentListOf(),
    onSlashCommandSelected: (SlashCommand) -> Unit = {},
    onSlashCommandUninstall: (SlashCommand) -> Unit = {},
    availableTools: List<Tool> = emptyList(),
) {
    val model = ChatComposerUiModel(
        inputText = inputText,
        pendingAttachments = pendingAttachments,
        isStreaming = isStreaming,
        canSendMessages = canSendMessages,
        slashCommands = slashCommands,
        availableTools = availableTools,
    )
    val callbacks = ChatComposerCallbacks(
        onTextChange = onTextChange,
        onSend = onSend,
        onStop = onStop,
        onRemoveAttachment = onRemoveAttachment,
        onAttachImage = onAttachImage,
        onSlashCommandSelected = onSlashCommandSelected,
        onSlashCommandUninstall = onSlashCommandUninstall,
    )
    var previewAttachment by remember { mutableStateOf<MessageContentPart.Image?>(null) }
    var showComposerActions by remember { mutableStateOf(false) }
    val onToolSelected: (Tool) -> Unit = { tool ->
        callbacks.onTextChange(appendToolCallTemplate(model.inputText, buildToolCallTemplate(tool)))
    }

    // letta-mobile-xtwt: defer to the IME's own Send action while the soft
    // keyboard is open and there's nothing in flight. The composer's trailing
    // button is redundant in that state — Enter on the keyboard already
    // submits via KeyboardActions.onSend in LettaInputBar. We keep the
    // button visible while streaming so the morphed Stop affordance stays
    // reachable even with the keyboard up.
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val showAction = isStreaming || !keyboardOpen

    // letta-mobile-rl0d (audio): swap the Send/Stop button for a
    // HoldToDictateButton when the field is empty. Voice path stays
    // hidden while the user is typing or a stream is in flight.
    // The shader overlay (letta-mobile-arhd: VoiceRecognizerOverlay)
    // is mounted at ChatScreen level — not here — so it can fill the
    // screen with a dark scrim instead of sitting as a strip above
    // this composer.
    //
    // Resolve the voice VM only when the hosting Activity is actually
    // Hilt-managed. Compose previews and AgentScaffoldHiltTest host
    // ChatComposer on a plain ComponentActivity (createComposeRule()
    // uses ComponentActivity, not HiltTestActivity), so hiltViewModel()
    // would throw IllegalStateException. In those contexts we silently
    // skip the voice affordance — production always has the Hilt host.
    val voice = rememberChatComposerVoice(model)

    Column(modifier = modifier.fillMaxWidth()) {
        // letta-mobile-ihuz: tool-affordance chips above the input when the
        // composer is empty AND the active agent has tools. Hides as soon as
        // the user starts typing — gated by composable visibility (no flicker).
        ChatComposerContextRows(
            model = model,
            callbacks = callbacks,
            onToolSelected = onToolSelected,
            onPreviewAttachment = { previewAttachment = it },
        )

        ChatComposerInput(
            state = ChatComposerInputState(
                model = model,
                voice = voice,
                showAction = showAction,
            ),
            callbacks = callbacks,
            onOpenActions = { showComposerActions = true },
        )
    }

    ChatComposerActionSheet(
        state = ChatComposerActionSheetState(
            show = showComposerActions,
            availableTools = model.availableTools,
        ),
        callbacks = ChatComposerActionSheetCallbacks(
            onDismiss = { showComposerActions = false },
            onAttachImage = {
                showComposerActions = false
                callbacks.onAttachImage()
            },
            onToolSelected = { tool ->
                showComposerActions = false
                onToolSelected(tool)
            },
        ),
    )

    previewAttachment?.let { image ->
        AttachmentPreviewDialog(
            image = image,
            onDismiss = { previewAttachment = null },
        )
    }
}

@Composable
private fun rememberChatComposerVoice(model: ChatComposerUiModel): ChatComposerVoice {
    val activity = LocalContext.current as? android.app.Activity
    val isHiltHost = activity is dagger.hilt.internal.GeneratedComponentManager<*>
    val viewModel: VoiceInputViewModel? = if (isHiltHost) hiltViewModel() else null
    val state by (viewModel?.uiState ?: remember { MutableStateFlow(VoiceInputUiState()) })
        .collectAsState()
    val hasSendableContent = model.inputText.isNotBlank() || model.pendingAttachments.isNotEmpty()
    return ChatComposerVoice(
        viewModel = viewModel,
        state = state,
        enabled = viewModel != null &&
            !model.isStreaming &&
            model.canSendMessages &&
            !hasSendableContent,
    )
}

@Composable
private fun ChatComposerContextRows(
    model: ChatComposerUiModel,
    callbacks: ChatComposerCallbacks,
    onToolSelected: (Tool) -> Unit,
    onPreviewAttachment: (MessageContentPart.Image) -> Unit,
) {
    if (model.inputText.isBlank() &&
        model.pendingAttachments.isEmpty() &&
        model.availableTools.isNotEmpty()
    ) {
        ToolAffordanceRow(
            tools = model.availableTools,
            onToolSelected = onToolSelected,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }

    val slashCommands = matchingSlashCommands(model.inputText, model.slashCommands)
    if (slashCommands.isNotEmpty()) {
        SlashCommandSuggestionRow(
            commands = slashCommands,
            onSelected = callbacks.onSlashCommandSelected,
            onUninstall = callbacks.onSlashCommandUninstall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }

    if (model.pendingAttachments.isNotEmpty()) {
        AttachmentStrip(
            attachments = model.pendingAttachments,
            onRemove = callbacks.onRemoveAttachment,
            onPreview = onPreviewAttachment,
        )
    }
}

private fun matchingSlashCommands(
    inputText: String,
    slashCommands: List<SlashCommand>,
): List<SlashCommand> {
    val query = inputText.trimStart()
    return if (query.startsWith("/")) {
        slashCommands.filter { it.command.startsWith(query) }.take(8)
    } else {
        emptyList()
    }
}

@Composable
private fun ChatComposerInput(
    state: ChatComposerInputState,
    callbacks: ChatComposerCallbacks,
    onOpenActions: () -> Unit,
) {
    val model = state.model
    val hasSendableContent = model.inputText.isNotBlank() || model.pendingAttachments.isNotEmpty()
    val canSend = !model.isStreaming && model.canSendMessages && hasSendableContent
    LettaInputBar(
        text = model.inputText,
        onTextChange = callbacks.onTextChange,
        placeholder = stringResource(R.string.screen_chat_input_hint),
        sendContentDescription = stringResource(R.string.action_send_message),
        enabled = model.canSendMessages,
        canSendOverride = if (model.isStreaming) true else canSend,
        actionIcon = if (model.isStreaming) LettaIcons.Close else LettaIcons.Send,
        actionContentDescription = if (model.isStreaming) {
            stringResource(R.string.action_stop_run)
        } else {
            stringResource(R.string.action_send_message)
        },
        actionContainerColor = if (model.isStreaming) MaterialTheme.colorScheme.errorContainer else null,
        actionContentColor = if (model.isStreaming) MaterialTheme.colorScheme.onErrorContainer else null,
        actionSizeFraction = if (model.isStreaming) 0.7f else 1f,
        actionPulse = model.isStreaming,
        actionVisible = state.showAction || state.voice.enabled,
        hasStagedContent = model.pendingAttachments.isNotEmpty(),
        customTrailingContent = voiceTrailingContent(model, callbacks, state.voice),
        contentPadding = PaddingValues(
            horizontal = ChatComposerInputHorizontalPadding,
            vertical = ChatComposerInputVerticalPadding,
        ),
        itemSpacing = ChatComposerInputItemSpacing,
        leadingContent = {
            ChatComposerAddButton(
                hasTools = model.availableTools.isNotEmpty(),
                onAttachImage = callbacks.onAttachImage,
                onOpenActions = onOpenActions,
            )
        },
        onSend = { text ->
            if (model.isStreaming) callbacks.onStop() else callbacks.onSend(text)
        },
    )
}

private fun voiceTrailingContent(
    model: ChatComposerUiModel,
    callbacks: ChatComposerCallbacks,
    voice: ChatComposerVoice,
): (@Composable () -> Unit)? {
    val viewModel = voice.viewModel ?: return null
    if (!voice.enabled) return null
    return {
        HoldToDictateButton(
            isRecognizing = voice.state.recognizing,
            onStart = {
                viewModel.startSpeechRecognition { dictated ->
                    if (dictated.isNotBlank()) {
                        val merged = if (model.inputText.isBlank()) dictated else "${model.inputText} $dictated"
                        callbacks.onTextChange(merged)
                    }
                }
            },
            onStop = viewModel::stopSpeechRecognition,
            onCancel = viewModel::cancelSpeechRecognition,
        )
    }
}

@Composable
private fun ChatComposerAddButton(
    hasTools: Boolean,
    onAttachImage: () -> Unit,
    onOpenActions: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale = rememberFloatingControlPressScale(
        interactionSource = interactionSource,
        reducedMotion = rememberReducedMotionEnabled(),
    )
    Box(
        modifier = Modifier
            .size(ChatComposerActionTargetSize)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = {
                    HapticEffects.contextClick(haptic, view)
                    if (hasTools) onOpenActions() else onAttachImage()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .size(ChatComposerAttachButtonSize)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    LettaIcons.Add,
                    contentDescription = stringResource(R.string.composer_actions_open),
                    modifier = Modifier.size(ChatComposerAttachIconSize),
                )
            }
        }
    }
}

internal fun appendToolCallTemplate(
    draft: String,
    template: String,
): String = when {
    draft.isBlank() -> template
    draft.last().isWhitespace() -> draft + template
    else -> "$draft $template"
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SlashCommandSuggestionRow(
    commands: List<SlashCommand>,
    onSelected: (SlashCommand) -> Unit,
    onUninstall: (SlashCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(items = commands, key = { it.command }) { command ->
            var menuOpen by remember(command.command) { mutableStateOf(false) }
            // Use a Surface + combinedClickable instead of Material3 InputChip:
            // InputChip owns its own onClick gesture, which swallowed the
            // combinedClickable modifier so tap/long-press never fired
            // (regression). A plain clickable Surface handles both reliably.
            val containerColor = if (command.installed) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
            val contentColor = if (command.installed) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Box {
                Surface(
                    modifier = Modifier.combinedClickable(
                        onClick = { onSelected(command) },
                        onLongClick = { if (command.installed) menuOpen = true },
                    ),
                    shape = RoundedCornerShape(8.dp),
                    color = containerColor,
                    contentColor = contentColor,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = if (command.installed) LettaIcons.Check else LettaIcons.Add,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        androidx.compose.material3.Text(
                            text = command.command,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { androidx.compose.material3.Text(stringResource(R.string.chat_slash_uninstall_label, command.command)) },
                        onClick = {
                            try {
                                onUninstall(command)
                            } finally {
                                menuOpen = false
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentStrip(
    attachments: ImmutableList<MessageContentPart.Image>,
    onRemove: (Int) -> Unit,
    onPreview: (MessageContentPart.Image) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(
            items = attachments,
            key = { index, img -> "$index-${img.base64.hashCode()}" },
        ) { index, img ->
            AttachmentThumbnail(
                image = img,
                onPreview = { onPreview(img) },
                onRemove = {
                    HapticEffects.segmentTick(haptic, view)
                    onRemove(index)
                },
            )
        }
    }
}

@Composable
private fun AttachmentThumbnail(
    image: MessageContentPart.Image,
    onPreview: () -> Unit,
    onRemove: () -> Unit,
) {
    // letta-mobile-v4f9: the axb2 ByteArray fix regressed under Coil 3.4 —
    // the BitmapFetcher returns null for `data(byteArray)` in this build,
    // so AsyncImage paints an empty square. The composer attachment is
    // already in memory as base64; decode straight to a Bitmap and use
    // Compose's native Image. No Coil round-trip, no async state to
    // mis-resolve, identical caching scope (the parent composition holds
    // the bitmap via remember keyed on the base64 string).
    val imageBitmap = rememberAttachmentImageBitmap(image.base64)

    Box(modifier = Modifier.size(64.dp)) {
        Surface(
            onClick = onPreview,
            modifier = Modifier
                .size(64.dp)
                .testTag(ChatComposerTestTags.ATTACHMENT_THUMBNAIL),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = stringResource(R.string.action_preview_attachment),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(ChatComposerTestTags.ATTACHMENT_THUMBNAIL_IMAGE),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(ChatComposerTestTags.ATTACHMENT_THUMBNAIL_PLACEHOLDER),
                )
            }
        }
        // Remove button overlay (top-right)
        Surface(
            modifier = Modifier
                .size(20.dp)
                .align(Alignment.TopEnd)
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.errorContainer,
            shape = CircleShape,
        ) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .size(20.dp)
                    .testTag(ChatComposerTestTags.ATTACHMENT_THUMBNAIL_REMOVE_BUTTON),
            ) {
                Icon(
                    LettaIcons.Close,
                    contentDescription = stringResource(R.string.action_remove_attachment),
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@Composable
private fun AttachmentPreviewDialog(
    image: MessageContentPart.Image,
    onDismiss: () -> Unit,
) {
    val imageBitmap = rememberAttachmentImageBitmap(image.base64)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.72f))
                .padding(24.dp)
                .testTag(ChatComposerTestTags.ATTACHMENT_PREVIEW_DIALOG),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                if (imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = stringResource(R.string.action_preview_attachment),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(ChatComposerTestTags.ATTACHMENT_PREVIEW_IMAGE),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize())
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Icon(
                    imageVector = LettaIcons.Close,
                    contentDescription = stringResource(R.string.action_close),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun rememberAttachmentImageBitmap(base64: String) = remember(base64) {
    runCatching {
        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        decodeImageBitmap(bytes)
    }.getOrNull()
}
