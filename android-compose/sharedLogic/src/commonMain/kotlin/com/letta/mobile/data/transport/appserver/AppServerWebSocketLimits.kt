package com.letta.mobile.data.transport.appserver

import io.ktor.client.plugins.websocket.WebSockets

/**
 * letta-mobile-lgns8.21.7: inbound frame ceiling for every App Server WebSocket
 * client.
 *
 * Ktor's client `WebSockets` plugin defaults `maxFrameSize` to `Int.MAX_VALUE`
 * (~2 GiB) — no usable ceiling. An App Server response is buffered into one
 * frame and then handed to `Json.parseToJsonElement`, so before this constant
 * existed there was no practical byte ceiling anywhere on the wrapper's inbound
 * path: a single conversation row carrying a large base64 image or tool return
 * could allocate hundreds of megabytes inside the turn-blocking preflight read.
 *
 * Sizing: the serve side already bounds its own projections to 900 KiB pages
 * under a 1 MiB frame cap (`MessageListPageGuard.MAX_PAGE_BYTES` /
 * `IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES`). Direct App Server frames are not
 * subject to that guard and legitimately carry bigger payloads (image parts,
 * long tool returns), so this ceiling is set 16x above the serve-side frame cap:
 * comfortably above any legitimate frame, while still bounded, so a pathological
 * or hostile response fails the socket instead of the heap. Exceeding it closes
 * the session with a protocol error, which the transport surfaces as a
 * retryable connection drop rather than an OOM.
 */
object AppServerWebSocketLimits {
    /** 16 MiB — 16x the 1 MiB serve-side frame cap. */
    const val MAX_FRAME_BYTES: Long = 16L * 1024 * 1024
}

/**
 * Apply the shared App Server inbound frame ceiling. Call at every
 * `install(WebSockets)` site that talks to an App Server `/ws` endpoint.
 */
fun WebSockets.Config.applyAppServerFrameLimits() {
    maxFrameSize = AppServerWebSocketLimits.MAX_FRAME_BYTES
}
