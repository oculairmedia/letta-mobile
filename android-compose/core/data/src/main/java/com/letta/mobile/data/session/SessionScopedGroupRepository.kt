package com.letta.mobile.data.session

import com.letta.mobile.data.model.Group
import com.letta.mobile.data.model.GroupCreateParams
import com.letta.mobile.data.model.GroupId
import com.letta.mobile.data.model.GroupUpdateParams
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LettaResponse
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.ProjectId
import com.letta.mobile.data.repository.api.IGroupRepository
import io.ktor.utils.io.ByteReadChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
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
    private val proxyScope: CoroutineScope,
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

    override suspend fun refreshGroups(managerType: String?, projectId: ProjectId?, showHiddenGroups: Boolean?) =
        sessionManager.withCurrentSession { it.groupRepository.refreshGroups(managerType, projectId, showHiddenGroups) }

    override suspend fun countGroups(): Int = sessionManager.withCurrentSession { it.groupRepository.countGroups() }

    override suspend fun getGroup(groupId: GroupId): Group = sessionManager.withCurrentSession { it.groupRepository.getGroup(groupId) }

    override suspend fun createGroup(params: GroupCreateParams): Group = sessionManager.withCurrentSession { it.groupRepository.createGroup(params) }

    override suspend fun updateGroup(groupId: GroupId, params: GroupUpdateParams): Group =
        sessionManager.withCurrentSession { it.groupRepository.updateGroup(groupId, params) }

    override suspend fun deleteGroup(groupId: GroupId) = sessionManager.withCurrentSession { it.groupRepository.deleteGroup(groupId) }

    override suspend fun sendGroupMessage(groupId: GroupId, request: MessageCreateRequest): LettaResponse =
        sessionManager.withCurrentSession { it.groupRepository.sendGroupMessage(groupId, request) }

    override suspend fun sendGroupMessageStream(groupId: GroupId, request: MessageCreateRequest): ByteReadChannel =
        sessionManager.withCurrentSession { it.groupRepository.sendGroupMessageStream(groupId, request) }

    override suspend fun updateGroupMessage(groupId: GroupId, messageId: String, request: JsonElement): LettaMessage =
        sessionManager.withCurrentSession { it.groupRepository.updateGroupMessage(groupId, messageId, request) }

    override suspend fun listGroupMessages(groupId: GroupId): List<LettaMessage> = sessionManager.withCurrentSession { it.groupRepository.listGroupMessages(groupId) }

    override suspend fun resetGroupMessages(groupId: GroupId) = sessionManager.withCurrentSession { it.groupRepository.resetGroupMessages(groupId) }

    fun close() { proxyScope.cancel() }
}
