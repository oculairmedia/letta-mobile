package com.letta.mobile.data.transport.iroh

import com.letta.mobile.util.Telemetry
import computer.iroh.Incoming
import computer.iroh.IncomingAddr
import computer.iroh.IrohException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Rate-limited WARN telemetry for a host-side Iroh failure that a peer can
 * repeat at will (a redialling client, a refused handshake).
 *
 * Each distinct error message is counted. Its first [burst] occurrences are logged, then every
 * [every]th, each carrying its running `count`, so a peer redialling into the same refusal cannot
 * flood the host log with thousands of identical lines and the totals still read off the last line.
 *
 * The message comes from [IrohException.message] (the Rust error text, e.g.
 * `peer doesn't support any known protocol`): an `IrohException`'s Kotlin `Throwable.message` is
 * null, which is why these lines used to read only `error=IrohException`.
 */
class IrohFailureLog(
    private val tag: String,
    private val event: String,
    private val burst: Long = DEFAULT_BURST,
    private val every: Long = DEFAULT_EVERY,
) {
    private val counts = ConcurrentHashMap<String, AtomicLong>()

    /** Records one [error]; [alpn] and [origin] say what the peer offered and where it came from. */
    fun record(error: Throwable, alpn: String? = null, origin: IncomingRemote? = null) {
        val message = errorMessage(error)
        val count = countFor(message)
        if (!shouldLog(count)) return
        Telemetry.event(
            tag, event,
            "error" to message,
            "errorKind" to errorKind(error),
            "count" to count,
            "alpn" to alpn,
            "path" to origin?.path,
            "remote" to origin?.remote,
            level = Telemetry.Level.WARN,
        )
    }

    /** Counts one occurrence of [key] and returns its running total. */
    internal fun countFor(key: String): Long {
        // Bounded: an unbounded variety of messages resets the tally instead of growing the map.
        if (counts.size >= MAX_KEYS && !counts.containsKey(key)) counts.clear()
        return counts.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
    }

    internal fun shouldLog(count: Long): Boolean = count <= burst || count % every == 0L

    companion object {
        const val DEFAULT_BURST = 5L
        const val DEFAULT_EVERY = 100L
        private const val MAX_KEYS = 64
        private const val REMOTE_ID_PREFIX = 12

        fun errorMessage(error: Throwable): String =
            (error as? IrohException)?.let { runCatching { it.message() }.getOrNull() }
                ?: error.message
                ?: error::class.simpleName
                ?: "unknown"

        fun errorKind(error: Throwable): String =
            (error as? IrohException)?.let { runCatching { it.kind().name }.getOrNull() }
                ?: error::class.simpleName
                ?: "unknown"

        /** A short remote endpoint id for log lines (full ids are 64 hex chars). */
        fun remotePrefix(endpointHex: String): String = endpointHex.take(REMOTE_ID_PREFIX)

        /**
         * Where an incoming connection came from, known before its handshake: `relay` with the
         * relayed peer's id prefix, `ip` with its socket address, or `other`.
         */
        suspend fun incomingRemote(incoming: Incoming): IncomingRemote =
            when (val addr = runCatching { incoming.remoteAddr() }.getOrNull()) {
                is IncomingAddr.Relay ->
                    IncomingRemote("relay", remotePrefix(IrohDiagnostics.endpointIdHex(addr.endpointId)))
                is IncomingAddr.Ip -> IncomingRemote("ip", addr.addr)
                else -> IncomingRemote("other", "")
            }
    }
}

/**
 * Where a failing peer came from: [path] is `relay`, `ip`, `other`, or `connected` (once
 * handshaken); [remote] is its id prefix or socket address.
 */
data class IncomingRemote(val path: String, val remote: String)
