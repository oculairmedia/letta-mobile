package com.letta.mobile.ui.components

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Build
import android.util.Base64
import android.view.View
import android.widget.FrameLayout
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.letta.mobile.ui.icons.LettaIcons
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Renders a Mermaid diagram from the supplied DSL `source` using a bundled
 * `mermaid.min.js` (see `designsystem/src/main/assets/mermaid.min.js`).
 *
 * Runs fully offline inside a sandboxed [WebView] — no network access. Bridges
 * a single `onRenderError(msg)` callback back to Compose so malformed diagrams
 * can degrade gracefully to the raw source without crashing.
 *
 * Theme follows the current Compose dark/light theme and passes a matching
 * mermaid `theme` config token. Supports pinch-to-zoom via WebView built-in
 * controls.
 */
@OptIn(ExperimentalComposeUiApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MermaidDiagram(
    source: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current

    val isDark = isSystemInDarkTheme()
    val backgroundArgb = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    val foregroundArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    var webViewRef by remember(source) { mutableStateOf<WebView?>(null) }

    // Hoisted so we can flip back to raw source if mermaid throws.
    var renderError by rememberSaveable(source) { mutableStateOf<String?>(null) }

    DisposableEffect(source) {
        onDispose {
            webViewRef?.destroySafely()
            webViewRef = null
        }
    }

    if (renderError != null) {
        MermaidErrorFallback(
            source = source,
            errorMessage = renderError!!,
            modifier = modifier,
        )
        return
    }

    val html = remember(source, isDark, backgroundArgb, foregroundArgb) {
        buildMermaidHtml(
            source = source,
            dark = isDark,
            backgroundArgb = backgroundArgb,
            foregroundArgb = foregroundArgb,
        )
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column {
            MermaidHeader(
                onCopy = { clipboard.setText(AnnotatedString(source)) },
            )

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 120.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { clipboard.setText(AnnotatedString(source)) },
                    ),
                factory = { ctx ->
                    createMermaidWebViewContainer(
                        ctx = ctx,
                        html = html,
                        onError = { renderError = it },
                        onWebViewReady = { webViewRef = it },
                    )
                },
                update = { container ->
                    container.findViewWithTag<WebView>(MERMAID_WEBVIEW_TAG)?.let { webView ->
                        webViewRef = webView
                        webView.loadMermaid(html)
                    }
                },
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createMermaidWebViewContainer(
    ctx: android.content.Context,
    html: String,
    onError: (String) -> Unit,
    onWebViewReady: (WebView) -> Unit,
): FrameLayout = FrameLayout(ctx).apply {
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
    setBackgroundColor(AndroidColor.TRANSPARENT)
    addView(
        WebView(ctx).apply {
            tag = MERMAID_WEBVIEW_TAG
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setBackgroundColor(AndroidColor.TRANSPARENT)
            settings.apply {
                javaScriptEnabled = true
                // Only asset-intercept and the base HTML are ever loaded; keep
                // file and content access off for belt-and-braces.
                allowFileAccess = false
                allowContentAccess = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                builtInZoomControls = true
                displayZoomControls = false
                setSupportZoom(true)
                useWideViewPort = true
                loadWithOverviewMode = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            }
            webViewClient = MermaidWebViewClient(ctx, onError)
            webChromeClient = WebChromeClient()
            addJavascriptInterface(MermaidBridge(onError), MermaidBridge.NAME)
            loadMermaid(html)
            onWebViewReady(this)
        }
    )
}

private fun WebView.loadMermaid(html: String) {
    loadDataWithBaseURL(
        "https://mermaid.local/",
        html,
        "text/html",
        "utf-8",
        null,
    )
}

@Composable
private fun MermaidHeader(onCopy: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "mermaid",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Box(modifier = Modifier.weight(1f))
        IconButton(
            onClick = onCopy,
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            ),
        ) {
            Icon(
                imageVector = LettaIcons.Copy,
                contentDescription = "Copy diagram source",
                modifier = Modifier.padding(4.dp),
            )
        }
    }
}

@Composable
private fun MermaidErrorFallback(
    source: String,
    errorMessage: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Mermaid render failed: $errorMessage",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = source,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private class MermaidBridge(private val onError: (String) -> Unit) {
    @JavascriptInterface
    fun onRenderError(message: String) {
        onError(message)
    }

    companion object {
        const val NAME = "LettaMermaid"
    }
}

/**
 * Serves the bundled `mermaid.min.js` from the designsystem module's assets
 * so the rendered page can `<script src="mermaid.min.js">` offline without
 * needing `allowFileAccess`.
 */
private class MermaidWebViewClient(
    private val context: android.content.Context,
    private val onError: (String) -> Unit,
) : WebViewClient() {
    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url?.toString() ?: return null
        if (url.endsWith("/mermaid.min.js")) {
            return try {
                val stream = context.assets.open("mermaid.min.js")
                WebResourceResponse("application/javascript", "utf-8", stream)
            } catch (e: IOException) {
                WebResourceResponse(
                    "text/plain",
                    "utf-8",
                    ByteArrayInputStream(
                        "window.LettaMermaid?.onRenderError('asset load failed: ${e.message}');"
                            .toByteArray(),
                    ),
                )
            }
        }
        return null
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        onError("Mermaid renderer crashed${detail?.let { " (didCrash=${it.didCrash()})" } ?: ""}")
        view?.destroySafely()
        return true
    }
}

private fun WebView.destroySafely() {
    stopLoading()
    onPause()
    pauseTimers()
    removeJavascriptInterface(MermaidBridge.NAME)
    clearHistory()
    loadUrl("about:blank")
    removeAllViews()
    (parent as? ViewGroup)?.removeView(this)
    destroy()
}

private const val MERMAID_WEBVIEW_TAG = "mermaid-webview"

internal fun buildMermaidHtml(
    source: String,
    dark: Boolean,
    backgroundArgb: Int,
    foregroundArgb: Int,
): String {
    val theme = if (dark) "dark" else "default"
    val bgCss = argbToCssHex(backgroundArgb)
    val fgCss = argbToCssHex(foregroundArgb)
    // Base64 so we don't have to escape backticks / newlines / quotes inside
    // the mermaid source.
    val encoded = Base64.encodeToString(source.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    return """
<!doctype html>
<html>
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width,initial-scale=1" />
    <style>
      html, body {
        margin: 0;
        padding: 8px;
        background: $bgCss;
        color: $fgCss;
        font-family: sans-serif;
      }
      #container { width: 100%; overflow: auto; }
      #container svg { max-width: 100%; height: auto; }
      .mermaid-error { color: #b00020; white-space: pre-wrap; }
    </style>
  </head>
  <body>
    <div id="container"><div class="mermaid" id="diagram"></div></div>
    <script src="mermaid.min.js"></script>
    <script>
      (function () {
        try {
          var src = atob('$encoded');
          document.getElementById('diagram').textContent = src;
          mermaid.initialize({ startOnLoad: false, theme: '$theme', securityLevel: 'strict' });
          mermaid.run({ nodes: [document.getElementById('diagram')] }).catch(function (err) {
            try { LettaMermaid.onRenderError(String(err && err.message ? err.message : err)); } catch (e) {}
          });
        } catch (err) {
          try { LettaMermaid.onRenderError(String(err && err.message ? err.message : err)); } catch (e) {}
        }
      })();
    </script>
  </body>
</html>
""".trimIndent()
}

private fun argbToCssHex(argb: Int): String {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return String.format(java.util.Locale.ROOT, "#%02x%02x%02x", r, g, b)
}
