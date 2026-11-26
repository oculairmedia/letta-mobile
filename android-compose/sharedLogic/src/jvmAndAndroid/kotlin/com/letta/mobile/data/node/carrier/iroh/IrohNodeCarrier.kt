package com.letta.mobile.data.node.carrier.iroh

import com.letta.mobile.data.node.carrier.NodeAddress
import com.letta.mobile.data.node.carrier.NodeCallLane
import com.letta.mobile.data.node.carrier.NodeCarrier
import com.letta.mobile.data.node.carrier.NodeCarrierException
import com.letta.mobile.data.node.carrier.NodeCarrierFailure
import com.letta.mobile.data.node.carrier.NodeFrame
import com.letta.mobile.data.node.carrier.NodeInbound
import com.letta.mobile.data.node.carrier.NodeLane
import com.letta.mobile.data.node.carrier.NodeLink
import com.letta.mobile.data.node.carrier.NodeLinkClosure
import com.letta.mobile.data.node.carrier.NodePeerIdentity
import com.letta.mobile.data.transport.iroh.IrohAppServerTransportAdapter
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.data.transport.iroh.IrohDiagnostics
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.SendStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout

/**
 * The Iroh carrier: reaches a node over QUIC from [endpoint], with the ALPN and stream layout the node already serves
 * (first bi-stream control, second push, every later one an `admin_rpc` call lane) and the frame codec the App Server
 * transport uses, so a node needs no change to serve it. Frames over one Iroh frame are split into `frame_part`s and
 * joined again inside the carrier; nobody above it sees them.
 *
 * Addresses are `iroh://<ticket>`, a bare ticket, `<node id hex>@host:port[,host:port]` or a bare node id hex. The
 * link's coroutines run in [scope]; the caller owns [endpoint] and closes it.
 */
class IrohNodeCarrier(
    private val endpoint: Endpoint,
    private val scope: CoroutineScope,
    private val alpn: ByteArray = IrohAppServerTransportAdapter.DEFAULT_ALPN,
    /** How long a dial may take before the node counts as unreachable. */
    private val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
) : NodeCarrier {
    override suspend fun dial(address: NodeAddress): NodeLink {
        val remote = parse(address)
        val connection = connect(remote)
        return try {
            IrohNodeLink.open(connection, scope)
        } catch (error: CancellationException) {
            abandon(connection)
            throw error
        } catch (error: Exception) {
            abandon(connection)
            throw unreachable("streams did not open", error)
        }
    }

    private fun abandon(connection: Connection) {
        runCatching { connection.close(NodeLinkClosure.NORMAL.code, NodeLinkClosure.NORMAL.reason.encodeToByteArray()) }
    }

    private fun parse(address: NodeAddress): EndpointAddr = try {
        IrohAppServerTransportAdapter.parseIrohAddress(IrohChannelTransport.normalizeIrohAddress(address.value))
    } catch (error: IllegalArgumentException) {
        throw unreachable("not an Iroh address", error)
    }

    private suspend fun connect(remote: EndpointAddr): Connection = try {
        withTimeout(connectTimeout) { endpoint.connect(remote, alpn) }
    } catch (error: TimeoutCancellationException) {
        throw unreachable("connect timed out", error)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw unreachable("connect failed", error)
    }

    private fun unreachable(detail: String, cause: Throwable): NodeCarrierException {
        Telemetry.event(
            "IrohNodeCarrier", "dial.unreachable",
            "detail" attr detail,
            "class" attr cause::class.simpleName,
            level = Telemetry.Level.WARN,
        )
        return NodeCarrierException(NodeCarrierFailure.Unreachable("$detail: ${cause::class.simpleName}"))
    }

    companion object {
        /** The default bound on one dial. */
        val DEFAULT_CONNECT_TIMEOUT: Duration = 30.seconds
    }
}

/**
 * The shell's end of one Iroh link: the control bi-stream, the push bi-stream (whose send half is finished at once,
 * as the node expects), and one bi-stream per call. The shell may always write `frame_part`s: every node decodes them.
 */
internal class IrohNodeLink private constructor(
    private val connection: Connection,
    private val core: IrohLinkCore,
    control: BiStream,
    push: BiStream,
) : NodeLink {
    private val controlSend: SendStream = control.send()
    private val controlLock = Mutex()
    private val controlFrames = Channel<NodeFrame>(Channel.UNLIMITED)
    private val pushFrames = Channel<NodeFrame>(Channel.UNLIMITED)

    init {
        core.whenEnded {
            controlFrames.close()
            pushFrames.close()
        }
        core.read(control.recv(), controlFrames, { MAX_REASSEMBLED_BYTES }, closeAtEnd = false)
        core.read(push.recv(), pushFrames, { MAX_REASSEMBLED_BYTES }, closeAtEnd = false)
        core.watchConnection()
    }

    override val node: NodePeerIdentity = NodePeerIdentity(IrohDiagnostics.endpointIdHex(connection.remoteId()))

    override val inbound: Flow<NodeInbound> = merge(
        controlFrames.receiveAsFlow().map { NodeInbound(NodeLane.CONTROL, it) },
        pushFrames.receiveAsFlow().map { NodeInbound(NodeLane.PUSH, it) },
    )

    override val closure: StateFlow<NodeLinkClosure?> get() = core.closure

    override suspend fun sendControl(frame: NodeFrame) = core.write(controlSend, controlLock, frame, frameParts = true)

    override suspend fun openCall(request: NodeFrame): NodeCallLane {
        if (core.closure.value != null) throw core.closed()
        val stream = try {
            connection.openBi()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw core.closed()
        }
        val lane = IrohCallLane(core, stream, frameParts = { true }, maxReassembledBytes = { MAX_REASSEMBLED_BYTES })
        lane.send(request)
        return lane
    }

    override suspend fun close(closure: NodeLinkClosure) = core.close(closure)

    companion object {
        private const val MAX_REASSEMBLED_BYTES = IrohFrameCodec.DEFAULT_MAX_REASSEMBLED_BYTES

        /** Opens the control and push lanes on [connection] and starts reading them. Sends no frame. */
        suspend fun open(connection: Connection, scope: CoroutineScope): IrohNodeLink {
            val control = connection.openBi()
            val push = connection.openBi()
            push.send().finish()
            return IrohNodeLink(connection, IrohLinkCore(connection, scope), control, push)
        }
    }
}
