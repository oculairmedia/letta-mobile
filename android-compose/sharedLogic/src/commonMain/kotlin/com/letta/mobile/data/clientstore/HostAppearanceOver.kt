package com.letta.mobile.data.clientstore

import com.letta.mobile.data.shellstate.HostAppearance
import com.letta.mobile.data.shellstate.LegacyAppearance
import com.letta.mobile.data.shellstate.appearance.SharedShellAppearance
import com.letta.mobile.data.shellstate.shared.SharedShellState
import com.letta.mobile.data.shellstate.shared.ShellStateLink
import com.letta.mobile.data.storage.SecureSettingsStore

/**
 * The person's appearance on the host, read and proposed over [link] (the one shared shell state client every
 * client uses), with [store] holding the disposable mirror of the host's last document so a cold start offline still
 * shows it. [legacy] is whatever this device chose before appearance moved onto the host, migrated once.
 *
 * Android, desktop and the web each build theirs with this, differing only in the link (their channel transport) and
 * the store (their own key-value storage).
 */
fun hostAppearanceOver(
    link: ShellStateLink,
    store: SecureSettingsStore,
    legacy: LegacyAppearance = LegacyAppearance.None,
): HostAppearance {
    val mirror = ShellStateCacheStore(store)
    return SharedShellAppearance(shell = SharedShellState(link, mirror), mirror = mirror, legacy = legacy)
}
