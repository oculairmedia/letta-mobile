package com.letta.mobile.desktop.data

import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.AgentBlockTarget
import com.letta.mobile.data.repository.api.IAgentBlockWriteRepository
import com.letta.mobile.data.repository.http.LettaHttpAdminRepositories
import com.letta.mobile.data.repository.http.LettaHttpAgentBlockWriter
import com.letta.mobile.desktop.chat.createDesktopLettaHttpClient
import io.ktor.client.HttpClient

/**
 * Desktop binding for the shared [LettaHttpAdminRepositories]. The platform-
 * neutral caching/TTL/error-flow/request logic lives in commonMain; the desktop
 * module supplies only the JVM Ktor engine and the system clock
 * (letta-mobile-mqzkc). It also serves the committed agent-block write for the
 * shared memory page via [LettaHttpAgentBlockWriter].
 */
internal class DesktopLettaHttpAdminRepositories(
    config: LettaConfig,
    httpClient: HttpClient = createDesktopLettaHttpClient(),
    nowMillis: () -> Long = { System.currentTimeMillis() },
) : LettaHttpAdminRepositories(
    config = config,
    httpClient = httpClient,
    nowMillis = nowMillis,
), IAgentBlockWriteRepository {
    private val blockWriter = LettaHttpAgentBlockWriter(config, httpClient)

    override suspend fun writeAgentBlock(target: AgentBlockTarget, params: BlockUpdateParams): Block =
        blockWriter.write(target, params)

    override suspend fun createAgentBlock(target: AgentBlockTarget, value: String): Block =
        blockWriter.create(target, value)

    override suspend fun deleteAgentBlock(target: AgentBlockTarget) =
        blockWriter.delete(target)
}
