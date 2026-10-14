package com.letta.mobile.desktop.plugin.view

/**
 * Desktop's channel script for `PluginPageDocument.compose`: `window.__lettaViewPost` over the JCEF
 * message router. The shared document puts it, then the `lcp-view/1` shim, first in the page's
 * head, so both run before any of the page's own scripts.
 */
internal object PluginPageShim {
    /** The message router's query function in the page; not `cefQuery`, so a page cannot mistake it for a general channel. */
    const val QUERY_FUNCTION: String = "__lettaCefQuery"
    const val CANCEL_FUNCTION: String = "__lettaCefQueryCancel"

    const val CHANNEL_SCRIPT: String =
        "window.__lettaViewPost=function(t){window.$QUERY_FUNCTION({request:String(t),persistent:false," +
            "onSuccess:function(){},onFailure:function(){}})};"
}
