package com.letta.mobile.ui.chat.media

import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.letta.mobile.data.media.MediaPlayer
import com.letta.mobile.data.media.MediaPlayerFactory

@Composable
internal actual fun rememberPlatformMediaPlayerFactory(): MediaPlayerFactory {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    return remember(context, scope) { MediaPlayerFactory { ExoMediaPlayer(context, scope) } }
}

@Composable
internal actual fun MediaVideoSurface(player: MediaPlayer, modifier: Modifier) {
    val exo = (player as? ExoMediaPlayer)?.exoPlayer ?: return
    AndroidView(
        factory = { SurfaceView(it) },
        modifier = modifier,
        update = { view -> exo.setVideoSurfaceView(view) },
        onRelease = { view -> exo.clearVideoSurfaceView(view) },
    )
}
