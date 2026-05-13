package com.letta.mobile.data.api

import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryContext
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that records one Telemetry event per HTTP round-trip.
 *
 * Accepts [TelemetryContext] as a context parameter so the interceptor
 * can call [event] and [error] without a static import of [Telemetry].
 *
 * See: letta-mobile-925m.3
 */
internal class TelemetryInterceptor(
    private val telemetryContext: TelemetryContext,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        val method = request.method
        val start = System.currentTimeMillis()

        val response = try {
            chain.proceed(request)
        } catch (t: Throwable) {
            telemetryContext.error(
                "Http", "request:failed", t,
                "method" to method,
                "path" to path,
                "durationMs" to (System.currentTimeMillis() - start),
            )
            throw t
        }

        val duration = System.currentTimeMillis() - start
        val bytes = response.header("Content-Length")?.toLongOrNull() ?: -1L
        val level = if (response.code >= 400) Telemetry.Level.WARN else Telemetry.Level.DEBUG

        telemetryContext.event(
            "Http", "request",
            "method" to method,
            "path" to path,
            "status" to response.code,
            "bytes" to bytes,
            durationMs = duration,
            level = level,
        )

        return response
    }
}
