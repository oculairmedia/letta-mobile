package com.letta.mobile.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.letta.mobile.feature.chat.R
import androidx.hilt.navigation.compose.hiltViewModel
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.ui.components.LettaInputBar
import com.letta.mobile.ui.components.audio.HoldToDictateButton
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

/**
 * The chat input composer: text bar + staged attachment thumbnails + attach
 * button. Extracted from [ChatScreen] to keep the rendering + wiring layer
 * focused and to make the composer independently testable.
 */
@Composable
fun ChatComposer(
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
) {
    val hasSendableContent = inputText.isNotBlank() || pendingAttachments.isNotEmpty()
    val canSend = !isStreaming && canSendMessages && hasSendableContent

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
    val activity = LocalContext.current as? android.app.Activity
    val isHiltHost = activity is dagger.hilt.internal.GeneratedComponentManager<*>
    val voiceVm: VoiceInputViewModel? = if (isHiltHost) hiltViewModel() else null
    val voiceState by (voiceVm?.uiState ?: remember { MutableStateFlow(VoiceInputUiState()) })
        .collectAsState()
    val useVoice = voiceVm != null && !isStreaming && canSendMessages && !hasSendableContent

    Column(modifier = modifier.fillMaxWidth()) {
        if (pendingAttachments.isNotEmpty()) {
            AttachmentStrip(
                attachments = pendingAttachments,
                onRemove = onRemoveAttachment,
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
                        .clickable(onClick = onAttachImage),
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
}

@Composable
private fun AttachmentStrip(
    attachments: ImmutableList<MessageContentPart.Image>,
    onRemove: (Int) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = attachments,
            key = { it.base64.hashCode() },
        ) { img ->
            val index = attachments.indexOf(img)
            AttachmentThumbnail(
                image = img,
                onRemove = { onRemove(index) },
            )
        }
    }
}

@Composable
private fun AttachmentThumbnail(
    image: MessageContentPart.Image,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val cacheKey = remember(image.base64, image.mediaType) {
        chatAttachmentImageCacheKey(
            base64 = image.base64,
            mediaType = image.mediaType,
        )
    }
    val dataUrl = remember(image.base64, image.mediaType) {
        chatAttachmentImageDataUrl(
            base64 = image.base64,
            mediaType = image.mediaType,
        )
    }
    val request = remember(context, dataUrl, cacheKey) {
        ImageRequest.Builder(context)
            .data(dataUrl)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .build()
    }

    Box(modifier = Modifier.size(64.dp)) {
        Surface(
            modifier = Modifier.size(64.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().size(64.dp),
                contentScale = ContentScale.Crop,
            )
        }
        // Remove button overlay (top-right)
        Surface(
            modifier = Modifier
                .size(20.dp)
                .align(Alignment.TopEnd)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            color = MaterialTheme.colorScheme.errorContainer,
            shape = CircleShape,
        ) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(20.dp),
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
