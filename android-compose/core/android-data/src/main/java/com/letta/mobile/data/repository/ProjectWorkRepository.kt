package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.noAppServerRoute
import com.letta.mobile.data.repository.api.ProjectWorkRemoteSource

/**
 * Android binding for [CachedProjectWorkRepository]. Project issues and ready work; no backend
 * serves them over the App Server yet.
 *
 * letta-mobile-ckmub.4: [remote] is the seam for a non-App-Server backend; production binds no
 * route (the Letta REST backend was removed).
 */
open class ProjectWorkRepository(
    remote: ProjectWorkRemoteSource = noAppServerRoute(),
) : CachedProjectWorkRepository(
    remote = remote,
)
