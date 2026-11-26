package com.letta.mobile.data.node.carrier.iroh

import com.letta.mobile.data.node.carrier.NodeCallLane
import com.letta.mobile.data.node.carrier.NodeCarrierException
import com.letta.mobile.data.node.carrier.NodeCarrierFailure
import com.letta.mobile.data.node.carrier.NodeFrame
import com.letta.mobile.data.node.carrier.NodeLinkClosure
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.RecvStream
import computer.iroh.SendStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What both ends of an Iroh link share: the QUIC connection, the one closure, the coroutines that read its streams,
 * and what must end when the link ends. The closure is set exactly once, before any lane completes, so a collector
 * that sees its lane complete always finds the closure set.
 */
internal class IrohLinkCore(private val connection: Connection, parent: CoroutineScope) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val closureState = MutableStateFlow<NodeLinkClosure?>(null)
    private val onEnd = CopyOnWriteArrayList<() -> Unit>()

    /** The link's coroutines (stream readers, the close watcher); cancelled when the link ends. */
    @Suppress("NoDetachedCoroutineLifecycle") // A child of the caller's scope; the link's end cancels its job.
    val scope: CoroutineScope = CoroutineScope(parent.coroutineContext + job)

    /** Null while the link is open; then why it ended. */
    val closure: StateFlow<NodeLinkClosure?> = closureState.asStateFlow()

    /** Watches the connection and ends the link with the closure the transport reports when it goes away. */
    fun watchConnection() {
        scope.launch {
            val reason = runCatching { connection.closed() }.getOrNull()
            end(IrohCloseReasons.closureOf(reason))
        }
    }

    /** Runs [action] when the link ends (at once if it already has). */
    fun whenEnded(action: () -> Unit) {
        onEnd += action
        if (closureState.value != null) action()
    }

    /** Ends the link with [closure] and closes the connection with its code and reason. Idempotent. */
    fun close(closure: NodeLinkClosure) {
        if (!closureState.compareAndSet(null, closure)) return
        runCatching { connection.close(closure.code, closure.reason.encodeToByteArray()) }
        finish()
    }

    /** Ends the link with a closure the transport reported (the other end closed, or the connection was lost). */
    private fun end(closure: NodeLinkClosure) {
        if (!closureState.compareAndSet(null, closure)) return
        finish()
    }

    private fun finish() {
        onEnd.forEach { runCatching { it() } }
        job.cancel()
    }

    /** The exception every write and open throws once the link ended. */
    fun closed(): NodeCarrierException =
        NodeCarrierException(NodeCarrierFailure.Closed(closureState.value ?: NodeLinkClosure.LOST))

    /** Writes [frame] on [send] under [mutex]; [frameParts] lets a frame over one Iroh frame go as `frame_part`s. */
    suspend fun write(send: SendStream, mutex: Mutex, frame: NodeFrame, frameParts: Boolean) {
        if (closureState.value != null) throw closed()
        try {
            mutex.withLock { IrohFrameCodec.write(send, frame.encode(), MAX_FRAME_BYTES, allowFrameParts = frameParts) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IrohFrameCodec.FrameTooLargeException) {
            throw error
        } catch (_: Exception) {
            throw closed()
        }
    }

    /**
     * Reads frames from [recv] into [sink] until the stream ends or the link does. A read error is the connection
     * going away (the close watcher reports why) or a framing violation, which ends the link as [NodeLinkClosure.LOST].
     * A frame that is not a JSON object ends the link as [MALFORMED_FRAME]: it may have carried a request id, and a
     * dropped request would leave its caller waiting for ever. A full [sink] suspends the read, so the other end is
     * held by QUIC flow control. [closeAtEnd] closes [sink] when the stream ends, for a call lane; the control and push
     * lanes close with the link.
     */
    fun read(recv: RecvStream, sink: SendChannel<NodeFrame>, maxReassembledBytes: () -> Int, closeAtEnd: Boolean) {
        scope.launch {
            try {
                IrohFrameCodec.readAll(recv, MAX_FRAME_BYTES, maxReassembledBytesProvider = maxReassembledBytes) { text ->
                    val frame = decode(text)
                    if (frame == null) {
                        close(MALFORMED_FRAME)
                        return@readAll
                    }
                    sink.send(frame)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: IrohFrameCodec.ProtocolException) {
                close(NodeLinkClosure.LOST)
            } catch (_: Exception) {
                // The connection went away; the close watcher sets the closure.
            } finally {
                if (closeAtEnd) sink.close()
            }
        }
    }

    private fun decode(text: String): NodeFrame? = runCatching { NodeFrame.decode(text) }.getOrElse {
        Telemetry.event("IrohNodeCarrier", "frame.malformed", "bytes" attr text.length, level = Telemetry.Level.WARN)
        null
    }

    companion object {
        /** The largest plain Iroh frame, and the largest one `frame_part` piece. */
        const val MAX_FRAME_BYTES: Int = IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES

        /** Frames read ahead of the node on one lane, and call lanes accepted ahead of it, before the read waits. */
        const val LANE_BUFFER: Int = 8

        /** The closure for a frame that is not a JSON object. */
        val MALFORMED_FRAME: NodeLinkClosure = NodeLinkClosure(4400L, "malformed_frame")
    }
}

/**
 * One call lane over one QUIC bi-stream, from either end. Its reader starts at once and buffers up to
 * [IrohLinkCore.LANE_BUFFER] of the other end's frames until [incoming] is collected, then waits; [incoming]
 * completes when the other end finishes sending or the link ends.
 */
internal class IrohCallLane(
    private val core: IrohLinkCore,
    stream: BiStream,
    private val frameParts: () -> Boolean,
    maxReassembledBytes: () -> Int,
) : NodeCallLane {
    private val received = Channel<NodeFrame>(IrohLinkCore.LANE_BUFFER)
    private val send: SendStream = stream.send()
    private val writeLock = Mutex()

    @Volatile
    private var finished = false

    init {
        core.whenEnded { received.close() }
        core.read(stream.recv(), received, maxReassembledBytes, closeAtEnd = true)
    }

    override val incoming: Flow<NodeFrame> = received.receiveAsFlow()

    override suspend fun send(frame: NodeFrame) {
        if (finished) throw NodeCarrierException(NodeCarrierFailure.Closed(core.closure.value ?: NodeLinkClosure.NORMAL))
        core.write(send, writeLock, frame, frameParts())
    }

    override suspend fun finishSending() {
        if (finished) return
        finished = true
        writeLock.withLock { runCatching { send.finish() } }
    }
}

/**
 * Reads the application close code and reason out of the text the Iroh binding gives for a closed connection
 * (`closed by peer: auth_failures (code 4401)`). Only a close by the other end's application carries a numeric code;
 * anything else (a timeout, a reset, a transport error, a local endpoint shutdown) is [NodeLinkClosure.LOST].
 *
 * Text is all there is: the binding (`computer.iroh` 1.2.0, where these patterns were read) exposes a closed
 * connection only as `closed(): String` and `closeReason(): String`, with no code or reason values. A version bump
 * re-checks these patterns against `IrohCloseReasonsTest`, which covers every close the node makes.
 */
internal object IrohCloseReasons {
    private val withReason = Regex("""(?:closed|aborted) by peer: (.*) \(code (\d+)\)\s*$""", RegexOption.DOT_MATCHES_ALL)
    private val codeOnly = Regex("""(?:closed|aborted) by peer: (\d+)\s*$""")
    private val debugForm = Regex("""ApplicationClosed\(ApplicationClose \{ error_code: (\d+), reason: b"(.*)" \}\)""")

    /** The closure [reason] (the binding's `closed()` text) describes. */
    fun closureOf(reason: String?): NodeLinkClosure {
        val text = reason.orEmpty()
        withReason.find(text)?.let { match ->
            return closure(match.groupValues[2], match.groupValues[1])
        }
        codeOnly.find(text)?.let { match -> return closure(match.groupValues[1], "") }
        debugForm.find(text)?.let { match -> return closure(match.groupValues[1], match.groupValues[2]) }
        return NodeLinkClosure.LOST
    }

    private fun closure(code: String, reason: String): NodeLinkClosure =
        code.toLongOrNull()?.let { NodeLinkClosure(it, reason) } ?: NodeLinkClosure.LOST
}
