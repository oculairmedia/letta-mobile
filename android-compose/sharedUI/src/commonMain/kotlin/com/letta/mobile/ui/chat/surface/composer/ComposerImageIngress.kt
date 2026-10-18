package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * letta-mobile-k5nbq.7: paste and drop of images onto the composer. Android uses the view's
 * rich-content receiver (Gboard / drag from another app); desktop already installs AWT paste and
 * drop, so its actual is a no-op.
 */
@Composable
internal expect fun Modifier.composerImageIngress(attacher: ComposerImageAttacher): Modifier

/** Bytes from paste/drop as picker sources, so they share [encodeAttachment] downscale and cap. */
internal fun imageBytesAsSources(payloads: List<ByteArray>): List<ComposerImageSource> =
    payloads.map { bytes -> { bytes } }
