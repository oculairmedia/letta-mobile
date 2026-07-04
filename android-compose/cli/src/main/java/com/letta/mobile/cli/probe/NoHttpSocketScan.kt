package com.letta.mobile.cli.probe

import java.io.File

/**
 * Per-process TCP socket scan for the `no-http` probe scenario (letta-mobile-qfa81).
 *
 * The headline iroh purity invariant: while the probe runs against an `iroh://`
 * backend, the CLIENT process must open ZERO TCP connections to the admin HTTP
 * port (:8291 by default). `/proc/net/tcp` alone is namespace-wide, so this scan
 * joins it with `/proc/self/fd` socket inodes to count only sockets owned by
 * this process.
 *
 * Returns null on platforms without procfs (macOS, Windows) so callers can
 * degrade to a skip-note instead of a false red.
 */
object NoHttpSocketScan {
    fun connectionsToPort(port: Int, procRoot: String = "/proc"): Int? {
        val fdDir = File(procRoot, "self/fd")
        if (!fdDir.isDirectory) return null
        val inodes = parseSocketInodes(fdDir)
        val tcpLines = buildList {
            listOf("net/tcp", "net/tcp6").forEach { rel ->
                val file = File(procRoot, "self/$rel")
                    .takeIf { it.canRead() }
                    ?: File(procRoot, rel).takeIf { it.canRead() }
                if (file != null) addAll(runCatching { file.readLines() }.getOrDefault(emptyList()))
            }
        }
        if (tcpLines.isEmpty()) return null
        return countMatches(inodes, tcpLines, port)
    }

    internal fun parseSocketInodes(fdDir: File): Set<Long> =
        fdDir.listFiles().orEmpty().mapNotNullTo(mutableSetOf()) { fd ->
            val target = runCatching { java.nio.file.Files.readSymbolicLink(fd.toPath()).toString() }.getOrNull()
            parseSocketInode(target)
        }

    internal fun parseSocketInode(linkTarget: String?): Long? {
        if (linkTarget == null || !linkTarget.startsWith("socket:[")) return null
        return linkTarget.removePrefix("socket:[").removeSuffix("]").toLongOrNull()
    }

    /**
     * Counts `/proc/net/tcp{,6}` entries whose REMOTE port is [port] and whose
     * socket inode belongs to this process ([fdSocketInodes]).
     */
    internal fun countMatches(fdSocketInodes: Set<Long>, procNetTcpLines: List<String>, port: Int): Int =
        procNetTcpLines.count { line ->
            val fields = line.trim().split(Regex("\\s+"))
            // sl local_address rem_address st tx_queue:rx_queue tr:tm->when retrnsmt uid timeout inode
            if (fields.size < 10 || !fields[0].endsWith(":")) return@count false
            val remotePort = fields[2].substringAfter(':', "").toIntOrNull(16) ?: return@count false
            val inode = fields[9].toLongOrNull() ?: return@count false
            remotePort == port && inode in fdSocketInodes
        }
}
