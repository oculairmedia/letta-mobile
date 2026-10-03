package com.letta.mobile.pluginview

import android.os.Message
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.letta.mobile.data.plugin.PluginCapability

/**
 * The plugin page's [WebViewClient]: every request is answered by [PluginWebView.intercept], the
 * page never navigates anywhere, and a page that fails to load or an engine that dies hands the
 * element back to its fallback card.
 */
internal class PluginWebViewClient(private val view: PluginWebView) : WebViewClient() {
    override fun shouldInterceptRequest(webView: WebView, request: WebResourceRequest): WebResourceResponse? =
        view.intercept(request.url.toString())

    override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean = true

    override fun onPageFinished(webView: WebView, url: String?) {
        view.onPageFinished(url)
    }

    override fun onReceivedError(webView: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) view.fail("the page failed to load: ${error.description}")
    }

    override fun onReceivedHttpError(webView: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (request.isForMainFrame) view.fail("the page failed to load: HTTP ${response.statusCode}")
    }

    /** The engine is gone: say so and keep the app alive; the WebView is destroyed when the view closes. */
    override fun onRenderProcessGone(webView: WebView, detail: RenderProcessGoneDetail): Boolean {
        view.fail("the page's renderer stopped")
        return true
    }
}

/**
 * The plugin page's [WebChromeClient]: device permissions only as far as [permissions] (what the
 * page declares and the person granted) reach, no windows, and no JavaScript dialogs.
 */
internal class PluginWebChromeClient(private val permissions: Set<PluginCapability>) : WebChromeClient() {
    override fun onPermissionRequest(request: PermissionRequest) {
        val allowed = request.resources.filter { resource -> RESOURCES[resource]?.let { it in permissions } == true }
        if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
        callback.invoke(origin, PluginCapability.UI_GEOLOCATION in permissions, false)
    }

    override fun onCreateWindow(webView: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean = false

    override fun onJsAlert(webView: WebView, url: String?, message: String?, result: JsResult): Boolean = result.refuse()

    override fun onJsConfirm(webView: WebView, url: String?, message: String?, result: JsResult): Boolean = result.refuse()

    override fun onJsPrompt(webView: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean =
        result.refuse()

    override fun onJsBeforeUnload(webView: WebView, url: String?, message: String?, result: JsResult): Boolean = result.refuse()

    private fun JsResult.refuse(): Boolean {
        cancel()
        return true
    }

    private companion object {
        /** The WebView resource each page permission covers. */
        val RESOURCES: Map<String, PluginCapability> = mapOf(
            PermissionRequest.RESOURCE_VIDEO_CAPTURE to PluginCapability.UI_CAMERA,
            PermissionRequest.RESOURCE_AUDIO_CAPTURE to PluginCapability.UI_MICROPHONE,
        )
    }
}
