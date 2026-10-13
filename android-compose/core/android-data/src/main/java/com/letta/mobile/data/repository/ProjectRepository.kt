package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.noAppServerRoute
import com.letta.mobile.data.repository.api.ProjectRemoteSource

/**
 * Android binding for [CachedProjectRepository]. Projects over an Iroh host's `admin_rpc` (VibeSync).
 *
 * letta-mobile-ckmub.4: [remote] is the seam for a non-App-Server backend; production binds no
 * route (the Letta REST backend was removed).
 */
open class ProjectRepository(
    remote: ProjectRemoteSource = noAppServerRoute(),
    irohProjectSource: IrohAdminRpcProjectSource? = null,
) : CachedProjectRepository(
    remote = remote,
    irohProjectSource = irohProjectSource,
)
