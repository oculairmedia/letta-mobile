package com.letta.mobile.ui.components

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Build
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * Render a single LaTeX expression — block or inline — using KaTeX inside a
 * sandboxed offline WebView.
 *
 * The implementation mirrors [MermaidDiagram]:
 * - bundled `katex.min.js` + `katex.min.css` + woff2 fonts under `assets/katex/`
 *   served via [WebViewClient.shouldInterceptRequest] (no network)
 * - source is base64-embedded in the host HTML to neutralize backticks /
 *   `</script>` injection
 * - errors from `katex.renderToString({throwOnError:false})` post back through
 *   a JS bridge → Compose surface fallback that renders the raw source
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MathBlock(
    source: String,
    modifier: Modifier = Modifier,
    displayMode: Boolean = true,
) {
    val isDark = isSystemInDarkTheme()
    val foregroundArgb = MaterialTheme.colorScheme.onSurface.toArgb()
    val backgroundArgb = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    val clipboard = LocalClipboardManager.current

    var renderError by rememberSaveable(source) { mutableStateOf<String?>(null) }

    if (renderError != null) {
        MathErrorFallback(source = source, errorMessage = renderError!!, modifier = modifier)
        return
    }

    val html = remember(source, isDark, displayMode, backgroundArgb, foregroundArgb) {
        buildKatexHtml(
            source = source,
            displayMode = displayMode,
            dark = isDark,
            backgroundArgb = backgroundArgb,
            foregroundArgb = foregroundArgb,
        )
    }

    Surface(
        shape = RoundedCornerShape(if (displayMode) 8.dp else 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .then(if (displayMode) Modifier.fillMaxWidth() else Modifier)
            .padding(vertical = if (displayMode) 4.dp else 0.dp),
    ) {
        AndroidView(
            modifier = Modifier
                .then(if (displayMode) Modifier.fillMaxWidth() else Modifier)
                .defaultMinSize(minHeight = if (displayMode) 60.dp else 32.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = { clipboard.setText(AnnotatedString(source)) },
                ),
            factory = { ctx -> createKatexWebView(ctx, html) { renderError = it } },
            update = { it.loadDataWithBaseURL("https://appassets/", html, "text/html", "utf-8", null) },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createKatexWebView(
    ctx: android.content.Context,
    html: String,
    onError: (String) -> Unit,
): WebView = WebView(ctx).apply {
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
    settings.apply {
        javaScriptEnabled = true
        // No file:// or content:// access; only the asset intercept is allowed
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = false
        domStorageEnabled = false
        cacheMode = WebSettings.LOAD_NO_CACHE
        builtInZoomControls = false
        displayZoomControls = false
        useWideViewPort = false
        loadWithOverviewMode = false
        textZoom = 100
    }
    setBackgroundColor(AndroidColor.TRANSPARENT)
    isVerticalScrollBarEnabled = false
    isHorizontalScrollBarEnabled = false
    addJavascriptInterface(KatexBridge { onError(it) }, "LettaKatex")
    webViewClient = KatexWebViewClient(ctx)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
    }
    loadDataWithBaseURL("https://appassets/", html, "text/html", "utf-8", null)
}

private class KatexBridge(private val onError: (String) -> Unit) {
    @JavascriptInterface
    fun onRenderError(message: String) {
        onError(message)
    }
}

/**
 * WebViewClient that serves the bundled katex assets from /assets/katex.
 * Only paths under appassets/katex/ are allowed; everything else 404s, which
 * (combined with allowFileAccess=false) keeps the WebView fully offline.
 */
private class KatexWebViewClient(private val ctx: android.content.Context) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url?.toString() ?: return null
        val prefix = "https://appassets/"
        if (!url.startsWith(prefix)) return null
        val rel = url.removePrefix(prefix)
        if (!rel.startsWith("katex/")) return null

        val mime = when {
            rel.endsWith(".js") -> "application/javascript"
            rel.endsWith(".css") -> "text/css"
            rel.endsWith(".woff2") -> "font/woff2"
            rel.endsWith(".woff") -> "font/woff"
            rel.endsWith(".ttf") -> "font/ttf"
            else -> "application/octet-stream"
        }
        return runCatching {
            val bytes = ctx.assets.open(rel).use { it.readBytes() }
            WebResourceResponse(mime, "utf-8", ByteArrayInputStream(bytes))
        }.getOrNull()
    }
}

@Composable
private fun MathErrorFallback(source: String, errorMessage: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Box(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "LaTeX render failed: $errorMessage\n\n$source",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

// ---------- Pure HTML builder (unit-testable) ----------

private fun argbToCssHex(argb: Int): String {
    val r = (argb shr 16) and 0xff
    val g = (argb shr 8) and 0xff
    val b = argb and 0xff
    return String.format(Locale.ROOT, "#%02x%02x%02x", r, g, b)
}

@VisibleForTesting
internal fun buildKatexHtml(
    source: String,
    displayMode: Boolean,
    dark: Boolean,
    backgroundArgb: Int,
    foregroundArgb: Int,
): String {
    val bg = argbToCssHex(backgroundArgb)
    val fg = argbToCssHex(foregroundArgb)
    val srcB64 = Base64.encodeToString(source.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    return """
        <!DOCTYPE html><html><head>
        <meta charset="utf-8">
        <link rel="stylesheet" href="katex/katex.min.css">
        <style>
          html, body { margin:0; padding:0; background:transparent; color:$fg; }
          body { font-family: 'KaTeX_Main', serif; padding: 8px 12px; }
          .mathwrap { background:$bg; color:$fg; overflow-x:auto; }
          .katex { color:$fg !important; }
          .katex-display { margin: 0.25em 0 !important; }
          ${if (dark) "body { color-scheme: dark; }" else ""}
        </style>
        <script src="katex/katex.min.js"></script>
        </head><body>
        <div id="m" class="mathwrap"></div>
        <script>
          (function() {
            try {
              var src = atob("$srcB64");
              var html = katex.renderToString(src, {
                throwOnError: false,
                displayMode: $displayMode,
                output: 'html',
                strict: 'ignore',
                trust: false
              });
              document.getElementById('m').innerHTML = html;
            } catch (e) {
              if (window.LettaKatex && window.LettaKatex.onRenderError) {
                window.LettaKatex.onRenderError(String(e && e.message || e));
              }
            }
          })();
        </script>
        </body></html>
    """.trimIndent()
}
