package com.letta.mobile.cli.swarm

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.letta.mobile.data.pairing.PairingRedeemer
import com.letta.mobile.data.swarm.FileSwarmOutbox
import com.letta.mobile.data.swarm.IrohSwarmLink
import com.letta.mobile.data.swarm.SwarmDeviceProfile
import com.letta.mobile.data.swarm.SwarmMessenger
import com.letta.mobile.data.transport.iroh.IrohPairingRedeemer
import java.io.InputStream
import java.io.Reader
import java.nio.file.Path
import kotlin.time.Clock

/**
 * letta-mobile-jvctq: what the swarm commands take from the machine. Production binds the Iroh
 * link, the file outbox and the real stdin; tests substitute fakes. Feature logic lives in
 * sharedLogic ([SwarmMessenger], the shared pairing flow); the commands only parse and print.
 */
class SwarmCliEnvironment(
    val messenger: (SwarmDeviceProfile) -> SwarmMessenger = { profile ->
        SwarmMessenger(IrohSwarmLink(profile), FileSwarmOutbox(profile.outboxDirectory))
    },
    val redeemer: (SwarmDeviceProfile) -> PairingRedeemer = { profile -> IrohPairingRedeemer(profile.identity) },
    val stdin: () -> InputStream = { System.`in` },
    val readLine: () -> String? = ::readlnOrNull,
    val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    val defaultProfileDirectory: () -> Path = { defaultSwarmProfileDirectory(System.getenv(), System.getProperty("user.home")) },
)

/** `%APPDATA%\Meridian\cli` on Windows, `~/.letta/meridian-cli` elsewhere: the user profile, never the repo. */
fun defaultSwarmProfileDirectory(environment: Map<String, String>, userHome: String): Path =
    environment["APPDATA"]?.takeIf { it.isNotBlank() }?.let { Path.of(it, "Meridian", "cli") }
        ?: Path.of(userHome, ".letta", "meridian-cli")

enum class OutputFormat { Text, Json }

/** Base of the swarm commands: the profile directory and output format they all share. */
abstract class SwarmCommand(
    name: String,
    private val helpText: String,
    protected val environment: SwarmCliEnvironment,
) : CliktCommand(name = name) {
    override fun help(context: Context): String = helpText

    private val profileDirectory by option(
        "--profile-dir",
        envvar = "MERIDIAN_CLI_HOME",
        help = "Where this device's key, pairing and outbox live (default: %APPDATA%\\Meridian\\cli or ~/.letta/meridian-cli).",
    )

    protected val output by option("--output", help = "text (default) or json.")
        .enum<OutputFormat> { it.name.lowercase() }
        .default(OutputFormat.Text)

    protected val profile: SwarmDeviceProfile by lazy {
        SwarmDeviceProfile(profileDirectory?.let { Path.of(it) } ?: environment.defaultProfileDirectory())
    }
}

/** A group command (`agents`, `message`) with no behaviour of its own. */
class SwarmGroupCommand(name: String, private val helpText: String) : CliktCommand(name = name) {
    override fun help(context: Context): String = helpText

    override fun run() = Unit
}

/**
 * Reads at most [maxChars] characters of [stream] as UTF-8; more is a usage error, so an oversized
 * input is refused before it is held in memory whole.
 */
fun readBounded(stream: InputStream, maxChars: Int): String {
    val reader: Reader = stream.reader(Charsets.UTF_8)
    val buffer = CharArray(maxChars + 1)
    var filled = 0
    while (filled < buffer.size) {
        val read = reader.read(buffer, filled, buffer.size - filled)
        if (read < 0) break
        filled += read
    }
    if (filled > maxChars) throw UsageError("input is longer than $maxChars characters")
    return String(buffer, 0, filled)
}
