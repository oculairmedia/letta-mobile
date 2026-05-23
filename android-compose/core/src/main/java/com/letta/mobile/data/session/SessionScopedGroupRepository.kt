package com.letta.mobile.data.session

import com.letta.mobile.data.model.Group
import com.letta.mobile.data.model.GroupCreateParams
import com.letta.mobile.data.model.GroupUpdateParams
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LettaResponse
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.repository.api.IGroupRepository
import io.ktor.utils.io.ByteReadChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.JsonElement

internal fun defaultSessionScopedGroupRepositoryScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class SessionScopedGroupRepository internal constructor(
    private val sessionManager: SessionManager,
    proxyScope: CoroutineScope,
) : IGroupRepository {
    @Inject
    constructor(
        sessionManager: SessionManager,
    ) : this(
        sessionManager = sessionManager,
        proxyScope = defaultSessionScopedGroupRepositoryScope(),
    )

    private val _groups = MutableStateFlow(sessionManager.current.groupRepository.groups.value)
    override val groups: StateFlow<List<Group>> = _groups

    init {
        sessionManager.currentGraph
            .flatMapLatest { it.groupRepository.groups }
            .onEach { _groups.value = it }
            .launchIn(proxyScope)
    }

    private val current: IGroupRepository
        get() = sessionManager.current.groupRepository

    override suspend fun refreshGroups(managerType: String?, projectId: String?, showHiddenGroups: Boolean?) =
        current.refreshGroups(managerType, projectId, showHiddenGroups)

    override suspend fun countGroups(): Int = current.countGroups()

    override suspend fun getGroup(groupId: String): Group = current.getGroup(groupId)

    override suspend fun createGroup(params: GroupCreateParams): Group = current.createGroup(params)

    override suspend fun updateGroup(groupId: String, params: GroupUpdateParams): Group =
        current.updateGroup(groupId, params)

    override suspend fun deleteGroup(groupId: String) = current.deleteGroup(groupId)

    override suspend fun sendGroupMessage(groupId: String, request: MessageCreateRequest): LettaResponse =
        current.sendGroupMessage(groupId, request)

    override suspend fun sendGroupMessageStream(groupId: String, request: MessageCreateRequest): ByteReadChannel =
        current.sendGroupMessageStream(groupId, request)

    override suspend fun updateGroupMessage(groupId: String, messageId: String, request: JsonElement): LettaMessage =
        current.updateGroupMessage(groupId, messageId, request)

    override suspend fun listGroupMessages(groupId: String): List<LettaMessage> = current.listGroupMessages(groupId)

    override suspend fun resetGroupMessages(groupId: String) = current.resetGroupMessages(groupId)
}
