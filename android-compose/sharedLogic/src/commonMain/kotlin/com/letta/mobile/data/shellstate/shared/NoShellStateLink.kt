package com.letta.mobile.data.shellstate.shared

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject

/** The link of a build with no host: never connected, advertises nothing, and every call fails as unreachable. */
object NoShellStateLink : ShellStateLink {
    override val hostCapabilities: Set<String> = emptySet()
    override val currentConnection: String? = null
    override val connection: Flow<String?> = flowOf(null)
    override val pushes: Flow<JsonObject> = emptyFlow()

    override suspend fun call(method: String, params: JsonObject): ShellStateReply = ShellStateReply(success = false)
}
