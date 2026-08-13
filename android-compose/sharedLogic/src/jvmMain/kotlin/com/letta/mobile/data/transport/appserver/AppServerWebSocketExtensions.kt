package com.letta.mobile.data.transport.appserver

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.websocket.WebSocketDeflateExtension

/**
 * letta-mobile data-efficiency Phase 2 (Q2): WebSocket frame compression on the
 * App Server /ws channel.
 *
 * The shared commonMain helper [applyAppServerFrameLimits] only sets the inbound
 * frame ceiling. It deliberately stops short of `extensions { install(...) }`
 * because `WebSocketDeflateExtension` is JVM-only — it lives in the
 * `ktor-websockets-jvm` shared commons (package `io.ktor.websocket`), which
 * commonMain cannot see.
 *
 * Call sites that talk to the App Server on a JVM-or-desktop HttpClient(CIO)
 * should call [applyAppServerDefaults] instead of [applyAppServerFrameLimits]
 * so they get BOTH the frame ceiling and the permessage-deflate extension.
 *
 * Fallback behaviour: Ktor silently negotiates `permessage-deflate` only when
 * the server offers it. If the App Server backend does not advertise the
 * extension, frames are sent uncompressed — identical to the previous
 * behaviour — so installing this is always safe.
 *
 * ENGINE CONSTRAINT: same as [applyAppServerFrameLimits] — the caller's
 * HttpClient must use the CIO engine. The OkHttp engine does not support
 * `maxFrameSize` and would refuse to install this configuration.
 */
fun WebSockets.Config.applyAppServerDefaults() {
    applyAppServerFrameLimits()
    extensions {
        install(WebSocketDeflateExtension)
    }
}
