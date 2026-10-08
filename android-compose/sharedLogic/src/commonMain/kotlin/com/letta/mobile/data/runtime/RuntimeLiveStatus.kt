package com.letta.mobile.data.runtime

import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus

/**
 * letta-mobile-bzvro.7 / .8 (F07, F08): what the shared chat page's status line says about the
 * running turn, beyond "a run is in flight": the loop's phase, a provider retry in progress, the
 * server's latest status message, and the server-side commands it is running or just ran.
 *
 * Reduced from runtime events by [RuntimeLiveStatusReducer] alone, so both platforms tell the same
 * story. Presentation-only: nothing durable reads it.
 */
data class RuntimeLiveStatus(
    /** The loop's phase; null when no turn is running (or the loop is back waiting on input). */
    val phase: LoopPhase? = null,
    /** A provider retry in progress; cleared once the loop moves past [LoopPhase.Retrying]. */
    val retry: LiveRetry? = null,
    /** The server's latest `status` message for this turn. */
    val notice: LiveNotice? = null,
    /** Server-side commands, newest last, at most [RuntimeLiveStatusReducer.MAX_COMMANDS]. */
    val commands: List<CommandActivity> = emptyList(),
) {
    /** Nothing to show. */
    val isEmpty: Boolean get() = phase == null && retry == null && notice == null && commands.isEmpty()

    companion object {
        val Idle = RuntimeLiveStatus()
    }
}

/** One provider retry, as a `retry` delta described it. [atEpochMs] is when it arrived. */
data class LiveRetry(
    val attempt: Int,
    val maxAttempts: Int,
    val delayMs: Long,
    val reason: String? = null,
    val message: String? = null,
    val retryKind: String? = null,
    val provider: String? = null,
    val errorCode: String? = null,
    val atEpochMs: Long = 0L,
) {
    /** When the retry fires; the status line counts down to it. */
    val retryAtEpochMs: Long get() = atEpochMs + delayMs.coerceAtLeast(0L)
}

data class LiveNotice(val message: String, val level: NoticeLevel)

enum class NoticeLevel {
    Info,
    Success,
    Warning,
    ;

    companion object {
        fun fromWire(level: String?): NoticeLevel = when (level?.lowercase()) {
            "success" -> Success
            "warning", "warn", "error" -> Warning
            else -> Info
        }
    }
}

/** A server-side command (`/compact`, `/doctor`, a harness bash command) and how it went. */
data class CommandActivity(
    val commandId: String,
    val input: String,
    val slash: Boolean,
    val state: CommandState,
    val output: String? = null,
    val dimOutput: Boolean = false,
    val preformatted: Boolean = false,
) {
    val running: Boolean get() = state == CommandState.Running
}

enum class CommandState { Running, Succeeded, Failed }

/** The one place runtime events become a [RuntimeLiveStatus]. Pure: the caller passes the clock. */
object RuntimeLiveStatusReducer {
    const val MAX_COMMANDS: Int = 3

    fun reduce(state: RuntimeLiveStatus, event: RuntimeEventPayload, nowMs: Long): RuntimeLiveStatus = when (event) {
        is RuntimeEventPayload.LoopPhaseChanged -> state.onPhase(LoopPhase.fromWire(event.status))
        is RuntimeEventPayload.RetryNotice -> state.copy(phase = LoopPhase.Retrying, retry = event.toLiveRetry(nowMs))
        is RuntimeEventPayload.StatusNotice -> event.message.takeIf { it.isNotBlank() }
            ?.let { state.copy(notice = LiveNotice(it, NoticeLevel.fromWire(event.level))) }
            ?: state
        is RuntimeEventPayload.CommandStarted -> state.withCommand(event.toActivity())
        is RuntimeEventPayload.CommandFinished -> state.withCommand(event.toActivity())
        is RuntimeEventPayload.RunLifecycleChanged -> state.onLifecycle(event.status)
        // A new message moves the conversation on: the last turn's finished commands are history.
        is RuntimeEventPayload.LocalUserAppend -> state.copy(
            retry = null,
            notice = null,
            commands = state.commands.filter { it.running },
        )
        else -> state
    }

    private fun RuntimeLiveStatus.onPhase(phase: LoopPhase): RuntimeLiveStatus = when {
        phase.isIdle -> copy(phase = null, retry = null, notice = null)
        phase == LoopPhase.Retrying -> copy(phase = phase)
        else -> copy(phase = phase, retry = null)
    }

    private fun RuntimeLiveStatus.onLifecycle(status: RuntimeRunStatus): RuntimeLiveStatus = when (status) {
        RuntimeRunStatus.Started -> copy(retry = null, notice = null)
        RuntimeRunStatus.Running -> this
        RuntimeRunStatus.Completed,
        RuntimeRunStatus.Failed,
        RuntimeRunStatus.Cancelled,
        -> copy(phase = null, retry = null, notice = null)
    }

    /** Replaces the command with the same id (start → end), else appends it; keeps the newest few. */
    private fun RuntimeLiveStatus.withCommand(activity: CommandActivity): RuntimeLiveStatus {
        val others = commands.filterNot { it.commandId == activity.commandId }
        return copy(commands = (others + activity).takeLast(MAX_COMMANDS))
    }

    private fun RuntimeEventPayload.RetryNotice.toLiveRetry(nowMs: Long) = LiveRetry(
        attempt = attempt,
        maxAttempts = maxAttempts,
        delayMs = delayMs,
        reason = reason,
        message = message,
        retryKind = retryKind,
        provider = provider,
        errorCode = errorCode,
        atEpochMs = nowMs,
    )

    private fun RuntimeEventPayload.CommandStarted.toActivity() = CommandActivity(
        commandId = commandId,
        input = input,
        slash = slash,
        state = CommandState.Running,
    )

    private fun RuntimeEventPayload.CommandFinished.toActivity() = CommandActivity(
        commandId = commandId,
        input = input,
        slash = slash,
        state = if (success) CommandState.Succeeded else CommandState.Failed,
        output = output,
        dimOutput = dimOutput,
        preformatted = preformatted,
    )
}
