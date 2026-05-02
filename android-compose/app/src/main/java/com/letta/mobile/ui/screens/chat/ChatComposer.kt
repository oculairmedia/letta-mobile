package com.letta.mobile.ui.screens.chat

import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.letta.mobile.R
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.ui.components.LettaInputBar
import com.letta.mobile.ui.icons.LettaIcons
import kotlinx.collections.immutable.ImmutableList

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
    val canSendSteeringUpdate = isStreaming && canSendMessages && inputText.isNotBlank()

    Column(modifier = modifier.fillMaxWidth()) {
        if (pendingAttachments.isNotEmpty()) {
            AttachmentStrip(
                attachments = pendingAttachments,
                onRemove = onRemoveAttachment,
            )
        }

        if (canSendSteeringUpdate) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                FilledTonalButton(onClick = { onSend(inputText) }) {
                    Icon(
                        LettaIcons.Send,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(stringResource(R.string.action_send_steering_message))
                }
            }
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
            leadingContent = {
                FilledIconButton(
                    onClick = onAttachImage,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(
                        LettaIcons.Add,
                        contentDescription = stringResource(R.string.action_attach_image),
                        modifier = Modifier.size(20.dp),
                    )
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
    val bitmap = remember(image.base64) {
        runCatching {
            val bytes = Base64.decode(image.base64, Base64.NO_WRAP)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    Box(modifier = Modifier.size(64.dp)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {}
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
