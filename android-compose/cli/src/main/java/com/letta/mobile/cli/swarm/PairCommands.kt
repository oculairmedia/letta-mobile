package com.letta.mobile.cli.swarm

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.letta.mobile.data.pairing.PairingError
import com.letta.mobile.data.pairing.PairingFlow
import com.letta.mobile.data.pairing.PairingState
import com.letta.mobile.data.swarm.SenderLabel
import com.letta.mobile.data.swarm.SwarmResult
import kotlinx.coroutines.runBlocking

/**
 * `meridian pair`: redeem a one-time invite as this machine's device, through the shared pairing
 * flow (local format/expiry check, host confirmation, redeem with the device's persistent key,
 * persist only on success). The invite is a bearer secret: read it from stdin, never echo it.
 */
class SwarmPairCommand(environment: SwarmCliEnvironment = SwarmCliEnvironment()) : SwarmCommand(
    "pair",
    "Pair this machine as a device using a one-time invite (create one in the Meridian app's pairing screen, or " +
        "`meridian peer invite --role chat --agents <agent-id> --ttl-ms 900000`). Pass `--invite -` to read it from stdin.",
    environment,
) {
    private val invite by option("--invite", help = "The invite link or code, or '-' to read it from stdin (recommended).").required()
    private val name by option("--name", help = "Device name the host records (default orchestrator-cli).").default(DEFAULT_NAME)
    private val yes by option("--yes", help = "Connect without asking to confirm the host id (required with --invite -).").flag()

    override fun run() = runBlocking {
        requireUnpaired()
        val deviceName = deviceName()
        val flow = pairingFlow(deviceName)
        submit(flow, readInvite())
        redeem(flow)
    }

    private suspend fun requireUnpaired() {
        if (profile.host() == null) return
        echo("error: already paired; run `meridian unpair` first", err = true)
        throw ProgramResult(SwarmExit.AlreadyPaired.code)
    }

    private fun deviceName(): String = SenderLabel.of(name)?.value ?: throw UsageError("--name has no printable characters")

    /** The invite, from stdin when `--invite -` (it then needs `--yes`: stdin cannot also answer the prompt). */
    private fun readInvite(): String {
        if (invite != STDIN) return invite
        if (!yes) throw UsageError("--invite - reads stdin, so the host cannot be confirmed interactively: add --yes")
        return readBounded(environment.stdin(), MAX_INVITE_CHARS)
    }

    private fun pairingFlow(deviceName: String) = PairingFlow(
        redeemer = environment.redeemer(profile),
        store = profile.hostStore(deviceName, environment.nowMs),
        deviceName = deviceName,
        nowMs = environment.nowMs,
    )

    /** Local format and expiry check, then the host confirmation; nothing is dialled before it. */
    private fun submit(flow: PairingFlow, raw: String) {
        when (val read = flow.submit(raw.trim())) {
            is PairingState.ConfirmHost -> confirmHost(read)
            is PairingState.Failed -> pairingFailed(read)
            else -> pairingFailed(PairingState.Failed(PairingError.MALFORMED))
        }
    }

    private suspend fun redeem(flow: PairingFlow) {
        when (val result = flow.confirm()) {
            is PairingState.Paired -> paired(result)
            is PairingState.Failed -> pairingFailed(result)
            else -> pairingFailed(PairingState.Failed(PairingError.DENIED))
        }
    }

    private fun confirmHost(host: PairingState.ConfirmHost) {
        echo("host: ${host.nodeId} (${host.hostShortId}); compare it with the inviting screen")
        if (yes) return
        echo("connect to this host? [y/N] ", trailingNewline = false)
        if (environment.readLine()?.trim()?.lowercase() !in setOf("y", "yes")) {
            echo("not paired", err = true)
            throw ProgramResult(SwarmExit.PairDeclined.code)
        }
    }

    private suspend fun paired(result: PairingState.Paired) {
        val nodeId = profile.nodeId()
        val again = if (result.alreadyPaired) " (this device was already paired; the invite was left unused)" else ""
        echo("paired with ${result.host.nodeId} as '${result.deviceName}'$again")
        echo("device node id: ${nodeId ?: "unknown"}")
        echo("profile: ${profile.directory}")
    }

    private fun pairingFailed(failed: PairingState.Failed): Nothing {
        val retry = if (failed.retryable) " (the same invite can be retried)" else ""
        echo("error: pairing failed: ${failed.error.name.lowercase()}$retry", err = true)
        throw ProgramResult(failed.error.exit.code)
    }

    private companion object {
        const val STDIN = "-"
        const val DEFAULT_NAME = "orchestrator-cli"
        const val MAX_INVITE_CHARS = 8_192
    }
}

/** `meridian unpair`: forget the pairing here and say which device to revoke on the host. */
class SwarmUnpairCommand(environment: SwarmCliEnvironment = SwarmCliEnvironment()) : SwarmCommand(
    "unpair",
    "Delete this machine's device key, pairing and outbox. The host keeps the device until it is revoked there.",
    environment,
) {
    override fun run() = runBlocking {
        val removed = profile.unpair()
        val nodeId = removed.nodeId
        if (nodeId == null) {
            echo("no device key in ${profile.directory}; nothing to remove")
            return@runBlocking
        }
        echo("removed the local pairing; device node id: $nodeId")
        echo("revoke it on the host: in the Meridian app's paired devices, or `meridian peer revoke --node-id $nodeId --ticket <host>`")
    }
}

/** `meridian agents list`: the agents this device may message. */
class AgentsListCommand(environment: SwarmCliEnvironment = SwarmCliEnvironment()) : SwarmCommand(
    "list",
    "List the agents in this device's scope (id and name).",
    environment,
) {
    override fun run() = runBlocking {
        when (val result = environment.messenger(profile).agents()) {
            is SwarmResult.Ok -> echo(if (output == OutputFormat.Json) result.value.toJson().printable() else result.value.toText())
            is SwarmResult.Failed -> failWith(result.failure, output)
        }
    }
}
