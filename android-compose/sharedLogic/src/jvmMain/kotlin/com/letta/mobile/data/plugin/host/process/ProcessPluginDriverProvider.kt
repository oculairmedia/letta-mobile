package com.letta.mobile.data.plugin.host.process

import com.letta.mobile.data.plugin.host.PluginDriver
import com.letta.mobile.data.plugin.host.PluginDriverProvider
import com.letta.mobile.data.plugin.host.PluginLaunchSpec
import com.letta.mobile.data.plugin.wire.LcpHostHandlers
import com.letta.mobile.data.plugin.wire.LcpWire
import kotlinx.coroutines.CoroutineScope

/** Process executor selected by distribution assembly, retaining the supplied launch limits and deadlines. */
class ProcessPluginDriverProvider(private val options: ProcessDriverOptions) : PluginDriverProvider {
    override val id: String = "letta.process"
    override val runtimeKind: String = "process"
    override val contractVersions: Set<Int> = LcpWire.SUPPORTED_CONTRACT_VERSIONS.toSet()

    override fun create(
        spec: PluginLaunchSpec,
        handlers: LcpHostHandlers,
        scope: CoroutineScope,
        onStderr: (String) -> Unit,
    ): PluginDriver = ProcessPluginDriver(
        spec,
        handlers,
        scope,
        options.copy(onStderr = onStderr),
    )
}
