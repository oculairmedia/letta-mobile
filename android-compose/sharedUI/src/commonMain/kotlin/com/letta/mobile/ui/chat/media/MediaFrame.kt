package com.letta.mobile.ui.chat.media

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.media.MediaPlaybackState
import com.letta.mobile.data.media.MediaPlaybackStatus
import com.letta.mobile.data.media.MediaSource
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.media_failed
import com.letta.mobile.sharedui.resources.media_loading
import com.letta.mobile.sharedui.resources.media_not_kept
import com.letta.mobile.sharedui.resources.media_open_externally
import com.letta.mobile.sharedui.resources.media_too_large
import com.letta.mobile.sharedui.resources.media_unsupported
import com.letta.mobile.ui.components.foundation.LettaButtonIcon
import com.letta.mobile.ui.components.foundation.LettaIconButton
import com.letta.mobile.ui.components.foundation.LettaSpinner
import com.letta.mobile.ui.icons.LettaIcons
import org.jetbrains.compose.resources.StringResource
import com.letta.mobile.ui.theme.tokens.LettaSpaceTokens
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import com.letta.mobile.ui.theme.tokens.LettaStrokeTokens
import org.jetbrains.compose.resources.stringResource

/**
 * The container every non-image attachment draws in: the image cell's surface colour, hairline and
 * [shape] (the row decides the shape, so a media card matches the images beside it).
 */
@Composable
internal fun MediaFrame(
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = shape,
        border = BorderStroke(LettaStrokeTokens.hairline, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(LettaSpaceTokens.sm),
            verticalArrangement = Arrangement.spacedBy(LettaSpaceTokens.xs),
            content = content,
        )
    }
}

/** A card's title line: the file's name (or its kind) and, for a URL, the open-elsewhere action. */
@Composable
internal fun MediaTitleLine(title: String, source: MediaSource?, modifier: Modifier = Modifier, leading: @Composable () -> Unit = {}) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        leading()
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = LettaSpaceTokens.xs),
        )
        MediaOpenExternallyButton(source)
    }
}

/** Opens a URL source in the platform's own app (browser or player); nothing for inline bytes. */
@Composable
internal fun MediaOpenExternallyButton(source: MediaSource?, modifier: Modifier = Modifier) {
    val remote = source as? MediaSource.Remote ?: return
    val uriHandler = LocalUriHandler.current
    LettaIconButton(
        icon = LettaButtonIcon(LettaIcons.ExternalLink, stringResource(Res.string.media_open_externally)),
        onClick = { runCatching { uriHandler.openUri(remote.url) } },
        modifier = modifier,
    )
}

/** The shared loading, failure and unsupported line under a card's controls; nothing while playable. */
@Composable
internal fun MediaStatusLine(state: MediaPlaybackState, unavailable: StringResource?, modifier: Modifier = Modifier) {
    val message = unavailable ?: statusMessage(state.status)
    if (state.status == MediaPlaybackStatus.Loading && unavailable == null) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaSpaceTokens.xs)) {
            LettaSpinner(size = LettaControlTokens.spinnerSm)
            MediaStatusText(stringResource(Res.string.media_loading))
        }
        return
    }
    if (message != null) MediaStatusText(stringResource(message), modifier)
}

@Composable
private fun MediaStatusText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = LettaSpaceTokens.xs),
    )
}

private fun statusMessage(status: MediaPlaybackStatus): StringResource? = when (status) {
    MediaPlaybackStatus.Failed -> Res.string.media_failed
    MediaPlaybackStatus.Unsupported -> Res.string.media_unsupported
    else -> null
}

/** Why an attachment cannot play before any player tries: bytes not kept, or too large to buffer. */
internal fun unavailableReason(source: MediaSource?): StringResource? = when {
    source == null -> Res.string.media_not_kept
    inlineTooLarge(source) -> Res.string.media_too_large
    else -> null
}
