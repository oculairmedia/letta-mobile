package com.letta.mobile.domain

import com.letta.mobile.data.api.ToolApi
import com.letta.mobile.data.model.ToolCreateParams
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClientToolSync @Inject constructor(
    private val toolApi: ToolApi,
    private val clientToolRegistry: ClientToolRegistry,
) {
    suspend fun syncTools(agentId: String) {
        val deviceInfoTool = ToolCreateParams(
            name = "get_device_info",
            sourceCode = """
                def get_device_info():
                    '''Get information about the mobile device.
                    
                    Returns:
                        dict: Device information including model, OS version, and app version.
                    '''
                    pass
            """.trimIndent(),
            description = "Get information about the mobile device including model, OS version, and app version",
            tags = listOf("client", "device", "mobile")
        )

        toolApi.upsertTool(deviceInfoTool)
        
        toolApi.attachTool(agentId, deviceInfoTool.name)
    }
}
