package com.letta.mobile.ui.chat.media

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.media.MediaPlaybackState
import com.letta.mobile.data.media.UiMediaAttachment
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.media_file
import com.letta.mobile.ui.components.foundation.LettaIcon
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.tokens.LettaSpaceTokens
import org.jetbrains.compose.resources.stringResource

/** Any other file the agent links: its name and type, and the shared open-elsewhere action. */
@Composable
internal fun MediaFileCard(attachment: UiMediaAttachment, shape: Shape, modifier: Modifier = Modifier) {
    val source = attachment.playbackSource()
    MediaFrame(shape = shape, modifier = modifier.testTag(MediaTestTags.FILE_CARD)) {
        MediaTitleLine(
            title = attachment.displayName ?: stringResource(Res.string.media_file),
            source = source,
            leading = { LettaIcon(LettaIcons.File, contentDescription = null) },
        )
        Text(
            text = attachment.mediaType,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = LettaSpaceTokens.xs),
        )
        MediaStatusLine(state = MediaPlaybackState(), unavailable = unavailableReason(source))
    }
}
