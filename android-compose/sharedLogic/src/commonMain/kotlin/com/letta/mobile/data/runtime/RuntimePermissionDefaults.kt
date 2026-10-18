package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerPermissionMode

/**
 * letta-mobile-h5t1g: single source of truth for the runtime permission mode used
 * when a caller does not pass one explicitly.
 *
 * Cold runtime starts (app reconnect, App Server restart, runtime eviction) used
 * to fall back to [AppServerPermissionMode.Standard] in several places. Nothing on
 * the mobile side answers tool approvals in Standard, so the first tool call parked
 * the turn until the idle watchdog killed it and the next turn settled the calls as
 * synthetic "Turn did not complete" errors. The product default is approve-all.
 *
 * letta-mobile-4mps3 (aktss.2) will bind a settings toggle to this same source:
 * replace [DEFAULT_MODE] reads with the injected provider on the seams that already
 * take one (e.g. `DefaultAppServerController(defaultPermissionMode = …)`).
 *
 * Note: approve-all still never auto-approves interactive user-input tools — see the
 * `RuntimeUserInputTools` carve-out in `AppServerTurnEngine.autoApproveIfAllowed`
 * (letta-mobile-vilsn).
 */
object RuntimePermissionDefaults {
    /** Effective permission mode when none is cached or explicitly requested. */
    val DEFAULT_MODE: AppServerPermissionMode = AppServerPermissionMode.Unrestricted
}
