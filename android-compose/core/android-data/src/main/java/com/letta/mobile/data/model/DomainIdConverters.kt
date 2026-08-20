package com.letta.mobile.data.model

import androidx.room.TypeConverter

/**
 * Room converters for domain identity value classes supplied by :sharedLogic.
 */
class DomainIdConverters {
    @TypeConverter
    fun agentIdToString(id: AgentId): String = id.value

    @TypeConverter
    fun stringToAgentId(value: String): AgentId = AgentId(value)

    @TypeConverter
    fun projectIdToString(id: ProjectId): String = id.value

    @TypeConverter
    fun stringToProjectId(value: String): ProjectId = ProjectId(value)

    @TypeConverter
    fun conversationIdToString(id: ConversationId): String = id.value

    @TypeConverter
    fun stringToConversationId(value: String): ConversationId = ConversationId(value)

    @TypeConverter
    fun toolIdToString(id: ToolId): String = id.value

    @TypeConverter
    fun stringToToolId(value: String): ToolId = ToolId(value)

    @TypeConverter
    fun blockIdToString(id: BlockId): String = id.value

    @TypeConverter
    fun stringToBlockId(value: String): BlockId = BlockId(value)

    @TypeConverter
    fun folderIdToString(id: FolderId): String = id.value

    @TypeConverter
    fun stringToFolderId(value: String): FolderId = FolderId(value)

    @TypeConverter
    fun providerIdToString(id: ProviderId): String = id.value

    @TypeConverter
    fun stringToProviderId(value: String): ProviderId = ProviderId(value)

    @TypeConverter
    fun identityIdToString(id: IdentityId): String = id.value

    @TypeConverter
    fun stringToIdentityId(value: String): IdentityId = IdentityId(value)

    @TypeConverter
    fun mcpServerIdToString(id: McpServerId): String = id.value

    @TypeConverter
    fun stringToMcpServerId(value: String): McpServerId = McpServerId(value)

    @TypeConverter
    fun groupIdToString(id: GroupId): String = id.value

    @TypeConverter
    fun stringToGroupId(value: String): GroupId = GroupId(value)
}
