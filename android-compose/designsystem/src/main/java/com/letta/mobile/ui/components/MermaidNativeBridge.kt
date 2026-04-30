package com.letta.mobile.ui.components

import android.util.Log

internal sealed interface MermaidNativeRenderResult {
    data class Rendered(val svg: String) : MermaidNativeRenderResult
    data class Failed(val reason: String) : MermaidNativeRenderResult
    data object Unavailable : MermaidNativeRenderResult
}

internal data class MermaidStyleSpec(
    val textArgb: Int,
    val borderArgb: Int,
    val surfaceArgb: Int,
    val primaryArgb: Int,
    val secondaryArgb: Int,
    val tertiaryArgb: Int,
)

/**
 * Optional JNI bridge for the Rust Mermaid spike.
 *
 * The app remains buildable and runnable even when the native library is absent:
 * in that case [renderToSvg] returns [MermaidNativeRenderResult.Unavailable] and
 * the caller falls back to the existing WebView renderer.
 */
internal object MermaidNativeBridge {
    private const val TAG = "MermaidNativeBridge"
    private const val LIB_NAME = "letta_mermaid_renderer"

    private val loadState: Boolean by lazy {
        runCatching {
            System.loadLibrary(LIB_NAME)
            true
        }.getOrElse { error ->
            Log.i(TAG, "Native Mermaid library unavailable; using WebView fallback", error)
            false
        }
    }

    fun isAvailable(): Boolean = loadState

    fun renderToSvg(
        source: String,
        darkTheme: Boolean,
        style: MermaidStyleSpec,
    ): MermaidNativeRenderResult {
        if (!loadState) return MermaidNativeRenderResult.Unavailable

        return runCatching {
            val svg = nativeRenderToSvg(
                source = source,
                darkTheme = darkTheme,
                textArgb = style.textArgb,
                borderArgb = style.borderArgb,
                surfaceArgb = style.surfaceArgb,
                primaryArgb = style.primaryArgb,
                secondaryArgb = style.secondaryArgb,
                tertiaryArgb = style.tertiaryArgb,
            )
            if (svg.isNullOrBlank()) {
                val reason = nativeTakeLastError()
                    ?.takeIf { it.isNotBlank() }
                    ?: "native renderer returned empty SVG"
                MermaidNativeRenderResult.Failed(reason)
            } else {
                MermaidNativeRenderResult.Rendered(svg)
            }
        }.getOrElse { error ->
            MermaidNativeRenderResult.Failed(
                error.message ?: error::class.java.simpleName,
            )
        }
    }

    @JvmStatic
    private external fun nativeRenderToSvg(
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
    private external fun nativeTakeLastError(): String?
}
