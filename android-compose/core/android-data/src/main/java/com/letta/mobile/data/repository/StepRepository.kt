package com.letta.mobile.data.repository

import com.letta.mobile.data.api.StepApi

/** Android binding for [CachedStepRepository]. Phase 5m. */
class StepRepository(
    stepApi: StepApi,
) : CachedStepRepository(
    remote = stepApi,
)
