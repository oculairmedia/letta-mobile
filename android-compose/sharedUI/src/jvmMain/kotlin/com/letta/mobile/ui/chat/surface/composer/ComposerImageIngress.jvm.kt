package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.size
import io.github.vinceglb.filekit.source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.buffered

/** Desktop's window installs AWT clipboard paste and file drop for the whole page (DesktopImageIngress). */
internal actual val platformComposerImageIngress: (@Composable (ComposerImageAttacher) -> Modifier)? = null

/** The picked file streamed and capped through [readImageSource], refused first by its size. */
internal actual suspend fun PlatformFile.readImageBytes(): ByteArray = withContext(Dispatchers.IO) {
    source().buffered().use { file -> readImageSource(knownSize = size()) { buffer -> file.readAtMostTo(buffer) } }
}
