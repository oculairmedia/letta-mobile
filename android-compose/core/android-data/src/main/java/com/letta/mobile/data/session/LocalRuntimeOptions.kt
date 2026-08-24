package com.letta.mobile.data.session

import com.letta.mobile.runtime.MemFsStore
import com.letta.mobile.runtime.RuntimeEventOutbox

/**
 * Local-runtime wiring options for session graph assembly.
 * Kept package-internal so tests can enable a fake provider without Hilt.
 */
internal sealed interface LocalRuntimeOptions {
    data object Disabled : LocalRuntimeOptions

    data class Enabled(
        val runtimeEventOutbox: RuntimeEventOutbox,
        val memFsStore: MemFsStore,
        val providers: Set<LocalRuntimeProvider>,
    ) : LocalRuntimeOptions
}
