package com.letta.mobile.ui.chat.session

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * A [StateFlow] read off other state with no cache or coroutine of its own,
 * so a host can expose a derived extension point without a scope to `stateIn` it in (and nothing to
 * cancel when it closes). [value] is [current] now; a collector gets [changes], deduplicated as a
 * StateFlow's are, which must start with the current value (a combine of StateFlows does).
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
class DerivedStateFlow<T>(
    private val changes: Flow<T>,
    private val current: () -> T,
) : StateFlow<T> {
    override val value: T get() = current()

    override val replayCache: List<T> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        changes.distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}
