package com.letta.mobile.data.controller.channels

import com.letta.mobile.data.transport.appserver.AppServerChannelAccount
import com.letta.mobile.data.transport.appserver.AppServerChannelAccountPatch
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Boot/reconnect restore of the App Server's channel accounts (lgns8.23).
 *
 * ## Why this exists
 *
 * Under a bare `letta app-server --listen`, upstream's `initializeChannels()` is
 * unreachable — its only invocation lives in the `--channels` CLI branch, which
 * `--listen` forbids. `wireChannelIngress` therefore early-returns on a null
 * registry at boot, and **enabled accounts in `accounts.json` are never
 * auto-started**. The empirical probe on the 0.29.12 pin (letta-mobile-lgns8.23.1,
 * 2026-07-31) measured exactly this: after a server restart with
 * `enabled=true`, a fresh client saw `running=false` until it issued
 * `channel_start`, which restored the account to `running=true` with a full sync.
 *
 * The same probe established the three facts this coordinator is built on:
 *
 * 1. **No handshake gate.** `channel_*` frames are accepted on a bare socket
 *    before any `runtime_start`, so the restore can run at the very top of the
 *    post-connect sequence.
 * 2. **Repeat `channel_start` is a clean stop+start**, not a no-op and not a
 *    leak — exactly one sync loop after it. Safe to issue every reconnect at the
 *    cost of one sync bounce.
 * 3. **Ingress binds to the ISSUING socket.** `channel_start` calls
 *    `wireChannelIngress(runtime, socket, …)` closed over the socket that sent
 *    the command. After a wrapper↔appserver reconnect the adapter keeps syncing
 *    but inbound points at the dead socket, so the restore MUST re-issue
 *    `channel_start` after every generation flip — that re-issue is the re-wire.
 *    This is why [restore] starts even accounts already reporting `running=true`.
 *
 * ## Landmine 1 — a failed start persists `enabled:false`
 *
 * Measured: after a start against an unreachable homeserver failed, upstream
 * wrote `"enabled": false` into `accounts.json`. A naive retry loop firing
 * during a homeserver blip would therefore **permanently disable the channel**.
 * This coordinator re-asserts `enabled=true` (via `channel_account_update`, the
 * one patch command that takes policy fields without echoing plugin config)
 * after every failed start — including the last one before giving up — and backs
 * off exponentially with a bounded attempt budget. On exhaustion it emits a WARN
 * and stops until the next reconnect, rather than hammering a sick homeserver.
 *
 * ## Landmine 2 — cleartext credentials on the wire
 *
 * `channel_accounts_list` returns the plugin account config VERBATIM, including
 * the Matrix `accessToken` / `syncAccessToken` in cleartext. Therefore:
 *
 * - Nothing in this file ever logs, telemeters, or persists a config body. Every
 *   diagnostic carries channel ids, account ids, booleans and counts only, and
 *   error strings are the server's own message (never a config echo).
 * - [AppServerChannelAccount.toString] withholds the config, so even an
 *   accidental interpolation cannot leak.
 * - `channel_*` responses are controller-initiated and are NOT fanned out to
 *   Iroh viewers: `RuntimeEventFanout.planUnscopedControl` drops unscoped frames
 *   that are not server-initiated control requests, which these are not.
 *
 * ## Sequence (per generation-ready / connect)
 *
 * ```
 * channels_list
 *   └─ per configured channel: channel_accounts_list
 *        └─ per enabled account: channel_start          (unconditional; re-wires ingress)
 *             └─ on failure: channel_account_update{enabled:true} + capped backoff retry
 * ```
 *
 * @param client the generation client to issue on. Pass the client handed to
 *   `ReconnectingClientListener.onRecovered` so ingress binds to the live socket.
 */
class ChannelRestoreCoordinator(
    private val client: AppServerClient,
    private val requestIdFactory: () -> String = ::defaultChannelRequestId,
    /** Total `channel_start` attempts per account, per restore pass. */
    private val maxAttemptsPerAccount: Int = DEFAULT_MAX_ATTEMPTS,
    private val baseBackoffMs: Long = DEFAULT_BASE_BACKOFF_MS,
    private val maxBackoffMs: Long = DEFAULT_MAX_BACKOFF_MS,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /**
     * Diagnostic sink. Receives ids and outcomes only — never config bodies.
     * Defaults to a no-op so library use is silent; the CLI passes a printer.
     */
    private val log: (String) -> Unit = {},
) {
    private val restoreMutex = Mutex()

    /**
     * Runs one full restore pass. Never throws: transport failures become
     * [ChannelRestoreFailure] entries so a channel outage cannot fail the whole
     * post-connect recovery flow (runtime reattachment must still complete).
     */
    suspend fun restore(): ChannelRestoreResult = restoreMutex.withLock {
        val failures = mutableListOf<ChannelRestoreFailure>()
        val channelIds = listConfiguredChannels(failures)
            ?: return ChannelRestoreResult(emptyList(), 0, failures)
        var started = 0
        for (channelId in channelIds) {
            for (account in listEnabledAccounts(channelId, failures)) {
                if (startAccountWithRetries(channelId, account.accountId, failures)) started++
            }
        }
        Telemetry.event(
            TELEMETRY_TAG,
            "restore_complete",
            "channels" to channelIds.size,
            "started" to started,
            "failed" to failures.size,
        )
        log("[channels] restore complete: channels=${channelIds.size} started=$started failed=${failures.size}")
        ChannelRestoreResult(channelIds, started, failures)
    }

    /** `channels_list` → the ids of channels that have any configuration at all. */
    private suspend fun listConfiguredChannels(
        failures: MutableList<ChannelRestoreFailure>,
    ): List<String>? {
        val response = try {
            client.channelsList(AppServerCommand.ChannelsList(requestId = requestIdFactory()))
        } catch (e: Exception) {
            failures += ChannelRestoreFailure(null, null, ChannelRestorePhase.LIST_CHANNELS, e.messageOrClass())
            warn("list_channels_failed", "reason" to e.messageOrClass())
            return null
        }
        if (!response.success) {
            failures += ChannelRestoreFailure(
                null,
                null,
                ChannelRestorePhase.LIST_CHANNELS,
                response.error ?: "channels_list reported failure",
            )
            warn("list_channels_failed", "reason" to (response.error ?: "unknown"))
            return null
        }
        return response.channels.filter { it.configured }.map { it.channelId }
    }

    /**
     * `channel_accounts_list` for one channel → the accounts we intend to run.
     *
     * Only `enabled` accounts are restored: a disabled account is an operator
     * decision, and re-enabling one here would resurrect a channel a human
     * deliberately turned off. Note the asymmetry with landmine 1 — we never
     * enable an account we did not just try to start.
     */
    private suspend fun listEnabledAccounts(
        channelId: String,
        failures: MutableList<ChannelRestoreFailure>,
    ): List<AppServerChannelAccount> {
        val response = try {
            client.channelAccountsList(
                AppServerCommand.ChannelAccountsList(
                    requestId = requestIdFactory(),
                    channelId = channelId,
                ),
            )
        } catch (e: Exception) {
            failures += ChannelRestoreFailure(channelId, null, ChannelRestorePhase.LIST_ACCOUNTS, e.messageOrClass())
            warn("list_accounts_failed", "channelId" to channelId, "reason" to e.messageOrClass())
            return emptyList()
        }
        if (!response.success) {
            failures += ChannelRestoreFailure(
                channelId,
                null,
                ChannelRestorePhase.LIST_ACCOUNTS,
                response.error ?: "channel_accounts_list reported failure",
            )
            warn("list_accounts_failed", "channelId" to channelId, "reason" to (response.error ?: "unknown"))
            return emptyList()
        }
        // NEVER log `response.accounts` itself — each element carries the plugin
        // config (Matrix accessToken / syncAccessToken) verbatim.
        return response.accounts.filter { it.enabled }
    }

    /**
     * Issues `channel_start` unconditionally (even when the account already
     * reports `running=true`) so ingress re-binds to this generation's socket,
     * retrying with capped exponential backoff and re-asserting `enabled=true`
     * after each failure so a transient outage cannot latch the account off.
     *
     * @return true when the account reached a successful start.
     */
    private suspend fun startAccountWithRetries(
        channelId: String,
        accountId: String,
        failures: MutableList<ChannelRestoreFailure>,
    ): Boolean {
        var lastError = "unknown"
        for (attempt in 0 until maxAttemptsPerAccount) {
            val outcome = attemptStart(channelId, accountId)
            if (outcome == null) {
                Telemetry.event(
                    TELEMETRY_TAG,
                    "account_started",
                    "channelId" to channelId,
                    "accountId" to accountId,
                    "attempt" to attempt,
                )
                log("[channels] started $channelId/$accountId (attempt ${attempt + 1})")
                return true
            }
            lastError = outcome
            // Landmine 1: a FAILED channel_start persists enabled:false. Re-assert
            // before the next attempt AND after the final one, so giving up never
            // leaves the account permanently disabled.
            reassertEnabled(channelId, accountId)
            if (attempt < maxAttemptsPerAccount - 1) {
                sleep(backoffMs(attempt))
            }
        }
        failures += ChannelRestoreFailure(channelId, accountId, ChannelRestorePhase.START_ACCOUNT, lastError)
        warn(
            "account_start_gave_up",
            "channelId" to channelId,
            "accountId" to accountId,
            "attempts" to maxAttemptsPerAccount,
            "reason" to lastError,
        )
        log(
            "[channels] gave up starting $channelId/$accountId after $maxAttemptsPerAccount attempts " +
                "(enabled re-asserted); retry on next reconnect",
        )
        return false
    }

    /** @return null on success, else a bounded error string (never a config echo). */
    private suspend fun attemptStart(channelId: String, accountId: String): String? = try {
        val response = client.channelStart(
            AppServerCommand.ChannelStart(
                requestId = requestIdFactory(),
                channelId = channelId,
                accountId = accountId,
            ),
        )
        if (response.success) null else (response.error ?: "channel_start reported failure")
    } catch (e: Exception) {
        e.messageOrClass()
    }

    /**
     * Re-asserts `enabled=true` on an account whose start just failed. Sends only
     * the `enabled` field: [AppServerChannelAccountPatch] deliberately cannot
     * carry `config`, so this can never round-trip a credential back to disk.
     * Best-effort — a failure here is logged, not fatal, since the next reconnect
     * re-runs the whole restore.
     */
    private suspend fun reassertEnabled(channelId: String, accountId: String) {
        try {
            val response = client.channelAccountUpdate(
                AppServerCommand.ChannelAccountUpdate(
                    requestId = requestIdFactory(),
                    channelId = channelId,
                    accountId = accountId,
                    patch = AppServerChannelAccountPatch(enabled = true),
                ),
            )
            if (!response.success) {
                warn(
                    "reassert_enabled_failed",
                    "channelId" to channelId,
                    "accountId" to accountId,
                    "reason" to (response.error ?: "unknown"),
                )
            }
        } catch (e: Exception) {
            warn(
                "reassert_enabled_failed",
                "channelId" to channelId,
                "accountId" to accountId,
                "reason" to e.messageOrClass(),
            )
        }
    }

    /** Capped exponential backoff: base * 2^attempt, clamped to [maxBackoffMs]. */
    internal fun backoffMs(attempt: Int): Long {
        if (attempt <= 0) return baseBackoffMs.coerceAtMost(maxBackoffMs)
        val shift = attempt.coerceAtMost(MAX_BACKOFF_SHIFT)
        val scaled = baseBackoffMs.toDouble() * (1L shl shift).toDouble()
        return if (scaled >= maxBackoffMs.toDouble()) maxBackoffMs else scaled.toLong()
    }

    private fun warn(name: String, vararg attrs: Pair<String, Any?>) {
        Telemetry.event(TELEMETRY_TAG, name, *attrs, level = Telemetry.Level.WARN)
    }

    private fun Exception.messageOrClass(): String = message ?: this::class.simpleName ?: "exception"

    companion object {
        const val TELEMETRY_TAG: String = "ChannelRestore"
        const val DEFAULT_MAX_ATTEMPTS: Int = 3
        const val DEFAULT_BASE_BACKOFF_MS: Long = 500
        const val DEFAULT_MAX_BACKOFF_MS: Long = 8_000
        private const val MAX_BACKOFF_SHIFT: Int = 20

        private var nextRequestId = 0

        private fun defaultChannelRequestId(): String {
            nextRequestId += 1
            return "channel-restore-$nextRequestId"
        }
    }
}

/** Phase of the restore sequence a failure occurred in. */
enum class ChannelRestorePhase {
    LIST_CHANNELS,
    LIST_ACCOUNTS,
    START_ACCOUNT,
}

/**
 * One restore failure. Carries identifiers and the server's error string only —
 * never account config (see landmine 2 in [ChannelRestoreCoordinator]).
 */
data class ChannelRestoreFailure(
    val channelId: String?,
    val accountId: String?,
    val phase: ChannelRestorePhase,
    val reason: String,
)

/** Outcome of one [ChannelRestoreCoordinator.restore] pass. */
data class ChannelRestoreResult(
    val channelIds: List<String>,
    val startedAccounts: Int,
    val failures: List<ChannelRestoreFailure>,
) {
    val isFullySuccessful: Boolean get() = failures.isEmpty()
}
