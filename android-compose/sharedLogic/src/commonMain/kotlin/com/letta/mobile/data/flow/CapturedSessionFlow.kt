package com.letta.mobile.data.flow

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow

/** Captures a subscriber handle for each session generation without creating a detached job. */
@OptIn(InternalCoroutinesApi::class)
class CapturedSessionFlow<G, R>(
    private val graphs: StateFlow<G>,
    private val capture: (G) -> R?,
) : StateFlow<R?> {
    private var graph = graphs.value
    private var runtime = capture(graph)
    override val value: R? get() = runtime
    override val replayCache: List<R?> get() = listOf(value)
    override suspend fun collect(collector: FlowCollector<R?>): Nothing {
        graphs.collect { next ->
            if (next !== graph) {
                val captured = capture(next)
                graph = next
                runtime = captured
            }
            // The consumer drains its pipeline before retiring this handle, also in finally.
            collector.emit(runtime)
        }
        error("Session graph StateFlow completed")
    }
}
