package com.letta.mobile.data.transport.appserver

import kotlin.random.Random
import kotlinx.atomicfu.atomic

/**
 * letta-mobile-by4vm.6: thread-safe App Server request-id source.
 *
 * Ids are `<prefix>-<nonce>-<counter>`. The counter is atomic, so parallel turns on
 * `Dispatchers.Default` never mint the same id (which [AppServerRequestRegistry]
 * rejects as `duplicate request_id`). The nonce is random per factory instance — and
 * the default factories are process-wide singletons — so ids are not reused against a
 * long-lived server after an app restart, and two factories never collide even with
 * the same prefix. The prefix stays first so logs remain greppable (`controller-req-`).
 */
class AppServerRequestIdFactory(
    private val prefix: String,
    random: Random = Random.Default,
) : () -> String {
    private val nonce: String = (random.nextLong() and Long.MAX_VALUE).toString(NONCE_RADIX)
    private val counter = atomic(0L)

    override fun invoke(): String = "$prefix-$nonce-${counter.incrementAndGet()}"

    private companion object {
        const val NONCE_RADIX = 36
    }
}
