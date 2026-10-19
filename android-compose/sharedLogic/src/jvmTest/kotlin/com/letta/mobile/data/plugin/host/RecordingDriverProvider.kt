package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.plugin.wire.LcpHostHandlers
import com.letta.mobile.plugin.api.ActionCall
import com.letta.mobile.plugin.api.ActionResult
import com.letta.mobile.plugin.api.ElementEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** An executor fake that records the actual host request and receives real published tool calls. */
internal class RecordingDriverProvider(
    override val id: String = "test.executor",
    override val runtimeKind: String = "process",
    override val contractVersions: Set<Int> = setOf(1),
) : PluginDriverProvider {
    val specs = mutableListOf<PluginLaunchSpec>()
    val drivers = mutableListOf<RecordingPluginDriver>()
    var createFailure: (PluginLaunchSpec) -> Throwable? = { null }
    var configureDriver: (RecordingPluginDriver) -> Unit = {}

    override fun create(
        spec: PluginLaunchSpec,
        handlers: LcpHostHandlers,
        scope: CoroutineScope,
        onStderr: (String) -> Unit,
    ): PluginDriver {
        specs += spec
        createFailure(spec)?.let { throw it }
        return RecordingPluginDriver(spec.manifest.id).also {
            configureDriver(it)
            drivers += it
        }
    }
}

internal class RecordingPluginDriver(override val pluginId: String) : PluginDriver {
    private val mutableState = MutableStateFlow<PluginDriverState>(PluginDriverState.Starting)
    override val state: StateFlow<PluginDriverState> = mutableState
    val calls = mutableListOf<ActionCall>()
    var stops = 0
    var startFailure: Throwable? = null
    var beforeStart: suspend () -> Unit = {}
    var beforeStop: suspend () -> Unit = {}

    override suspend fun start(): PluginDriverState {
        beforeStart()
        startFailure?.let { throw it }
        return PluginDriverState.Running(null).also { mutableState.value = it }
    }

    override suspend fun invoke(call: ActionCall): ActionResult {
        calls += call
        return ActionResult.Ok("injected ${call.action}")
    }

    override suspend fun elementEvent(event: ElementEvent) = Unit

    override suspend fun stop() {
        beforeStop()
        stops++
        mutableState.value = PluginDriverState.Stopped("disabled")
    }
}
