package com.letta.mobile.mermaid

/**
 * Package-stable JNI facade for the Rust Mermaid renderer.
 * Both Android and desktop bridges resolve against this single class name.
 */
internal object MermaidNativeRenderer {
    @JvmStatic
    external fun nativeRenderToSvg(
        source: String,
        darkTheme: Boolean,
        textArgb: Int,
        borderArgb: Int,
        surfaceArgb: Int,
        primaryArgb: Int,
        secondaryArgb: Int,
        tertiaryArgb: Int,
    ): String?

    /**
     * Rasterizes the diagram natively and returns PNG bytes, or null on failure
     * (call [nativeTakeLastError] for the reason). Rasterizing in Rust — rather
     * than via skiko's SVGDOM, which has no font manager — is what makes text
     * labels actually paint on desktop.
     */
    @JvmStatic
    external fun nativeRenderToPng(
        source: String,
        darkTheme: Boolean,
        textArgb: Int,
        borderArgb: Int,
        surfaceArgb: Int,
        primaryArgb: Int,
        secondaryArgb: Int,
        tertiaryArgb: Int,
        targetWidthPx: Int,
    ): ByteArray?

    @JvmStatic
    external fun nativeTakeLastError(): String?
}
