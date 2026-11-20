package com.letta.mobile.ui.chat.media

import com.letta.mobile.data.media.MediaKind
import com.letta.mobile.data.media.MediaPlayer
import com.letta.mobile.data.media.MediaSource
import kotlinx.coroutines.CoroutineScope

/** An HTML video element plays every kind, audio-only sources included. */
internal actual fun platformMediaPlayer(kind: MediaKind, scope: CoroutineScope): MediaPlayer? = null

/** The browser reads `data:` URIs directly, so nothing is written. */
internal actual fun inlineMediaUri(source: MediaSource.Inline): InlineMediaUri? = source.dataUri()
