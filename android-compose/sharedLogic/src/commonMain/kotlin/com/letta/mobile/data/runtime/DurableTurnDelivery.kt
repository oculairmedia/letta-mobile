package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.fanout.RuntimeSessionLink
import com.letta.mobile.data.plugin.PluginSemVer
import com.letta.mobile.data.transport.appserver.AppServerClientPreferences
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInfoData
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerTerminalConsumerId
import com.letta.mobile.data.transport.appserver.AppServerToolsetPreference
import com.letta.mobile.runtime.TurnCommand
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * letta-mobile-zxk2n.2: what a host's user inputs carry of letta-code 0.34's delivery contract.
 *
 * - [consumerId] names this client in `input.terminal_consumer_id`. The server then keeps the
 *   turn's `turn_finished` until this client answers `turn_finished_ack`, replays it after a
 *   reconnect, and recognises a re-sent input by its `client_message_id` instead of running it
 *   twice. Null keeps every turn on the pre-0.34 path. It must be stable for the install and the
 *   client surface: a terminal kept for an id that never comes back waits out the server's TTL.
 * - [clientPreferencesFor] is the conversation's `client_preferences`; null inherits the
 *   conversation's stored preferences.
 *
 * Each field is sent only to a server whose `app_server_info` names a letta-code version that
 * understands it ([LettaCodeDeliveryGates]). Missing, unreadable or earlier advertised versions
 * omit that field. This gate does not certify an older release as supported or identify the
 * version currently deployed on any host.
 */
class DurableTurnDelivery(
    val consumerId: AppServerTerminalConsumerId? = null,
    val clientPreferencesFor: (TurnCommand) -> AppServerClientPreferences? = { null },
) {
    /** The fields [command]'s input carries for [server]. */
    internal fun fieldsFor(server: AppServerInfoData?, command: TurnCommand): DurableInputFields = DurableInputFields(
        consumerId = consumerId?.takeIf { LettaCodeDeliveryGates.supportsDurableTerminal(server) },
        clientPreferences = clientPreferencesFor(command)?.takeIf { LettaCodeDeliveryGates.supportsClientPreferences(server) },
    )

    companion object {
        val OFF: DurableTurnDelivery = DurableTurnDelivery()

        /**
         * The preferences of a chat surface: it offers `AskUserQuestion`, which every shared chat
         * surface renders. Sent on every input, as the reference desktop does, so a preference
         * another client left on the conversation does not take the question tool away.
         */
        val CHAT_CLIENT_PREFERENCES: AppServerClientPreferences =
            AppServerClientPreferences(AppServerToolsetPreference(listOf("AskUserQuestion")))

        /** A chat surface's delivery: durable terminals under [consumerId], and [CHAT_CLIENT_PREFERENCES]. */
        fun chat(consumerId: AppServerTerminalConsumerId?): DurableTurnDelivery =
            DurableTurnDelivery(consumerId) { CHAT_CLIENT_PREFERENCES }
    }
}

/** The 0.34 fields one input carries; [consumerId] null means the host has no durable terminal. */
internal data class DurableInputFields(
    val consumerId: AppServerTerminalConsumerId?,
    val clientPreferences: AppServerClientPreferences?,
) {
    /** The host keeps this input's identity and terminal across a dropped link. */
    val isResumable: Boolean get() = consumerId != null

    companion object {
        val NONE: DurableInputFields = DurableInputFields(consumerId = null, clientPreferences = null)
    }
}

/** [this] input carrying [fields]: the consumer id on the input, the preferences on a `create_message`. */
internal fun AppServerCommand.Input.withDurableFields(fields: DurableInputFields): AppServerCommand.Input {
    if (fields == DurableInputFields.NONE) return this
    val message = payload as? AppServerInputPayload.CreateMessage ?: return this
    return copy(
        payload = message.copy(clientPreferences = fields.clientPreferences),
        terminalConsumerId = fields.consumerId?.value,
    )
}

/** letta-mobile-zxk2n.2: what [TurnInputSender] needs for 0.34 delivery; the defaults send nothing new. */
internal class DurableInputSending(
    val delivery: DurableTurnDelivery = DurableTurnDelivery.OFF,
    val resumption: InputResumption = InputResumption(sessionLink = null),
)

/**
 * letta-mobile-zxk2n.2: waits for a dropped session to come back so an unacknowledged input can be
 * sent again. Without a [sessionLink] (a direct client that never reconnects) nothing is waited for.
 */
internal class InputResumption(
    private val sessionLink: StateFlow<RuntimeSessionLink>?,
    private val graceMs: Long = SESSION_RECONNECT_GRACE_MS,
) {
    /** The session's restore count now; null without a link. */
    fun epoch(): Long? = sessionLink?.value?.epoch

    /** True once the session is linked again after [epoch], within [graceMs]; false without a link. */
    suspend fun awaitRestoredAfter(epoch: Long?): Boolean {
        val link = sessionLink ?: return false
        val since = epoch ?: return false
        return withTimeoutOrNull(graceMs.milliseconds) {
            link.first { it is RuntimeSessionLink.Linked && it.epoch > since }
        } != null
    }
}

/** The letta-code versions the 0.34 delivery fields need (README: "gated on an advertised capability"). */
internal object LettaCodeDeliveryGates {
    /** `client_preferences` on `create_message` (letta-code 0.34.0). */
    private val CLIENT_PREFERENCES = PluginSemVer(major = 0, minor = 34, patch = 0)

    /** `input.terminal_consumer_id` with `turn_finished_ack` (letta-code 0.34.8). */
    private val DURABLE_TERMINAL = PluginSemVer(major = 0, minor = 34, patch = 8)

    fun supportsClientPreferences(server: AppServerInfoData?): Boolean = server.isAtLeast(CLIENT_PREFERENCES)

    fun supportsDurableTerminal(server: AppServerInfoData?): Boolean = server.isAtLeast(DURABLE_TERMINAL)

    /** False without a handshake or with an unreadable version: nothing new is sent then. */
    private fun AppServerInfoData?.isAtLeast(minimum: PluginSemVer): Boolean {
        val version = this?.lettaCodeVersion?.trim()?.let(PluginSemVer::parse) ?: return false
        return version >= minimum
    }
}
