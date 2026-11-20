package com.letta.mobile.ui.chat.media

import com.letta.mobile.data.media.JavaSoundMediaPlayer
import com.letta.mobile.data.media.MediaKind
import com.letta.mobile.data.media.MediaPlayer
import com.letta.mobile.data.media.MediaSource
import com.letta.mobile.data.media.UnsupportedMediaPlayer
import java.io.File
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CoroutineScope

/**
 * Desktop: video plays through the library's OS engine (Media Foundation, AVFoundation, GStreamer, over
 * its bundled JNI bridge), or offers to open elsewhere when that bridge cannot load. Audio stays on
 * Java Sound (WAV, AIFF, AU): in ComposeMediaPlayer 0.11.4 the Windows and macOS engines fail to open
 * any source without a video track, so it cannot play audio-only files there yet.
 */
internal actual fun platformMediaPlayer(kind: MediaKind, scope: CoroutineScope): MediaPlayer? = when {
    kind == MediaKind.Audio -> JavaSoundMediaPlayer(scope)
    !nativeBackendLoads -> UnsupportedMediaPlayer()
    else -> null
}

/**
 * The desktop engines open files and URLs, not `data:` URIs, so inline bytes go to a temporary file
 * that lives as long as the player (and is deleted on exit if the player outlives the process).
 */
internal actual fun inlineMediaUri(source: MediaSource.Inline): InlineMediaUri? = try {
    val bytes = Base64.getMimeDecoder().decode(source.base64)
    val file = File.createTempFile(TEMP_PREFIX, ".${extensionFor(source.mimeType)}").apply { deleteOnExit() }
    file.writeBytes(bytes)
    InlineMediaUri(file.absolutePath) { file.delete() }
} catch (_: IOException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/** The engines pick a demuxer by extension, so the file keeps the video type's usual one. */
private fun extensionFor(mimeType: String): String {
    val subtype = mimeType.substringAfter('/').substringBefore(';').lowercase()
    return MIME_EXTENSIONS[subtype] ?: FALLBACK_EXTENSION
}

/**
 * Whether the bridge class for this OS initialises: it loads the native library, which on Linux
 * links GStreamer. Probed once; a missing library would otherwise fail inside the library's threads.
 */
private val nativeBackendLoads: Boolean by lazy {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val bridge = when {
        os.contains("win") -> "windows.WindowsNativeBridge"
        os.contains("mac") -> "mac.MacNativeBridge"
        else -> "linux.LinuxNativeBridge"
    }
    try {
        Class.forName("$LIBRARY_PACKAGE.$bridge")
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: LinkageError) {
        false
    }
}

private const val LIBRARY_PACKAGE = "io.github.kdroidfilter.composemediaplayer"
private const val TEMP_PREFIX = "letta-media-"
private const val FALLBACK_EXTENSION = "bin"
private val MIME_EXTENSIONS = mapOf(
    "mp4" to "mp4", "x-m4v" to "m4v", "webm" to "webm", "quicktime" to "mov", "x-matroska" to "mkv",
)
