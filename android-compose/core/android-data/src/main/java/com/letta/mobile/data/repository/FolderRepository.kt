package com.letta.mobile.data.repository

import com.letta.mobile.data.api.FolderApi

/**
 * Android binding for [CachedFolderRepository]: HTTP [FolderApi] + optional Iroh list source.
 *
 * Phase 5f — cache/refresh/Iroh list routing live in sharedLogic; this type keeps
 * the historical constructor for session wiring and existing unit tests.
 */
class FolderRepository(
    folderApi: FolderApi,
    irohFolderSource: IrohAdminRpcFolderSource? = null,
) : CachedFolderRepository(
    remote = folderApi,
    irohFolderSource = irohFolderSource,
)
