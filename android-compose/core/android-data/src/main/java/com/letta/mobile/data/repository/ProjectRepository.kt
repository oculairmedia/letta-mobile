package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.bound
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.RemoteRoute
import com.letta.mobile.data.repository.api.ProjectRemoteSource

/**
 * Android binding for [CachedProjectRepository]. Projects over an Iroh host's `admin_rpc` (VibeSync).
 *
 * letta-mobile-ckmub.4: [remote] is the seam for a non-App-Server backend; production binds no
 * route (the Letta REST backend was removed).
 */
open class ProjectRepository(
    remote: RemoteRoute<ProjectRemoteSource> = RemoteRoute.Absent(SessionRoute.PROJECTS),
    irohProjectSource: IrohAdminRpcProjectSource? = null,
) : CachedProjectRepository(
    remote = remote,
    irohProjectSource = irohProjectSource,
) {
    /** A repository over a bound [remote] (tests bind a fake). */
    constructor(remote: ProjectRemoteSource, irohProjectSource: IrohAdminRpcProjectSource? = null) : this(remote.bound(), irohProjectSource)
}
