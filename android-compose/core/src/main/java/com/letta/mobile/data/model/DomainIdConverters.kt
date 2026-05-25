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
    fun toolIdToString(id: ToolId): String = id.value

    @TypeConverter
    fun stringToToolId(value: String): ToolId = ToolId(value)

    @TypeConverter
    fun blockIdToString(id: BlockId): String = id.value

    @TypeConverter
    fun stringToBlockId(value: String): BlockId = BlockId(value)
}
