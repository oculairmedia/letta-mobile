package com.letta.mobile.data.repository

import com.letta.mobile.data.api.JobApi

/** Android binding for [CachedJobRepository]. Phase 5k. */
class JobRepository(
    jobApi: JobApi,
    irohJobSource: IrohAdminRpcJobSource? = null,
) : CachedJobRepository(
    remote = jobApi,
    irohJobSource = irohJobSource,
)
