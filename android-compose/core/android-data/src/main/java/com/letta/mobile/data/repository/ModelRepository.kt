package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.bound
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.RemoteRoute
import com.letta.mobile.data.repository.api.ModelRemoteSource
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.LocalRuntimeModelSource

/**
 * Android binding for [CachedModelRepository]. Models from the on-device runtime or an Iroh host.
 *
 * letta-mobile-ckmub.4: [remote] is the seam for a non-App-Server backend; production binds no
 * route (the Letta REST backend was removed).
 */
open class ModelRepository(
    remote: RemoteRoute<ModelRemoteSource> = RemoteRoute.Absent(SessionRoute.MODELS),
    localModelSource: LocalRuntimeModelSource? = null,
    settingsRepository: ISettingsRepository? = null,
    irohModelSource: IrohAdminRpcModelSource? = null,
) : CachedModelRepository(
    remote = remote,
    localModelSource = localModelSource,
    settingsRepository = settingsRepository,
    irohModelSource = irohModelSource,
) {
    /** A repository over a bound [remote] (tests bind a fake). */
    constructor(
        remote: ModelRemoteSource,
        localModelSource: LocalRuntimeModelSource? = null,
        settingsRepository: ISettingsRepository? = null,
        irohModelSource: IrohAdminRpcModelSource? = null,
    ) : this(remote.bound(), localModelSource, settingsRepository, irohModelSource)
}
