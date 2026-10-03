package com.letta.mobile.data.plugin.wire

import kotlin.time.Duration

/** Calls [type] on the other side with typed [params] and decodes its result. */
suspend fun <P, R> LcpPeer.call(type: LcpRequestType<P, R>, params: P, timeout: Duration? = null): R =
    type.decodeResult(request(type.method, type.encodeParams(params), timeout))

/** Sends the notification [type] with typed [params]. */
suspend fun <P> LcpPeer.notify(type: LcpNotificationType<P>, params: P) = notify(type.method, type.encodeParams(params))

/** Serves [type] with a typed [block]; params that do not decode are answered [LcpErrorCode.INVALID_PARAMS]. */
fun <P, R> LcpPeer.serve(type: LcpRequestType<P, R>, block: suspend (P) -> R) =
    handle(type.method) { params -> type.encodeResult(block(type.decodeParams(params))) }

/** Serves the notification [type] with a typed [block]. */
fun <P> LcpPeer.serve(type: LcpNotificationType<P>, block: suspend (P) -> Unit) =
    handle(type.method) { params ->
        block(type.decodeParams(params))
        params
    }
