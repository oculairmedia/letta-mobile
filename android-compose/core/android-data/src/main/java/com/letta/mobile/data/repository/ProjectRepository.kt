package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ProjectApi

/**
 * Android binding for [CachedProjectRepository]: HTTP [ProjectApi] + optional Iroh source.
 *
 * Phase 5n — cache/refresh/sanitize/Iroh routing live in sharedLogic; this type keeps
 * the historical constructor for session wiring and existing unit tests.
 */
open class ProjectRepository(
    projectApi: ProjectApi,
    irohProjectSource: IrohAdminRpcProjectSource? = null,
) : CachedProjectRepository(
    remote = projectApi,
    irohProjectSource = irohProjectSource,
)
