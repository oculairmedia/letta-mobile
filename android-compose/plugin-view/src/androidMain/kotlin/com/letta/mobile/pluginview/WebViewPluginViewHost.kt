package com.letta.mobile.pluginview

import android.content.Context
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.letta.mobile.ui.canvas.plugin.PluginViewHost
import com.letta.mobile.ui.canvas.plugin.PluginViewSession
import kotlinx.coroutines.CoroutineScope

/**
 * The Android [PluginViewHost] (letta-mobile-s416w.13): each live element is a sandboxed
 * [PluginWebView]. A device without a usable WebView, a page that fails, or a renderer that dies
 * hands the element back to its fallback card. When the element leaves the board the bridge is
 * torn down and the WebView destroyed, in [scope]: a main-thread scope the screen owns (its view
 * model's), which outlives the element's composition.
 */
class WebViewPluginViewHost(private val scope: CoroutineScope) : PluginViewHost {
    @Composable
    override fun View(session: PluginViewSession, modifier: Modifier, onFailure: (reason: String) -> Unit) {
        val context = LocalContext.current
        val failure by rememberUpdatedState(onFailure)
        val slot = remember(session) { PluginWebViewSlot(context, session, scope) { reason -> failure(reason) } }
        val live = slot.live
        if (live == null) {
            LaunchedEffect(slot) { failure(UNAVAILABLE) }
        } else {
            AndroidView(factory = { live.webView }, modifier = modifier)
        }
    }

    internal companion object {
        const val UNAVAILABLE: String = "WebView is not available on this device"
    }
}

/**
 * Ties one [PluginWebView] to the composition: started when remembered, closed (teardown, then
 * destroy) when forgotten, and destroyed outright when the composition that made it was abandoned.
 * [live] is null when this device cannot make a WebView.
 */
internal class PluginWebViewSlot(
    context: Context,
    session: PluginViewSession,
    scope: CoroutineScope,
    onFailure: (String) -> Unit,
) : RememberObserver {
    val live: PluginWebView? = runCatching { WebView(context) }.getOrNull()?.let { PluginWebView(it, session, scope, onFailure) }

    override fun onRemembered() {
        live?.start()
    }

    override fun onForgotten() {
        live?.close(PluginWebView.REASON_REMOVED)
    }

    override fun onAbandoned() {
        live?.close(PluginWebView.REASON_REMOVED)
    }
}
