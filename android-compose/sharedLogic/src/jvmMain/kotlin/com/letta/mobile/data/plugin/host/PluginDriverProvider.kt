package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.plugin.wire.LcpHostHandlers
import kotlinx.coroutines.CoroutineScope

/** A distribution-selected executor for one runtime kind and its supported plugin contract majors. */
interface PluginDriverProvider {
    /** Stable provider identity, unique within the host's registry. */
    val id: String

    /** Manifest runtime kind this executor accepts, such as `process`. */
    val runtimeKind: String

    /** Plugin contract majors this executor implements; selection requires an exact major match. */
    val contractVersions: Set<Int>

    /**
     * Builds one driver after consent and unambiguous resolution; does not start it.
     * [spec] carries the package and consented inputs. [handlers] retains host capability/rate/quota
     * guards; [scope] owns driver work. Scrub secret values before sending output to [onStderr].
     * A factory that throws must release resources it allocated before returning a driver.
     * The host contains ordinary factory/start failures and stops any returned partial driver.
     */
    fun create(
        spec: PluginLaunchSpec,
        handlers: LcpHostHandlers,
        scope: CoroutineScope,
        onStderr: (String) -> Unit,
    ): PluginDriver
}
