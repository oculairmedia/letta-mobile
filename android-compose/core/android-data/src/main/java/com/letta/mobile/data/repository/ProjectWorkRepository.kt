package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.bound
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.RemoteRoute
import com.letta.mobile.data.repository.api.ProjectWorkRemoteSource

/**
 * Android binding for [CachedProjectWorkRepository]. Project issues and ready work; no backend
 * serves them over the App Server yet.
 *
 * letta-mobile-ckmub.4: [remote] is the seam for a non-App-Server backend; production binds no
 * route (the Letta REST backend was removed).
 */
open class ProjectWorkRepository(
    remote: RemoteRoute<ProjectWorkRemoteSource> = RemoteRoute.Absent(SessionRoute.PROJECT_WORK),
) : CachedProjectWorkRepository(
    remote = remote,
) {
    /** A repository over a bound [remote] (tests bind a fake). */
    constructor(remote: ProjectWorkRemoteSource) : this(remote.bound())
}
