package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ModelApi
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.LocalRuntimeModelSource

/** Android binding for [CachedModelRepository]. Phase 5l. */
open class ModelRepository(
    modelApi: ModelApi,
    localModelSource: LocalRuntimeModelSource? = null,
    settingsRepository: ISettingsRepository? = null,
    irohModelSource: IrohAdminRpcModelSource? = null,
) : CachedModelRepository(
    remote = modelApi,
    localModelSource = localModelSource,
    settingsRepository = settingsRepository,
    irohModelSource = irohModelSource,
)

