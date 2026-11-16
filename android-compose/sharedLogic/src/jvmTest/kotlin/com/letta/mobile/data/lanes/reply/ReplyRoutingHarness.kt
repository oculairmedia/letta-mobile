package com.letta.mobile.data.lanes.reply

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.node.FakeAppServerController
import com.letta.mobile.data.controller.node.iroh.IrohConnectionAuthentication
import com.letta.mobile.data.controller.node.iroh.IrohNodeConnection
import com.letta.mobile.data.controller.node.iroh.IrohViewerHandle
import com.letta.mobile.data.controller.node.iroh.NodeTurnHost
import com.letta.mobile.data.controller.node.iroh.ObserverWriteQueue
import com.letta.mobile.data.controller.node.iroh.ProcessScopedClientMessageDedupe
import com.letta.mobile.data.controller.node.iroh.ViewerFrameSink
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.lanes.reply.persistence.fileReplyStore
import com.letta.mobile.data.messaging.IrohAgentMessageRouter
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.runtime.FakeAppServerClient
import com.letta.mobile.data.runtime.TurnInputAcknowledgement
import com.letta.mobile.data.runtime.reportInputAcknowledgement
import com.letta.mobile.data.runtime.toInputCommand
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerInputMessage
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.iroh.DeliveryOutcome
import com.letta.mobile.data.transport.iroh.IrohAgentIdentity
import com.letta.mobile.data.transport.iroh.IrohAgentMessage
import com.letta.mobile.data.transport.iroh.IrohAgentMessageReceiver
import com.letta.mobile.runtime.TurnCommand
import computer.iroh.Connection
import computer.iroh.NoHandle
import computer.iroh.SendStream
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonPrimitive

/** Calls the actual wrapper input handler and records the App Server client's actual inputs. */
internal class ReplyRoutingHarness(private val scope: CoroutineScope) : AutoCloseable {
    val directory = Files.createTempDirectory("reply-routing").toFile()
    var now = 1_800_000_000_000L
    var store = fileReplyStore(directory) { now }
    val senderEndpoint = IrohAgentIdentity.loadOrCreate(REPLIER, directory).nodeIdHex
    val otherEndpoint = IrohAgentIdentity.loadOrCreate(OTHER, directory).nodeIdHex
    val origin = ReplyOrigin(RECIPIENT, ORIGIN_CONVERSATION)
    val inputs = mutableListOf<AppServerCommand.Input>()
    var accepting = true
    var active = true
    var teleportPending = false
    var ledgerAvailable = true
    var queueAvailable = true
    private var sequence = 0

    // letta.js 0.34.39 routeInputFrame: active listener; validated create_message;
    // no approval payload; no teleport; admitted originator (these inputs have no
    // originator token); durable ledger + queue both accept. Unknown conversation
    // ids are admitted, and a busy conversation is queued. There is no resolver gate.
    val client: AppServerClient = object : AppServerClient by FakeAppServerClient() {
        override suspend fun inputAwaitingAcceptance(command: AppServerCommand.Input): AppServerInboundFrame.InputAccepted {
            val request = requireNotNull(command.requestId)
            require(command.runtime.agentId.isNotEmpty() && command.runtime.conversationId.isNotEmpty())
            val payload = command.payload as AppServerInputPayload.CreateMessage
            inputs += command
            val validImageMode = payload.imageFailureMode == null || payload.imageFailureMode in setOf("strict", "drop")
            val queueAdmits = payload.messages.isEmpty() || queueAvailable
            val accepted = listOf(accepting, active, !teleportPending, ledgerAvailable, queueAdmits, validImageMode).all { it }
            val disposition = if (payload.messages.isEmpty()) "started" else "queued"
            return AppServerInboundFrame.InputAccepted(request, command.runtime, accepted,
                if (accepted) disposition else null,
                if (accepted) null else "Input was rejected by the queue or durable ledger")
        }
    }

    fun receiver(): IrohAgentMessageReceiver = IrohAgentMessageReceiver(
        IrohAgentMessageRouter("host"),
        { listOf(IrohAgentMessageRouter.ConversationState(Conversation(ConversationId(INBOX),
            AgentId(RECIPIENT), tags = listOf("lane:inbox")), false)) },
        { message, _ ->
            val ack = client.inputAwaitingAcceptance(input(INBOX, message.body))
            DeliveryOutcome(false, if (ack.accepted) "application_input_accepted" else "application_input_rejected")
        },
        { message, peer -> when (message.fromAgentId) {
            REPLIER -> peer == senderEndpoint
            OTHER -> peer == otherEndpoint
            else -> false
        } },
        replies = store,
    )

    suspend fun receive(id: String?, body: String = "answer", sender: String = REPLIER): DeliveryOutcome =
        receiver().respond(IrohAgentMessage(sender, RECIPIENT, body, "reply-${sequence++}", now,
            correlationId = id), if (sender == REPLIER) senderEndpoint else otherEndpoint)

    suspend fun originInput(text: String, conversation: String = ORIGIN_CONVERSATION) {
        val controller = object : AppServerController by FakeAppServerController() {
            override fun runTurn(command: TurnCommand) = flow<com.letta.mobile.runtime.RuntimeEventDraft> {
                val runtime = AppServerRuntimeScope(command.agentId.value, command.conversationId.value)
                val ack = client.inputAwaitingAcceptance(command.toInputCommand(runtime, null).copy(requestId = "forwarded"))
                reportInputAcknowledgement(if (ack.accepted) TurnInputAcknowledgement.Queued
                    else TurnInputAcknowledgement.rejected(requireNotNull(ack.error)))
            }
        }
        val node = IrohNodeConnection(Connection(NoHandle), controller,
            authentication = IrohConnectionAuthentication(IrohAuthPolicy.InsecureAnonymousForTestOnly,
                remoteEndpointId = "fixture-peer"),
            clientMessageDedupe = ProcessScopedClientMessageDedupe(), turnHost = NodeTurnHost(scope))
        node.replies = store
        setField(node, "observerWrites", ObserverWriteQueue(scope))
        setField(node, "selfViewer", IrohViewerHandle("fixture-peer", object : ViewerFrameSink {
            override suspend fun writeAll(bytes: ByteArray) = Unit
        }, com.letta.mobile.data.controller.node.iroh.ConnectionEventSeq(1L), Mutex(), { false }, 1024 * 1024))
        val method = IrohNodeConnection::class.java.declaredMethods.single { it.name == "handleInput" }
        method.isAccessible = true
        suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
            try { method.invoke(node, input(conversation, text), SendStream(NoHandle), continuation) }
            catch (error: InvocationTargetException) { throw error.targetException }
        }
    }

    fun restart() { store = fileReplyStore(directory) { now } }

    fun input(conversation: String, body: String) = AppServerCommand.Input(
        requestId = "input-${sequence++}", runtime = AppServerRuntimeScope(RECIPIENT, conversation),
        payload = AppServerInputPayload.CreateMessage(messages = listOf(
            AppServerInputMessage("user", JsonPrimitive(body), clientMessageId = "user-${sequence++}"))))

    fun texts(): List<String> = inputs.map {
        ((it.payload as AppServerInputPayload.CreateMessage).messages.single().content as JsonPrimitive).content
    }

    override fun close() { directory.deleteRecursively() }

    private fun setField(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).also { it.isAccessible = true }.set(target, value)
    }

    companion object {
        const val RECIPIENT = "recipient"
        const val REPLIER = "replier"
        const val OTHER = "other"
        const val ORIGIN_CONVERSATION = "origin-thread"
        const val INBOX = "inbox-thread"
    }
}
