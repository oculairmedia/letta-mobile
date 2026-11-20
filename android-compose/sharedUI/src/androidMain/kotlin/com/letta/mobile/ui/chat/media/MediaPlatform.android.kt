package com.letta.mobile.ui.chat.media

import com.letta.mobile.data.media.MediaKind
import com.letta.mobile.data.media.MediaPlayer
import com.letta.mobile.data.media.MediaSource
import kotlinx.coroutines.CoroutineScope

/** Media3 ExoPlayer (with audio focus and becoming-noisy handling, the library's default audio mode) plays every kind. */
internal actual fun platformMediaPlayer(kind: MediaKind, scope: CoroutineScope): MediaPlayer? = null

/** Media3 reads `data:` URIs directly, so nothing is written. */
internal actual fun inlineMediaUri(source: MediaSource.Inline): InlineMediaUri? = source.dataUri()
