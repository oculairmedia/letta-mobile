package com.letta.mobile.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

class UnknownClientToolException(toolName: String) : Exception("Unknown client tool: $toolName")

@Singleton
class ClientToolRegistry @Inject constructor() {
    private val json = Json { ignoreUnknownKeys = true }
    
    private val tools: Map<String, suspend (String) -> String> = mapOf(
        "get_device_info" to ::getDeviceInfo
    )

    fun isClientTool(toolName: String): Boolean {
        return tools.containsKey(toolName)
    }

    suspend fun execute(toolName: String, arguments: String): String {
        val tool = tools[toolName] ?: throw UnknownClientToolException(toolName)
        return tool(arguments)
    }

    private suspend fun getDeviceInfo(arguments: String): String {
        return buildJsonObject {
            put("device_model", "Android Device")
            put("os_version", "Android API 26+")
            put("app_version", "1.0.0")
        }.toString()
    }
}
