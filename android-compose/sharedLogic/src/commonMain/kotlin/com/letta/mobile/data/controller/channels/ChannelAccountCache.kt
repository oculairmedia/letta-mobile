package com.letta.mobile.data.controller.channels

import kotlinx.atomicfu.atomic

/**
 * Last-known enabled channel accounts, carried ACROSS App Server generations
 * (letta-mobile-o5bqk).
 *
 * [ChannelRestoreCoordinator] is constructed per generation because it binds to
 * that generation's client, so it cannot remember anything itself. This cache is
 * the one piece of state that must outlive a socket: it is what lets the restore
 * issue `channel_start` for already-known accounts in the FIRST round trip after
 * a reconnect, instead of waiting out `channels_list` + one
 * `channel_accounts_list` per channel.
 *
 * That ordering is the whole point. Upstream binds channel ingress to the socket
 * that issued `channel_start`; between socket-open and the re-issue, an inbound
 * channel event is received, posts a typing indicator, resolves its route and is
 * ENQUEUED — but `scheduleQueuePump` bails on the dead captured socket, so the
 * turn never runs and the room shows a typing indicator followed by silence
 * (measured 2026-08-01, letta-mobile-lgns8.23.1 probe #4). Shortening the window
 * to a single round trip is the client-side half of the mitigation.
 *
 * Contents are identifiers only. **Never** put an [AppServerChannelAccount] or
 * any part of its config in here: account config carries the Matrix
 * `accessToken` / `syncAccessToken` in cleartext.
 *
 * @see ChannelRestoreCoordinator
 */
interface ChannelAccountCache {
    /**
     * Accounts observed `enabled` by the most recent completed enumeration, in
     * enumeration order. Empty on the first pass of a process — a boot restore
     * has nothing to fast-path and simply enumerates.
     */
    fun lastKnownEnabled(): List<ChannelAccountRef>

    /** Replaces the cached set with the accounts the latest enumeration found enabled. */
    fun record(accounts: List<ChannelAccountRef>)
}

/**
 * Process-lifetime [ChannelAccountCache]. Hold ONE instance per channels host and
 * hand it to every per-generation [ChannelRestoreCoordinator]; a fresh instance
 * per generation would never fast-path anything.
 *
 * Backed by an atomic reference over an immutable list, so a restore pass reading
 * the cache can never observe a torn write from a concurrent one.
 */
class InMemoryChannelAccountCache : ChannelAccountCache {
    private val accounts = atomic<List<ChannelAccountRef>>(emptyList())

    override fun lastKnownEnabled(): List<ChannelAccountRef> = accounts.value

    override fun record(accounts: List<ChannelAccountRef>) {
        // Only identified accounts are fast-pathable: channel_start needs an
        // account id, and a channel-wide ref is a failure marker, not an account.
        this.accounts.value = accounts.filter { it.accountId != null }.toList()
    }
}
