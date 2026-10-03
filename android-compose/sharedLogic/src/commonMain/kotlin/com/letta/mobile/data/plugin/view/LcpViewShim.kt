package com.letta.mobile.data.plugin.view

import kotlinx.serialization.json.JsonPrimitive

/**
 * The `lcp-view/1` page shim (plan section 7.2): a script of at most [MAX_BYTES] the host injects
 * before the page so every platform offers one API, `window.lettaView`:
 *
 *  - `version`, `send(message)` (raw JSON-RPC, object or text), `request(method, params)` (a
 *    Promise of the result; rejects with the error), `notify(method, params)`;
 *  - `ready()`, `action(name, input)`, `resize(width, height)`, `displayMode(mode)`, `openLink(url)`,
 *    `log(level, message)` for the six view methods;
 *  - host messages arrive as `message` events on `window` (`event.data` is the JSON-RPC object);
 *    `host.teardown` is acknowledged by the shim after the event's listeners ran.
 *
 * Before the shim, the platform host defines `window.__lettaViewPost(text)` (its native channel to
 * the [PostMessagePort]); it delivers host messages by calling `window.__lettaViewReceive(text)`.
 * [inject] adds the page id `ready()` announces.
 */
object LcpViewShim {
    /** The `viewVersion` the shim announces; one of [LcpView.SUPPORTED_VIEW_VERSIONS]. */
    const val VERSION: String = "1"

    const val MAX_BYTES: Int = 1024

    /** The members of `window.lettaView`, in the order the shim defines them. */
    val API: List<String> = listOf("version", "send", "request", "notify", "ready", "action", "resize", "displayMode", "openLink", "log")

    /** The shim source, before [inject] adds the page id. */
    const val SOURCE: String =
        "(function(w){if(w.lettaView)return;var n=0,q={},P=w.__lettaViewPost;" +
            "function s(m){P(typeof m==\"string\"?m:JSON.stringify(m))}" +
            "function r(m,p){var i=++n;return new Promise(function(a,b){q[i]=[a,b];s({jsonrpc:\"2.0\",id:i,method:m,params:p||{}})})}" +
            "function t(m,p){s({jsonrpc:\"2.0\",method:m,params:p})}" +
            "w.lettaView={version:\"$VERSION\",send:s,request:r,notify:t," +
            "ready:()=>r(\"view.ready\",{pageId:w.__lettaViewPage,viewVersion:\"$VERSION\"})," +
            "action:(a,i)=>r(\"view.action\",{action:a,input:i||{}})," +
            "resize:(x,y)=>t(\"view.resize\",{width:x,height:y})," +
            "displayMode:m=>r(\"view.displayMode\",{mode:m})," +
            "openLink:u=>r(\"view.openLink\",{url:u})," +
            "log:(l,m)=>t(\"view.log\",{level:l,message:String(m)})};" +
            "w.__lettaViewReceive=function(x){var m=typeof x==\"string\"?JSON.parse(x):x,c=m.method?0:q[m.id];" +
            "if(c){delete q[m.id];return m.error?c[1](m.error):c[0](m.result)}if(!m.method)return;" +
            "w.dispatchEvent(new MessageEvent(\"message\",{data:m}));" +
            "if(m.method==\"${HostMethod.TEARDOWN}\")s({jsonrpc:\"2.0\",id:m.id,result:{}})}})(window);"

    /** The script a host injects for page [pageId]: the page id (as a JS string, `<` escaped), then [SOURCE]. */
    fun inject(pageId: String): String = "window.__lettaViewPage=" + JsonPrimitive(pageId).toString().replace("<", "\\u003c") + ";" + SOURCE
}
