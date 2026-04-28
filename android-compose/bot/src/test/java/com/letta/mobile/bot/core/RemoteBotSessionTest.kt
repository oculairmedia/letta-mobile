package com.letta.mobile.bot.core

import com.letta.mobile.bot.channel.ChannelMessage
import com.letta.mobile.bot.config.BotConfig
import com.letta.mobile.bot.config.BotServerProfileResolver
import com.letta.mobile.bot.config.BotServerProfile
import com.letta.mobile.bot.config.IBotServerProfileStore
import com.letta.mobile.bot.protocol.BotAgentInfo
import com.letta.mobile.bot.protocol.BotChatRequest
import com.letta.mobile.bot.protocol.BotChatResponse
import com.letta.mobile.bot.protocol.BotClient
import com.letta.mobile.bot.protocol.BotStatusResponse
import com.letta.mobile.bot.protocol.BotStreamChunk
import com.letta.mobile.bot.protocol.GatewayReadyClient
import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag

@Tag("unit")
class RemoteBotSessionTest : WordSpec({
    // letta-mobile-w2hx.4: every ChannelMessage flowing through a
    // RemoteBotSession now MUST carry a targetAgentId — the bot is a
    // transport, the agent travels per-message. Tests covering the
    // session must populate it; `requireAgent` errors otherwise.
    val message = ChannelMessage(
        messageId = "msg-1",
        channelId = "in_app",
        chatId = "chat-1",
        senderId = "user-1",
        senderName = "User",
        text = "hello",
        targetAgentId = "agent-1",
    )

    "RemoteBotSession" should {
        "default to HTTP transport when BotConfig transport is omitted" {
            val recorder = RecordingOverride()
            val session = remoteSession(
                config = remoteConfig(),
                clientFactoryOverride = recorder::create,
            )

            runBlocking { session.start() }

            recorder.transports.single() shouldBe BotConfig.Transport.HTTP
            recorder.readyAgentIds shouldHaveSize 0
        }

        "use WS transport when explicitly configured" {
            val recorder = RecordingOverride()
            val session = remoteSession(
                config = remoteConfig(transport = BotConfig.Transport.WS),
                clientFactoryOverride = recorder::create,
            )

            runBlocking { session.start() }

            recorder.transports.single() shouldBe BotConfig.Transport.WS
            // letta-mobile-w2hx.4: start() no longer pre-warms a bound
            // agent's gateway session. The per-agent WS pool (w2hx.3)
            // opens sessions lazily on first real message, so start
            // does not call ensureGatewayReady at all.
            recorder.readyAgentIds shouldHaveSize 0
        }

        "stream progressive chunks when WS transport is selected" {
            val session = remoteSession(
                config = remoteConfig(transport = BotConfig.Transport.WS),
                clientFactoryOverride = {
                    FakeBotClient(
                        streamChunks = listOf(
                            BotStreamChunk(text = "Hel", conversationId = "conv-1"),
                            BotStreamChunk(text = "lo", conversationId = "conv-1"),
                            BotStreamChunk(conversationId = "conv-1", done = true),
                        )
                    )
                },
            )

            runBlocking { session.start() }
            val chunks = runBlocking { session.streamToAgent(message).toList() }

            chunks shouldHaveSize 3
            chunks[0].text shouldBe "Hel"
            chunks[0].isComplete shouldBe false
            chunks[1].text shouldBe "lo"
            chunks[1].isComplete shouldBe false
            chunks[2].isComplete shouldBe true
            chunks[2].conversationId shouldBe "conv-1"
        }

        // letta-mobile-uww.12: terminal frame must NOT re-emit accumulated
        // assistant text under the gateway's pure-delta contract. Field
        // repro 2026-04-26: assistant bubble rendered twice in a single
        // contiguous block because the consumer (Client Mode timeline
        // ASSISTANT branch) appends every emit with `text != null` and
        // the previous terminal frame re-emitted the full accumulated
        // string. This regression test pins the contract.
        "not re-emit assistant text on the terminal frame for delta streams" {
            val session = remoteSession(
                config = remoteConfig(
                    transport = BotConfig.Transport.WS,
                    directivesEnabled = true,
                ),
                clientFactoryOverride = {
                    FakeBotClient(
                        streamChunks = listOf(
                            BotStreamChunk(text = "Hey ", conversationId = "conv-1"),
                            BotStreamChunk(text = "— I see you. ", conversationId = "conv-1"),
                            BotStreamChunk(text = "Connection's live", conversationId = "conv-1"),
                            BotStreamChunk(conversationId = "conv-1", done = true),
                        )
                    )
                },
            )

            runBlocking { session.start() }
            val chunks = runBlocking { session.streamToAgent(message).toList() }

            chunks shouldHaveSize 4
            chunks[0].text shouldBe "Hey "
            chunks[1].text shouldBe "— I see you. "
            chunks[2].text shouldBe "Connection's live"
            chunks[3].isComplete shouldBe true
            chunks[3].text shouldBe null
            chunks[3].directive shouldBe null
            chunks[3].conversationId shouldBe "conv-1"
        }

        "parse directives only on the final chunk" {
            val session = remoteSession(
                config = remoteConfig(transport = BotConfig.Transport.WS, directivesEnabled = true),
                clientFactoryOverride = {
                    FakeBotClient(
                        streamChunks = listOf(
                            BotStreamChunk(text = "Before "),
                            BotStreamChunk(text = "<no-reply/>"),
                            BotStreamChunk(done = true),
                        )
                    )
                },
            )

            runBlocking { session.start() }
            val chunks = runBlocking { session.streamToAgent(message).toList() }

            chunks[0].directive shouldBe null
            chunks[1].directive shouldBe null
            chunks[2].isComplete shouldBe true
            chunks[2].directive shouldBe Directive.NoReply
            chunks[2].text shouldBe "Before"
        }

        "pick up a different transport after stop and restart" {
            val recorder = RecordingOverride()
            val session = remoteSession(
                config = remoteConfig(),
                clientFactoryOverride = recorder::create,
            )

            runBlocking {
                session.start()
                session.stop()
            }
            session.currentClient shouldBe null
            recorder.transports.single() shouldBe BotConfig.Transport.HTTP

            val wsSession = remoteSession(
                config = remoteConfig(transport = BotConfig.Transport.WS),
                clientFactoryOverride = recorder::create,
            )

            runBlocking { wsSession.start() }
            recorder.transports.last() shouldBe BotConfig.Transport.WS
        }
    }
})

private fun remoteSession(
    config: BotConfig,
    clientFactoryOverride: ((com.letta.mobile.bot.config.ResolvedRemoteProfile) -> BotClient)? = null,
): RemoteBotSession {
    val profileResolver = BotServerProfileResolver(FakeBotServerProfileStore())
    return if (clientFactoryOverride == null) {
        RemoteBotSession(
            config = config,
            profileResolver = profileResolver,
        )
    } else {
        RemoteBotSession(
            config = config,
            profileResolver = profileResolver,
            clientFactoryOverride = clientFactoryOverride,
        )
    }
}

private fun remoteConfig(
    transport: BotConfig.Transport = BotConfig.Transport.HTTP,
    directivesEnabled: Boolean = true,
): BotConfig = BotConfig(
    id = "bot-1",
    displayName = "Remote Bot",
    mode = BotConfig.Mode.REMOTE,
    remoteUrl = "https://bot.example",
    remoteToken = "secret",
    transport = transport,
    directivesEnabled = directivesEnabled,
)

private class FakeBotServerProfileStore : IBotServerProfileStore {
    override val profiles = flowOf(emptyList<BotServerProfile>())

    override suspend fun saveProfile(profile: BotServerProfile) = Unit
    override suspend fun deleteProfile(profileId: String) = Unit
    override suspend fun getAll(): List<BotServerProfile> = emptyList()
    override suspend fun activateProfile(profileId: String) = Unit
    override suspend fun findById(profileId: String): BotServerProfile? = null
    override suspend fun getActiveProfile(): BotServerProfile? = null
}

private class RecordingOverride {
    val transports = mutableListOf<BotConfig.Transport>()
    val readyAgentIds = mutableListOf<String>()

    fun create(resolvedProfile: com.letta.mobile.bot.config.ResolvedRemoteProfile): BotClient {
        transports += resolvedProfile.transport
        if (resolvedProfile.transport == BotConfig.Transport.HTTP) {
            return FakeBotClient()
        }
        return object : BotClient, GatewayReadyClient {
            private val delegate = FakeBotClient()

            override suspend fun sendMessage(request: BotChatRequest): BotChatResponse =
                delegate.sendMessage(request)

            override fun streamMessage(request: BotChatRequest): Flow<BotStreamChunk> =
                delegate.streamMessage(request)

            override suspend fun getStatus(): BotStatusResponse = delegate.getStatus()

            override suspend fun listAgents(): List<BotAgentInfo> = delegate.listAgents()

            override suspend fun ensureGatewayReady(agentId: String, conversationId: String?) {
                readyAgentIds += agentId
            }
        }
    }
}

private class FakeBotClient(
    private val chatResponse: BotChatResponse = BotChatResponse(response = "ok", conversationId = "conv-1", agentId = "agent-1"),
    private val streamChunks: List<BotStreamChunk> = listOf(BotStreamChunk(text = "ok", conversationId = "conv-1", done = true)),
) : BotClient {
    override suspend fun sendMessage(request: BotChatRequest): BotChatResponse = chatResponse

    override fun streamMessage(request: BotChatRequest): Flow<BotStreamChunk> = flow {
        streamChunks.forEach { emit(it) }
    }

    override suspend fun getStatus(): BotStatusResponse = BotStatusResponse(status = "ok", agents = emptyList())

    override suspend fun listAgents(): List<BotAgentInfo> = emptyList()
}
