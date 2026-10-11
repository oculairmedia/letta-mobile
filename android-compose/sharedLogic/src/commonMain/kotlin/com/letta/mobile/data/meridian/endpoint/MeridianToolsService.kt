package com.letta.mobile.data.meridian.endpoint

import com.letta.mobile.data.meridian.MeridianCommandRouter
import com.letta.mobile.data.meridian.MeridianError
import com.letta.mobile.data.meridian.MeridianErrorCode
import com.letta.mobile.data.meridian.MeridianRequest
import com.letta.mobile.data.meridian.MeridianResponse

/**
 * The transport-agnostic body of the `meridian/tools/1` endpoint (letta-mobile-jna0o.4): one request
 * line in, one response line out. The socket server around it only moves lines; a later in-process
 * loopback (jna0o.11) can reuse it as is.
 *
 * Order of checks: the wire shape, then the bearer token when the transport needs one (the loopback
 * TCP fallback; a Unix socket is guarded by its file mode), then the caller binding, then the
 * router, which applies its own size caps and per-conversation rate limit. The router is the only
 * thing reachable: no REST passthrough, no admin, no profile commands.
 */
class MeridianToolsService(
    private val router: MeridianCommandRouter,
    private val binder: MeridianCallerBinder,
    private val requiredToken: String? = null,
) {
    suspend fun handle(line: String): String = MeridianToolsWire.encodeResponse(respond(line))

    suspend fun respond(line: String): MeridianResponse {
        val request = MeridianToolsWire.decodeRequest(line).getOrElse { return refusal(it) }
        if (requiredToken != null && !constantTimeEquals(requiredToken, request.token.orEmpty())) {
            return MeridianError(MeridianErrorCode.DENIED, "missing or wrong endpoint token").toResponse()
        }
        val caller = binder.bind(request.agentId, request.conversationId).getOrElse { return refusal(it) }
        return router.execute(MeridianRequest(request.argv, request.stdin?.takeIf { it.isNotEmpty() }, caller))
    }

    private fun refusal(failure: Throwable): MeridianResponse =
        (failure as? MeridianWireRefusal)?.response
            ?: MeridianError(MeridianErrorCode.USAGE, failure.message ?: "the request could not be read").toResponse()

    private fun constantTimeEquals(expected: String, actual: String): Boolean {
        val a = expected.encodeToByteArray()
        val b = actual.encodeToByteArray()
        var diff = a.size xor b.size
        for (i in a.indices) diff = diff or (a[i].toInt() xor b.getOrElse(i) { 0 }.toInt())
        return diff == 0
    }

    companion object {
        /** The answer to a request line over [MeridianToolsWire.MAX_REQUEST_LINE_BYTES], sent unread. */
        fun tooLarge(): String = MeridianToolsWire.encodeResponse(
            MeridianError(
                MeridianErrorCode.INPUT_TOO_LARGE,
                "the request is over ${MeridianToolsWire.MAX_REQUEST_LINE_BYTES} bytes",
            ).toResponse(),
        )
    }
}
