package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.bound
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.RemoteRoute
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.local.GatedDao
import com.letta.mobile.data.local.SessionCacheGate
import com.letta.mobile.data.local.RoomAllConversationsLocalCache
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.repository.api.ConversationRemoteSource
import com.letta.mobile.data.transport.api.IChannelTransport
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The scope of a repository built outside a session graph (tests, previews); graphs pass their own. */
@Suppress("NoDetachedCoroutineLifecycle")
internal fun defaultAllConversationsScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Android binding for [CachedAllConversationsRepository]: Room cache + the session channel's admin_rpc source.
 *
 * letta-mobile-ckmub.4: [remote] is the seam for a non-App-Server backend; production binds no
 * route (the Letta REST backend was removed).
 */
open class AllConversationsRepository(
    roster: suspend () -> List<Agent>,
    remote: RemoteRoute<ConversationRemoteSource> = RemoteRoute.Absent(SessionRoute.CONVERSATIONS),
    conversationDao: Lazy<ConversationDao>? = null,
    repositoryScope: CoroutineScope,
    irohConversationListSource: IrohAdminRpcConversationListSource? = null,
    transport: IChannelTransport? = null,
    /** letta-mobile-by4vm.94: the session's cache clear every Room access waits for. */
    cacheGate: SessionCacheGate = SessionCacheGate.Open,
) : CachedAllConversationsRepository(
    roster = roster,
    remote = remote,
    localCache = conversationDao?.let { GatedDao(it, cacheGate) }?.let { dao -> { RoomAllConversationsLocalCache(dao) } },
    repositoryScope = repositoryScope,
    irohConversationListSource = irohConversationListSource,
    transport = transport,
) {
    /** A repository over [remote] (the backend seam; tests and previews bind a fake). */
    constructor(
        remote: ConversationRemoteSource,
        roster: suspend () -> List<Agent>,
        conversationDao: Lazy<ConversationDao>? = null,
        repositoryScope: CoroutineScope = defaultAllConversationsScope(),
        irohConversationListSource: IrohAdminRpcConversationListSource? = null,
        transport: IChannelTransport? = null,
    ) : this(
        roster = roster,
        remote = remote.bound(),
        conversationDao = conversationDao,
        repositoryScope = repositoryScope,
        irohConversationListSource = irohConversationListSource,
        transport = transport,
    )
}
