package com.letta.mobile.cli.commands

import com.letta.mobile.data.subagents.AppServerProcessIdentity
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Reads an operator-bound systemd MainPID file, never guesses a process from its name or sockets. */
internal class LinuxAppServerProcessIdentity(
    private val serverUrl: String?,
    private val pidFile: Path?,
    private val procRoot: Path = Path.of("/proc"),
) : AppServerProcessIdentity {
    override fun current(): String? {
        return runCatching {
            val url = URI(serverUrl ?: return null)
            if (url.scheme !in setOf("ws", "wss") || url.host !in LOOPBACK_HOSTS) return null
            val file = pidFile ?: return null
            val pid = Files.readString(file).trim().toLongOrNull()?.takeIf { it > 0 } ?: return null
            val boot = UUID.fromString(Files.readString(procRoot.resolve("sys/kernel/random/boot_id")).trim()).toString()
            val statFile = procRoot.resolve("$pid/stat")
            val start = startTime(Files.readString(statFile), pid) ?: return null
            // Fail closed if the PID file or process changed while we were reading it.
            if (Files.readString(file).trim() != pid.toString()) return null
            if (startTime(Files.readString(statFile), pid) != start) return null
            "$boot:$pid:$start"
        }.getOrNull()
    }

    private fun startTime(stat: String, pid: Long): Long? {
        if (stat.substringBefore(' ').toLongOrNull() != pid) return null
        val endOfName = stat.lastIndexOf(')')
        if (endOfName < 0) return null
        val fields = stat.substring(endOfName + 1).trim().split(Regex("\\s+"))
        if (fields.firstOrNull() in setOf("Z", "X", "x")) return null
        return fields.getOrNull(START_TIME_INDEX)?.toLongOrNull()?.takeIf { it > 0 }
    }

    private companion object {
        // Numeric loopback only: no DNS resolution or hostname trust at this boundary.
        val LOOPBACK_HOSTS = setOf("127.0.0.1", "::1", "[::1]")
        const val START_TIME_INDEX = 19 // /proc stat field 22, after removing pid and comm.
    }
}
