package com.letta.mobile.feature.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.letta.mobile.feature.chat.R
import androidx.hilt.navigation.compose.hiltViewModel
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.Tool
import com.letta.mobile.ui.components.LettaInputBar
import com.letta.mobile.ui.components.ToolAffordanceRow
import com.letta.mobile.ui.components.audio.HoldToDictateButton
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.feature.chat.voice.VoiceInputUiState
import com.letta.mobile.feature.chat.voice.VoiceInputViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.MutableStateFlow

internal val ChatComposerAttachButtonSize = 36.dp
private val ChatComposerAttachIconSize = 18.dp
private val ChatComposerInputHorizontalPadding = 8.dp
private val ChatComposerInputVerticalPadding = 6.dp
private val ChatComposerInputItemSpacing = 6.dp

internal object ChatComposerTestTags {
    const val AttachmentThumbnail = "chat-composer-attachment-thumbnail"
    const val AttachmentThumbnailImage = "chat-composer-attachment-thumbnail-image"
    const val AttachmentThumbnailPlaceholder = "chat-composer-attachment-thumbnail-placeholder"
    const val AttachmentThumbnailRemoveButton = "chat-composer-attachment-thumbnail-remove"
    const val AttachmentPreviewDialog = "chat-composer-attachment-preview-dialog"
    const val AttachmentPreviewImage = "chat-composer-attachment-preview-image"
}

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
    availableTools: List<Tool> = emptyList(),
) {
    val hasSendableContent = inputText.isNotBlank() || pendingAttachments.isNotEmpty()
    val canSend = !isStreaming && canSendMessages && hasSendableContent
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    var previewAttachment by remember { mutableStateOf<MessageContentPart.Image?>(null) }

    // letta-mobile-xtwt: defer to the IME's own Send action while the soft
    // keyboard is open and there's nothing in flight. The composer's trailing
    // button is redundant in that state — Enter on the keyboard already
    // submits via KeyboardActions.onSend in LettaInputBar. We keep the
    // button visible while streaming so the morphed Stop affordance stays
    // reachable even with the keyboard up.
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val showAction = shouldShowChatComposerAction(
        isStreaming = isStreaming,
        keyboardOpen = keyboardOpen,
        hasAttachments = pendingAttachments.isNotEmpty(),
    )

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
    val activity = LocalContext.current as? android.app.Activity
    val isHiltHost = activity is dagger.hilt.internal.GeneratedComponentManager<*>
    val voiceVm: VoiceInputViewModel? = if (isHiltHost) hiltViewModel() else null
    val voiceState by (voiceVm?.uiState ?: remember { MutableStateFlow(VoiceInputUiState()) })
        .collectAsState()
    val useVoice = voiceVm != null && !isStreaming && canSendMessages && !hasSendableContent

    Column(modifier = modifier.fillMaxWidth()) {
        // letta-mobile-ihuz: tool-affordance chips above the input when the
        // composer is empty AND the active agent has tools. Hides as soon as
        // the user starts typing — gated by composable visibility (no flicker).
        if (inputText.isBlank() && pendingAttachments.isEmpty() && availableTools.isNotEmpty()) {
            ToolAffordanceRow(
                tools = availableTools,
                onToolSelected = { tool -> onTextChange(buildToolCallTemplate(tool)) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        if (pendingAttachments.isNotEmpty()) {
            AttachmentStrip(
                attachments = pendingAttachments,
                onRemove = onRemoveAttachment,
                onPreview = { previewAttachment = it },
            )
        }

        LettaInputBar(
            text = inputText,
            onTextChange = onTextChange,
            placeholder = stringResource(R.string.screen_chat_input_hint),
            sendContentDescription = stringResource(R.string.action_send_message),
            enabled = canSendMessages,
            canSendOverride = if (isStreaming) true else canSend,
            actionIcon = if (isStreaming) LettaIcons.Close else LettaIcons.Send,
            actionContentDescription = if (isStreaming) {
                stringResource(R.string.action_stop_run)
            } else {
                stringResource(R.string.action_send_message)
            },
            actionContainerColor = if (isStreaming) MaterialTheme.colorScheme.errorContainer else null,
            actionContentColor = if (isStreaming) MaterialTheme.colorScheme.onErrorContainer else null,
            actionSizeFraction = if (isStreaming) 0.7f else 1f,
            actionPulse = isStreaming,
            actionVisible = showAction || useVoice,
            customTrailingContent = if (useVoice && voiceVm != null) {
                {
                    HoldToDictateButton(
                        isRecognizing = voiceState.recognizing,
                        onStart = {
                            voiceVm.startSpeechRecognition(
                                onDone = { dictated ->
                                    if (dictated.isNotBlank()) {
                                        val merged = if (inputText.isBlank()) {
                                            dictated
                                        } else {
                                            "$inputText $dictated"
                                        }
                                        onTextChange(merged)
                                    }
                                },
                            )
                        },
                        onStop = voiceVm::stopSpeechRecognition,
                        onCancel = voiceVm::cancelSpeechRecognition,
                    )
                }
            } else null,
            contentPadding = PaddingValues(
                horizontal = ChatComposerInputHorizontalPadding,
                vertical = ChatComposerInputVerticalPadding,
            ),
            itemSpacing = ChatComposerInputItemSpacing,
            leadingContent = {
                Surface(
                    modifier = Modifier
                        .size(ChatComposerAttachButtonSize)
                        .clickable {
                            HapticEffects.contextClick(haptic, view)
                            onAttachImage()
                        },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            LettaIcons.Add,
                            contentDescription = stringResource(R.string.action_attach_image),
                            modifier = Modifier.size(ChatComposerAttachIconSize),
                        )
                    }
                }
            },
            onSend = { text ->
                if (isStreaming) {
                    onStop()
                } else {
                    onSend(text)
                }
            },
        )
    }

    previewAttachment?.let { image ->
        AttachmentPreviewDialog(
            image = image,
            onDismiss = { previewAttachment = null },
        )
    }
}

internal fun shouldShowChatComposerAction(
    isStreaming: Boolean,
    keyboardOpen: Boolean,
    hasAttachments: Boolean,
): Boolean = isStreaming || !keyboardOpen || hasAttachments

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
            modifier = Modifier
                .size(64.dp)
                .testTag(ChatComposerTestTags.AttachmentThumbnail)
                .clickable(onClick = onPreview),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = stringResource(R.string.action_preview_attachment),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(ChatComposerTestTags.AttachmentThumbnailImage),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(ChatComposerTestTags.AttachmentThumbnailPlaceholder),
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
                    .testTag(ChatComposerTestTags.AttachmentThumbnailRemoveButton),
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
                .testTag(ChatComposerTestTags.AttachmentPreviewDialog),
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
                            .testTag(ChatComposerTestTags.AttachmentPreviewImage),
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
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}
