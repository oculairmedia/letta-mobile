package com.letta.mobile.feature.chat

/**
 * Process-local idempotency for automatic route-provided messages (Android
 * shares, notification/deeplink starters). The per-ViewModel AtomicBoolean is
 * enough for re-resolves in one instance, but Android share delivery can create
 * two equivalent chat route/ViewModel instances before the first navigation is
 * fully settled. In that case both instances carry the same initialMessage and
 * otherwise race into the send path. Keep this narrowly scoped and time-bound
 * so normal manual sends are unaffected.
 */
internal object InitialRouteMessageDeliveryGuard {
    private const val DELIVERY_WINDOW_MS = 30_000L
    private val deliveredAtByKey = linkedMapOf<String, Long>()

    fun key(agentId: String, conversationId: String?, message: String): String = buildString {
        append(agentId)
        append('|')
        append(conversationId.orEmpty())
        append('|')
        append(message)
    }

    fun tryConsume(
        key: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(deliveredAtByKey) {
        val cutoff = nowMs - DELIVERY_WINDOW_MS
        val iterator = deliveredAtByKey.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < cutoff) iterator.remove()
        }
        if (deliveredAtByKey.containsKey(key)) {
            false
        } else {
            deliveredAtByKey[key] = nowMs
            true
        }
    }

    fun resetForTests() = synchronized(deliveredAtByKey) {
        deliveredAtByKey.clear()
    }
}
