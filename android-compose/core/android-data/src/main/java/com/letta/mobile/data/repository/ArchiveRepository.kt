package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ArchiveApi

/**
 * Android binding for [CachedArchiveRepository]: HTTP [ArchiveApi] + optional Iroh list source.
 *
 * Phase 5h — cache/refresh/Iroh list routing live in sharedLogic; this type keeps
 * the historical constructor for session wiring and existing unit tests.
 */
open class ArchiveRepository(
    archiveApi: ArchiveApi,
    irohArchiveSource: IrohAdminRpcArchiveSource? = null,
) : CachedArchiveRepository(
    remote = archiveApi,
    irohArchiveSource = irohArchiveSource,
)
