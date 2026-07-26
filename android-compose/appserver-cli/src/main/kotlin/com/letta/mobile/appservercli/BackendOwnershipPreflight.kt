package com.letta.mobile.appservercli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Ownership fencing preflight for a Letta local-backend root
 * (letta-mobile-lgns8.14).
 *
 * Runs BEFORE acquiring the [LocalBackendLock] to answer "is it safe to become
 * the writer of this backend root?" The lock alone cannot say *who* currently
 * owns the root or whether a recorded owner is stale — this preflight does, by
 * reconciling the `.owner.json` sidecar against live-process evidence:
 *
 *  - No sidecar / unreadable sidecar → [Decision.Clear] (safe; nothing owns it).
 *  - Sidecar names a PID that is alive AND whose start time matches → a real
 *    competing writer → [Decision.HeldByLiveOwner] (must NOT start; fence).
 *  - Sidecar names a dead PID, or a live PID whose start time differs (PID
 *    reuse) → stale, non-authoritative → [Decision.StaleReclaimable].
 *  - Sidecar names THIS process → [Decision.HeldBySelf] (idempotent).
 *
 * The caller uses the decision to enforce stop-before-start / rollback fencing:
 * only [Clear], [StaleReclaimable], and [HeldBySelf] may proceed to take the
 * lock; [HeldByLiveOwner] fails closed.
 */
class BackendOwnershipPreflight(
    private val liveness: ProcessLiveness = SystemProcessLiveness,
    private val selfPid: Long = ProcessHandle.current().pid(),
) {
    fun evaluate(backendDir: String): Decision =
        evaluate(Paths.get(expandTilde(backendDir)))

    fun evaluate(backendDir: Path): Decision {
        val sidecar = backendDir.resolve(OWNER_FILENAME)
        if (!Files.exists(sidecar)) return Decision.Clear
        val raw = try {
            Files.readString(sidecar)
        } catch (_: Exception) {
            return Decision.StaleReclaimable(reason = "owner sidecar unreadable")
        }
        val info = BackendOwnerInfo.fromJson(raw)
            ?: return Decision.StaleReclaimable(reason = "owner sidecar malformed")

        if (info.pid == selfPid) return Decision.HeldBySelf(info)

        val actualStart = liveness.startTimeMsOf(info.pid)
        return when {
            actualStart == null ->
                Decision.StaleReclaimable(reason = "recorded owner pid ${info.pid} is not alive", previous = info)
            actualStart != info.startTimeMs ->
                Decision.StaleReclaimable(
                    reason = "pid ${info.pid} reused (start time ${actualStart} != recorded ${info.startTimeMs})",
                    previous = info,
                )
            else -> Decision.HeldByLiveOwner(info)
        }
    }

    /**
     * Acquire ownership: fence via [evaluate], and if permitted, write a fresh
     * canonical sidecar and take the exclusive [LocalBackendLock].
     *
     * @throws BackendCompetingWriterException if a live competing writer exists.
     * @throws BackendAlreadyOwnedException if the lock is held (belt-and-braces:
     *   the FileLock is the final arbiter even if the sidecar said Clear).
     */
    fun acquire(
        backendDir: String,
        unit: String? = null,
        nowIso: () -> String = { java.time.Instant.now().toString() },
    ): FencedOwnership {
        val dir = Paths.get(expandTilde(backendDir))
        Files.createDirectories(dir)
        when (val decision = evaluate(dir)) {
            is Decision.HeldByLiveOwner ->
                throw BackendCompetingWriterException(dir, decision.owner)
            else -> Unit // Clear / StaleReclaimable / HeldBySelf may proceed
        }
        // The FileLock is the true mutual-exclusion primitive; the sidecar is
        // advisory diagnostics. Take the lock FIRST so two racing preflights that
        // both saw "Clear" cannot both proceed.
        val lock = LocalBackendLock.acquire(dir)
        val info = BackendOwnerInfo(
            pid = selfPid,
            startTimeMs = liveness.startTimeMsOf(selfPid) ?: 0L,
            backendDir = dir.toString(),
            unit = unit,
            acquiredAtIso = nowIso(),
        )
        Files.writeString(dir.resolve(OWNER_FILENAME), info.toJson())
        return FencedOwnership(lock, dir.resolve(OWNER_FILENAME), info)
    }

    sealed interface Decision {
        /** Nothing owns the root — safe to start. */
        data object Clear : Decision
        /** A live process is actively the writer — must not start. */
        data class HeldByLiveOwner(val owner: BackendOwnerInfo) : Decision
        /** Recorded owner is dead/PID-reused/malformed — safe to reclaim. */
        data class StaleReclaimable(val reason: String, val previous: BackendOwnerInfo? = null) : Decision
        /** This very process already owns it — idempotent. */
        data class HeldBySelf(val owner: BackendOwnerInfo) : Decision

        val mayProceed: Boolean
            get() = this is Clear || this is StaleReclaimable || this is HeldBySelf
    }

    companion object {
        const val OWNER_FILENAME = ".owner.json"

        private fun expandTilde(path: String): String {
            if (path == "~") return System.getProperty("user.home")
            if (path.startsWith("~/")) return System.getProperty("user.home") + path.substring(1)
            return path
        }
    }
}

/**
 * Bundles the exclusive lock and the ownership sidecar; releasing clears the
 * sidecar so a clean shutdown leaves no stale ownership metadata.
 */
class FencedOwnership internal constructor(
    private val lock: LocalBackendLock,
    private val ownerFile: Path,
    val info: BackendOwnerInfo,
) : AutoCloseable {
    override fun close() {
        // Remove the sidecar first so a crash between the two ops leaves a stale
        // (reclaimable) sidecar rather than a released lock with a live-looking
        // owner record.
        runCatching { Files.deleteIfExists(ownerFile) }
        lock.close()
    }
}

/** Process liveness/identity, injected so the preflight is unit-testable. */
interface ProcessLiveness {
    /** The start time (ms since epoch) of [pid], or null if not alive. */
    fun startTimeMsOf(pid: Long): Long?
}

/** Real liveness via [ProcessHandle]. */
object SystemProcessLiveness : ProcessLiveness {
    override fun startTimeMsOf(pid: Long): Long? {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return null
        if (!handle.isAlive) return null
        val start = handle.info().startInstant().orElse(null) ?: return 0L
        return start.toEpochMilli()
    }
}

/** Thrown when a live competing writer already owns the backend root. */
class BackendCompetingWriterException(
    backendDir: Path,
    owner: BackendOwnerInfo,
) : java.io.IOException(
    "local backend '$backendDir' has a live competing writer " +
        "(pid=${owner.pid}, unit=${owner.unit ?: "?"}, since=${owner.acquiredAtIso}); refusing stop-before-start fence violation",
)
