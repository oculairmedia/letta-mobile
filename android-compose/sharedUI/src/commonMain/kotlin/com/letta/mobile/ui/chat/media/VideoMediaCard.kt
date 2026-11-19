package com.letta.mobile.ui.chat.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.letta.mobile.data.media.MediaKind
import com.letta.mobile.data.media.MediaPlaybackState
import com.letta.mobile.data.media.MediaPlaybackStatus
import com.letta.mobile.data.media.UiMediaAttachment
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.media_exit_fullscreen
import com.letta.mobile.sharedui.resources.media_fullscreen
import com.letta.mobile.sharedui.resources.media_video
import com.letta.mobile.ui.components.foundation.LettaButtonIcon
import com.letta.mobile.ui.components.foundation.LettaIcon
import com.letta.mobile.ui.components.foundation.LettaIconButton
import com.letta.mobile.ui.components.foundation.LettaIconStyle
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.tokens.LettaSpaceTokens
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import com.letta.mobile.ui.theme.tokens.LettaSurfaceSizes
import org.jetbrains.compose.resources.stringResource

/**
 * A video attachment: the frame (the player's first frame once loaded, a neutral poster before or
 * where the platform cannot play it), the shared transport row and a full-screen toggle. The player
 * loads when the card is composed so the first frame can show; it plays only on request.
 */
@Composable
internal fun VideoMediaCard(attachment: UiMediaAttachment, shape: Shape, modifier: Modifier = Modifier) {
    val source = attachment.playbackSource()
    val playback = rememberMediaItemPlayback(source, MediaKind.Video)
    val state by playback.collectState()
    val unavailable = unavailableReason(source)
    var fullscreen by rememberSaveable(attachment.url, attachment.base64.length) { mutableStateOf(false) }
    LaunchedEffect(playback) { playback.load() }
    MediaFrame(
        shape = shape,
        modifier = modifier
            .testTag(MediaTestTags.VIDEO_CARD)
            .pauseWhenScrolledAway(playback)
            .mediaKeyboard(playback, onFullscreen = { fullscreen = !fullscreen }),
    ) {
        MediaTitleLine(
            title = attachment.displayName ?: stringResource(Res.string.media_video),
            source = source,
            leading = { LettaIcon(LettaIcons.Video, contentDescription = null) },
        )
        val frame = Modifier.fillMaxWidth().heightIn(max = LettaSurfaceSizes.inlineMediaMaxHeight)
        VideoFrame(playback, state, showSurface = !fullscreen, modifier = frame)
        MediaTransportBar(playback, state, Modifier.fillMaxWidth()) {
            FullscreenButton(entering = true, enabled = state.isPlayable) { fullscreen = true }
        }
        MediaStatusLine(state, unavailable)
    }
    if (fullscreen) VideoFullscreen(playback, state) { fullscreen = false }
}

/**
 * The picture at the video's own aspect ratio (16:9 until known), as large as fits [modifier]'s
 * bounds without distortion, with a play glyph while paused.
 */
@Composable
private fun VideoFrame(playback: MediaItemPlayback, state: MediaPlaybackState, showSurface: Boolean, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .aspectRatio(state.videoAspectRatio())
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            val player = playback.player
            if (showSurface && player != null && state.status != MediaPlaybackStatus.Unsupported) {
                MediaVideoSurface(player, Modifier.fillMaxSize().testTag(MediaTestTags.VIDEO_SURFACE))
            }
            if (!state.isPlaying) PosterGlyph()
        }
    }
}

@Composable
private fun BoxScope.PosterGlyph() {
    LettaIcon(
        imageVector = LettaIcons.Play,
        contentDescription = null,
        style = LettaIconStyle(size = LettaControlTokens.iconButtonLg, tint = MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier.align(Alignment.Center),
    )
}

@Composable
private fun FullscreenButton(entering: Boolean, enabled: Boolean, onClick: () -> Unit) {
    LettaIconButton(
        icon = LettaButtonIcon(
            imageVector = if (entering) LettaIcons.Maximize else LettaIcons.Minimize,
            contentDescription = stringResource(if (entering) Res.string.media_fullscreen else Res.string.media_exit_fullscreen),
        ),
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.testTag(MediaTestTags.FULLSCREEN),
    )
}

/** The same player full screen: the picture fills the window over the scrim, the transport below it. */
@Composable
private fun VideoFullscreen(playback: MediaItemPlayback, state: MediaPlaybackState, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize().mediaKeyboard(playback, onFullscreen = onDismiss),
            color = MaterialTheme.colorScheme.scrim,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ) {
            Column(Modifier.fillMaxSize().padding(LettaSpaceTokens.md)) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    VideoFrame(playback, state, showSurface = true, modifier = Modifier.fillMaxSize())
                }
                MediaTransportBar(playback, state, Modifier.fillMaxWidth()) {
                    FullscreenButton(entering = false, enabled = true, onClick = onDismiss)
                }
            }
        }
    }
}

private fun MediaPlaybackState.videoAspectRatio(): Float {
    val width = videoWidth ?: return LettaSurfaceSizes.inlineVideoAspectRatio
    val height = videoHeight?.takeIf { it > 0 } ?: return LettaSurfaceSizes.inlineVideoAspectRatio
    return width.toFloat() / height
}
