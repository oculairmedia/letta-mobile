package com.letta.mobile.data.node.host

import com.letta.mobile.data.subagents.AppServerProcessIdentity
import java.time.Instant

/**
 * The Windows binding: the App Server's PID plus that process's creation time, read through the
 * JDK's [ProcessHandle] (no `/proc`). Windows reuses PIDs quickly, so the creation time is what
 * makes the identity name one process: a reused PID reads as a different identity, and an exited
 * process as unknown.
 *
 * It uses only JDK APIs, so it also works wherever the JDK reports process start times.
 *
 * @param pid the App Server's PID, or null when there is none to prove (no child yet, a remote or
 *   unconfigured App Server). Read twice per call; a change in between fails closed.
 * @param startOf the creation time of the live process [pid], or null when no such process lives.
 */
internal class WindowsAppServerProcessIdentity(
    private val pid: () -> Long?,
    private val startOf: (Long) -> Instant? = ::liveProcessStart,
) : AppServerProcessIdentity {
    override fun current(): String? = runCatching {
        val id = pid()?.takeIf { it > 0 } ?: return null
        val start = startOf(id) ?: return null
        // Fail closed if the PID source or the process changed while we were reading it.
        if (pid() != id || startOf(id) != start) return null
        "pid:$id:started:$start"
    }.getOrNull()
}

/** The creation time of the live process [pid], from the OS process table; null when it is gone or unknown. */
internal fun liveProcessStart(pid: Long): Instant? = ProcessHandle.of(pid)
    .filter(ProcessHandle::isAlive)
    .flatMap { it.info().startInstant() }
    .orElse(null)
