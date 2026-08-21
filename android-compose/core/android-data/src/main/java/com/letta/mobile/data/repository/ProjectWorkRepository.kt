package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ProjectWorkApi

/** Android binding for [CachedProjectWorkRepository]. Phase 5n. */
open class ProjectWorkRepository(
    projectWorkApi: ProjectWorkApi,
) : CachedProjectWorkRepository(
    remote = projectWorkApi,
)
