package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.bound
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.RemoteRoute
import com.letta.mobile.data.repository.api.MessageRemoteSource
import com.letta.mobile.data.timeline.IrohAdminRpcTimelineTransport
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android binding for [CachedMessageRepository]: messages over an Iroh host's `admin_rpc`.
 *
 * letta-mobile-ckmub.4: [remote] is the seam for a non-App-Server backend; production binds no
 * route (the Letta REST backend was removed).
 *
 * Phase 5p — fetch / approval / inspector / batch orchestration live in sharedLogic.
 */
@Singleton
class MessageRepository(
    remote: RemoteRoute<MessageRemoteSource>,
    irohApprovalSource: IrohAdminRpcApprovalSource? = null,
    irohTimelineTransport: IrohAdminRpcTimelineTransport? = null,
) : CachedMessageRepository(
    remote = remote,
    irohTimelineSource = irohTimelineTransport?.let(::IrohAdminRpcMessageTimelineSource),
    irohApprovalSource = irohApprovalSource,
) {
    @Inject
    constructor(
        irohApprovalSource: IrohAdminRpcApprovalSource?,
        irohTimelineTransport: IrohAdminRpcTimelineTransport?,
    ) : this(RemoteRoute.Absent(SessionRoute.CONVERSATIONS), irohApprovalSource, irohTimelineTransport)

    /** A repository over a bound [remote] (tests bind a fake). */
    constructor(
        remote: MessageRemoteSource,
        irohApprovalSource: IrohAdminRpcApprovalSource? = null,
        irohTimelineTransport: IrohAdminRpcTimelineTransport? = null,
    ) : this(remote.bound(), irohApprovalSource, irohTimelineTransport)

    companion object {
        const val INITIAL_FETCH_LIMIT: Int = CachedMessageRepository.INITIAL_FETCH_LIMIT
        const val OLDER_MESSAGES_PAGE_SIZE: Int = CachedMessageRepository.OLDER_MESSAGES_PAGE_SIZE
        const val DEFAULT_FETCH_LIMIT: Int = CachedMessageRepository.DEFAULT_FETCH_LIMIT
        const val TARGETED_FETCH_LIMIT: Int = CachedMessageRepository.TARGETED_FETCH_LIMIT
        const val MAX_TARGETED_FETCH_PAGES: Int = CachedMessageRepository.MAX_TARGETED_FETCH_PAGES
    }
}
