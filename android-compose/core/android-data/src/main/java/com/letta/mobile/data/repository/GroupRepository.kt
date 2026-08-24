package com.letta.mobile.data.repository

import com.letta.mobile.data.api.GroupApi

/** Android binding for [CachedGroupRepository]. Phase 5i. */
class GroupRepository(
    groupApi: GroupApi,
    irohGroupSource: IrohAdminRpcGroupSource? = null,
) : CachedGroupRepository(
    remote = groupApi,
    irohGroupSource = irohGroupSource,
)
