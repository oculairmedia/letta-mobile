package com.letta.mobile.di

import com.letta.mobile.data.repository.api.ISettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Singleton

@Singleton
/** One captured config, pinned for the lifetime of a single selected-runtime capture. */
internal class CapturedSettings(
    delegate: ISettingsRepository,
    config: com.letta.mobile.data.model.LettaConfig,
) : ISettingsRepository by delegate {
    override val activeConfig = MutableStateFlow<com.letta.mobile.data.model.LettaConfig?>(config)
    override val activeConfigChanges = flowOf(config)
}
