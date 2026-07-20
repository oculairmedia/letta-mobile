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

    @JvmStatic
    external fun nativeTakeLastError(): String?
}
