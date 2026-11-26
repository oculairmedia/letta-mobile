package com.letta.mobile.data.node.host

import com.letta.mobile.data.controller.extras.IrohCliPaths
import com.letta.mobile.data.controller.extras.IrohCliRunner
import com.letta.mobile.data.controller.extras.IrohCliSendResult
import com.letta.mobile.data.transport.iroh.AgentSendResult
import com.letta.mobile.data.transport.iroh.IrohAgentMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Runs `agent_message_send` inside the node's own process instead of spawning a separate
 * `meridian` binary: the Windows binding, where no such binary is deployed. It sends through
 * [AgentMessageDelivery], the same path the `meridian agent-message send` command takes, so the tool
 * answers the same way whichever runner a host picked. The configured binary is ignored.
 *
 * Never throws (the tool must always answer): any failure becomes [IrohCliSendResult.Failed].
 *
 * @param deliver sends one message with the identity directory and address book in the paths.
 * @param clock the message timestamp, in epoch milliseconds.
 */
internal class InProcessAgentMessageRunner(
    private val deliver: suspend (IrohAgentMessage, IrohCliPaths) -> AgentSendResult = ::deliverOverIroh,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : IrohCliRunner {
    // The SPI's signature is fixed; the names differ only to keep raw string ids out of new code.
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override suspend fun send(
        binary: String,
        sender: String,
        recipient: String,
        body: String,
        paths: IrohCliPaths,
    ): IrohCliSendResult = sendMessage(envelope(sender, recipient, body, paths.message), paths)

    private fun envelope(
        sender: String,
        recipient: String,
        body: String,
        options: com.letta.mobile.data.controller.extras.messaging.IrohMessageOptions,
    ) = IrohAgentMessage(
        fromAgentId = sender,
        toAgentId = recipient,
        body = body,
        // Keep a caller-supplied idempotency id across retries; otherwise mint one.
        msgId = options.msgId ?: "msg-${UUID.randomUUID()}",
        ts = clock(),
        conversationId = options.conversationId,
        lane = options.lane.takeIf { options.conversationId == null },
        correlation = options.correlation,
    )

    private suspend fun sendMessage(message: IrohAgentMessage, paths: IrohCliPaths): IrohCliSendResult = try {
        deliver(message, paths).toCliResult()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        IrohCliSendResult.Failed(message.toAgentId, "in_process_send_failed: ${e.message ?: e::class.simpleName}")
    }
}

/** The CLI runner's typed outcome for this delivery result, one for one. */
internal fun AgentSendResult.toCliResult(): IrohCliSendResult = when (this) {
    is AgentSendResult.Delivered -> IrohCliSendResult.Delivered(msgId)
    is AgentSendResult.Accepted -> IrohCliSendResult.Accepted(msgId, toAgentId, reason)
    is AgentSendResult.Unaddressable -> IrohCliSendResult.Unaddressable(toAgentId, reason)
    is AgentSendResult.Failed -> IrohCliSendResult.Failed(toAgentId, reason)
}

/** Delivers over Iroh with the paths' identity directory and address book, or the CLI's defaults. */
private suspend fun deliverOverIroh(message: IrohAgentMessage, paths: IrohCliPaths): AgentSendResult =
    withContext(Dispatchers.IO) {
        AgentMessageDelivery.send(
            message = message,
            identityDir = paths.identityDir?.let(::File) ?: AgentMessageDelivery.defaultIdentityDir(),
            addressStore = paths.addressStore?.let(::File) ?: AgentMessageDelivery.defaultAddressStore(),
        )
    }
