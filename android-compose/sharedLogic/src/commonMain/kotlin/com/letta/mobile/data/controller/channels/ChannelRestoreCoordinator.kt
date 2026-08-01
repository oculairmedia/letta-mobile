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
 * ## Landmine 3 — the re-wire window (letta-mobile-o5bqk)
 *
 * Re-issuing `channel_start` is the re-wire, but it is not instant, and the
 * adapter keeps syncing throughout. Probe #4 (2026-08-01) measured what happens
 * to an inbound event that lands BEFORE the re-issue: the old socket-bound
 * ingress closure still fires — the event is received, a typing indicator is
 * posted to the room, the route resolves, and the item is enqueued — but
 * `scheduleQueuePump` bails because `isListenerTransportOpen(<dead socket>)` is
 * false. No error, no channel-side failure notice; the room shows typing and then
 * nothing, and the item drains only on a LATER connect (lossily, per the cron
 * drain finding in lgns8.24 (3d)).
 *
 * That silent-drop is upstream behaviour and cannot be fixed from here: the
 * enqueue happens inside letta-code, in a closure we do not own, and the socket
 * it captured is chosen by upstream, not by us. What IS client-side achievable is
 * making the window as short as the transport allows — one round trip instead of
 * `1 + 1 + <channel count>`. Hence the fast path below: on any restore after the
 * first, `channel_start` for every account the previous enumeration found enabled
 * is issued IMMEDIATELY, before `channels_list`, from a cache that outlives the
 * socket ([ChannelAccountCache]). The enumeration then runs as before and is the
 * authority; accounts it re-confirms are not started twice.
 *
 * ## Sequence (per generation-ready / connect)
 *
 * ```
 * channel_start × cached enabled accounts        (fast path; skipped on the first pass)
 *   └─ on failure: channel_account_update{enabled:true}, then fall through to the retrying path
 * channels_list
 *   └─ per configured channel: channel_accounts_list
 *        └─ per enabled account NOT already started above: channel_start
 *             └─ on failure: channel_account_update{enabled:true} + capped backoff retry
 * ```
 *
 * @param client the generation client to issue on. Pass the client handed to
 *   `ReconnectingClientListener.onRecovered` so ingress binds to the live socket.
 * @param accountCache shared across generations. The default is a private
 *   instance, which disables the fast path — the channels host must pass ONE
 *   long-lived cache to every coordinator it builds.
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
    private val accountCache: ChannelAccountCache = InMemoryChannelAccountCache(),
) {
    private val restoreMutex = Mutex()

    /**
     * Runs one full restore pass. Never throws: transport failures become
     * [ChannelRestoreFailure] entries so a channel outage cannot fail the whole
     * post-connect recovery flow (runtime reattachment must still complete).
     */
    suspend fun restore(): ChannelRestoreResult = restoreMutex.withLock {
        val failures = mutableListOf<ChannelRestoreFailure>()
        // Landmine 3: re-wire the accounts we already know about in the first
        // round trip, BEFORE any enumeration, so the ingress window is as short
        // as the transport allows.
        val rewired = fastPathRewire()
        var started = rewired.size
        val channelIds = listConfiguredChannels(failures)
            ?: return ChannelRestoreResult(emptyList(), started, failures)
        val enabled = mutableListOf<ChannelAccountRef>()
        for (channelId in channelIds) {
            for (account in listEnabledAccounts(channelId, failures)) {
                val ref = ChannelAccountRef(channelId, account.accountId)
                enabled += ref
                // Already started on THIS socket a moment ago: ingress is wired,
                // and a second start would cost the account another sync bounce.
                if (ref in rewired) continue
                if (startAccountWithRetries(ref, failures)) started++
            }
        }
        reportStaleFastPath(rewired, enabled)
        accountCache.record(enabled)
        Telemetry.event(
            TELEMETRY_TAG,
            "restore_complete",
            "channels" to channelIds.size,
            "started" to started,
            "rewired" to rewired.size,
            "failed" to failures.size,
        )
        log(
            "[channels] restore complete: channels=${channelIds.size} started=$started " +
                "rewired=${rewired.size} failed=${failures.size}",
        )
        ChannelRestoreResult(channelIds, started, failures)
    }

    /**
     * FIRST-ROUND-TRIP re-wire (letta-mobile-o5bqk). Issues `channel_start` for
     * every account the previous enumeration found enabled, before `channels_list`
     * so nothing gates it.
     *
     * One attempt each, no backoff: this path exists to be fast. Anything that
     * fails here falls through to [startAccountWithRetries] during the
     * enumeration below, which owns the retry budget.
     *
     * A failure still has to re-assert `enabled=true` immediately (landmine 1) —
     * otherwise the failed start's persisted `enabled:false` would make the
     * enumeration that follows *within this same pass* skip the account entirely,
     * turning a transient blip into a whole dark generation.
     *
     * @return the accounts that reached a successful start, so the enumeration
     *   does not start them a second time.
     */
    private suspend fun fastPathRewire(): Set<ChannelAccountRef> {
        val cached = accountCache.lastKnownEnabled()
        if (cached.isEmpty()) return emptySet()
        val rewired = LinkedHashSet<ChannelAccountRef>()
        for (ref in cached) {
            if (ref.accountId == null) continue
            val error = attemptStart(ref)
            if (error == null) {
                rewired += ref
                continue
            }
            reassertEnabled(ref)
            warn(
                "fast_path_start_failed",
                "channelId" to ref.channelId,
                "accountId" to ref.accountId,
                "reason" to error,
            )
        }
        Telemetry.event(
            TELEMETRY_TAG,
            "fast_path_complete",
            "attempted" to cached.size,
            "rewired" to rewired.size,
        )
        log("[channels] fast-path re-wire: attempted=${cached.size} rewired=${rewired.size}")
        return rewired
    }

    /**
     * Sensing for the fast path's one real risk: an account disabled between
     * generations is started once by the cache before the enumeration can say so.
     * We cannot un-start it — the App Server WS exposes no `channel_stop` to this
     * client — so the honest response is a loud, actionable warning rather than a
     * silent resurrection.
     */
    private fun reportStaleFastPath(
        rewired: Set<ChannelAccountRef>,
        enabled: List<ChannelAccountRef>,
    ) {
        val stillEnabled = enabled.toSet()
        for (ref in rewired) {
            if (ref in stillEnabled) continue
            warn(
                "fast_path_started_stale_account",
                "channelId" to ref.channelId,
                "accountId" to ref.accountId,
            )
            log(
                "[channels] WARN fast-path started $ref but the enumeration no longer reports it " +
                    "enabled — it will not be started again after the next restart; stop it manually " +
                    "if it must be off now",
            )
        }
    }

    /** `channels_list` → the ids of channels that have any configuration at all. */
    private suspend fun listConfiguredChannels(
        failures: MutableList<ChannelRestoreFailure>,
    ): List<String>? {
        val response = try {
            client.channelsList(AppServerCommand.ChannelsList(requestId = requestIdFactory()))
        } catch (e: Exception) {
            failures += ChannelRestoreFailure(null, ChannelRestorePhase.LIST_CHANNELS, e.messageOrClass())
            warn("list_channels_failed", "reason" to e.messageOrClass())
            return null
        }
        if (!response.success) {
            failures += ChannelRestoreFailure(
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
            failures += ChannelRestoreFailure(
                ChannelAccountRef(channelId, accountId = null),
                ChannelRestorePhase.LIST_ACCOUNTS,
                e.messageOrClass(),
            )
            warn("list_accounts_failed", "channelId" to channelId, "reason" to e.messageOrClass())
            return emptyList()
        }
        if (!response.success) {
            failures += ChannelRestoreFailure(
                ChannelAccountRef(channelId, accountId = null),
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
        ref: ChannelAccountRef,
        failures: MutableList<ChannelRestoreFailure>,
    ): Boolean {
        var lastError = "unknown"
        for (attempt in 0 until maxAttemptsPerAccount) {
            val outcome = attemptStart(ref)
            if (outcome == null) {
                Telemetry.event(
                    TELEMETRY_TAG,
                    "account_started",
                    "channelId" to ref.channelId,
                    "accountId" to ref.accountId,
                    "attempt" to attempt,
                )
                log("[channels] started $ref (attempt ${attempt + 1})")
                return true
            }
            lastError = outcome
            // Landmine 1: a FAILED channel_start persists enabled:false. Re-assert
            // before the next attempt AND after the final one, so giving up never
            // leaves the account permanently disabled.
            reassertEnabled(ref)
            if (attempt < maxAttemptsPerAccount - 1) {
                sleep(backoffMs(attempt))
            }
        }
        failures += ChannelRestoreFailure(ref, ChannelRestorePhase.START_ACCOUNT, lastError)
        warn(
            "account_start_gave_up",
            "channelId" to ref.channelId,
            "accountId" to ref.accountId,
            "attempts" to maxAttemptsPerAccount,
            "reason" to lastError,
        )
        log(
            "[channels] gave up starting $ref after $maxAttemptsPerAccount attempts " +
                "(enabled re-asserted); retry on next reconnect",
        )
        return false
    }

    /** @return null on success, else a bounded error string (never a config echo). */
    private suspend fun attemptStart(ref: ChannelAccountRef): String? = try {
        val response = client.channelStart(
            AppServerCommand.ChannelStart(
                requestId = requestIdFactory(),
                channelId = ref.channelId,
                accountId = ref.accountId,
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
    private suspend fun reassertEnabled(ref: ChannelAccountRef) {
        val reason = try {
            val response = client.channelAccountUpdate(
                AppServerCommand.ChannelAccountUpdate(
                    requestId = requestIdFactory(),
                    channelId = ref.channelId,
                    accountId = requireNotNull(ref.accountId),
                    patch = AppServerChannelAccountPatch(enabled = true),
                ),
            )
            if (response.success) return else (response.error ?: "unknown")
        } catch (e: Exception) {
            e.messageOrClass()
        }
        warn(
            "reassert_enabled_failed",
            "channelId" to ref.channelId,
            "accountId" to ref.accountId,
            "reason" to reason,
        )
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
 * Identifies one channel account. Exists so the restore path passes a single
 * typed reference instead of loose (channelId, accountId) string pairs, and so
 * every diagnostic renders it the same way. [accountId] is null when the
 * reference is channel-wide (e.g. an accounts-listing failure).
 */
data class ChannelAccountRef(
    val channelId: String,
    val accountId: String?,
) {
    override fun toString(): String = "$channelId/${accountId ?: "-"}"
}

/**
 * One restore failure. Carries identifiers and the server's error string only —
 * never account config (see landmine 2 in [ChannelRestoreCoordinator]).
 */
data class ChannelRestoreFailure(
    val account: ChannelAccountRef?,
    val phase: ChannelRestorePhase,
    val reason: String,
) {
    val channelId: String? get() = account?.channelId

    val accountId: String? get() = account?.accountId
}

/** Outcome of one [ChannelRestoreCoordinator.restore] pass. */
data class ChannelRestoreResult(
    val channelIds: List<String>,
    val startedAccounts: Int,
    val failures: List<ChannelRestoreFailure>,
) {
    val isFullySuccessful: Boolean get() = failures.isEmpty()
}
