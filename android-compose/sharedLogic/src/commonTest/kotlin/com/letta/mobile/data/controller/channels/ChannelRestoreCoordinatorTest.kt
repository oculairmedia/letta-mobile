package com.letta.mobile.data.controller.channels

import com.letta.mobile.data.transport.appserver.AppServerChannelAccount
import com.letta.mobile.data.transport.appserver.AppServerChannelSummary
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Restore-sequence tests for lgns8.23, scripted against a fake App Server client
 * that mirrors the frames captured by the letta-mobile-lgns8.23.1 probe.
 *
 * Kotlin/Native commonTest naming: punctuation-free camelCase function names.
 */
class ChannelRestoreCoordinatorTest {
    // Tokens that appear in the fake's account config. No log line, telemetry
    // attribute, result object, or toString() may ever contain these.
    private val secretAccessToken = "syt_TOTALLY_SECRET_ACCESS_TOKEN"
    private val secretSyncToken = "syt_TOTALLY_SECRET_SYNC_TOKEN"

    private fun matrixAccount(
        accountId: String = "lettabot",
        enabled: Boolean = true,
        running: Boolean = false,
    ) = AppServerChannelAccount(
        channelId = "matrix",
        accountId = accountId,
        displayName = "Letta Bot",
        enabled = enabled,
        configured = true,
        running = running,
        config = buildJsonObject {
            put("homeserverUrl", "http://homeserver.invalid")
            put("accessToken", secretAccessToken)
            put("syncAccessToken", secretSyncToken)
        },
    )

    @Test
    fun bootRestoreStartsEnabledAccountThatIsNotRunning() = runTest {
        val client = ScriptedChannelClient(
            channels = listOf(summary("matrix"), summary("mobile")),
            accounts = mapOf(
                "matrix" to listOf(matrixAccount(running = false)),
                "mobile" to listOf(matrixAccount(accountId = "mobile-default", running = false).copy(channelId = "mobile")),
            ),
        )

        val result = coordinator(client).restore()

        assertEquals(listOf("matrix", "mobile"), result.channelIds)
        assertEquals(2, result.startedAccounts)
        assertTrue(result.isFullySuccessful)
        assertEquals(
            listOf("matrix/lettabot", "mobile/mobile-default"),
            client.starts,
        )
    }

    /**
     * FAIL-ON-REVERT for the ingress caveat: `channel_start` binds ingress to the
     * ISSUING socket, so a reconnect must re-issue it even for accounts already
     * reporting `running=true`. Adding a `running` short-circuit to the
     * coordinator makes this test fail.
     */
    @Test
    fun reconnectReissuesChannelStartEvenWhenAccountIsAlreadyRunning() = runTest {
        val client = ScriptedChannelClient(
            channels = listOf(summary("matrix")),
            accounts = mapOf("matrix" to listOf(matrixAccount(running = true))),
        )
        val coordinator = coordinator(client)

        // First pass: boot restore.
        coordinator.restore()
        // Second pass: the post-reconnect generation-ready hook.
        val second = coordinator.restore()

        assertEquals(1, second.startedAccounts)
        assertEquals(
            listOf("matrix/lettabot", "matrix/lettabot"),
            client.starts,
            "channel_start must be re-issued after every reconnect to re-wire ingress",
        )
    }

    @Test
    fun disabledAccountsAreNeverStarted() = runTest {
        val client = ScriptedChannelClient(
            channels = listOf(summary("matrix")),
            accounts = mapOf(
                "matrix" to listOf(
                    matrixAccount(accountId = "lettabot", enabled = true),
                    matrixAccount(accountId = "retired", enabled = false),
                ),
            ),
        )

        coordinator(client).restore()

        assertEquals(listOf("matrix/lettabot"), client.starts)
        assertTrue(client.enableReasserts.isEmpty())
    }

    @Test
    fun unconfiguredChannelsAreSkipped() = runTest {
        val client = ScriptedChannelClient(
            channels = listOf(summary("matrix"), summary("slack", configured = false)),
            accounts = mapOf("matrix" to listOf(matrixAccount())),
        )

        val result = coordinator(client).restore()

        assertEquals(listOf("matrix"), result.channelIds)
        assertTrue(client.accountListings.none { it == "slack" })
    }

    /**
     * LANDMINE 1: a failed `channel_start` persists `enabled:false` upstream. The
     * coordinator must re-assert `enabled=true` after EVERY failure, including
     * the last one, so a homeserver blip cannot latch the channel off forever.
     */
    @Test
    fun failedStartReassertsEnabledOnEveryAttemptIncludingTheLast() = runTest {
        val client = ScriptedChannelClient(
            channels = listOf(summary("matrix")),
            accounts = mapOf("matrix" to listOf(matrixAccount())),
            startFailures = Int.MAX_VALUE,
            startError = "matrix plugin: whoami failed (fetch failed).",
        )

        val result = coordinator(client).restore()

        assertEquals(0, result.startedAccounts)
        assertEquals(3, client.starts.size, "attempt budget must be honoured")
        assertEquals(
            3,
            client.enableReasserts.size,
            "enabled must be re-asserted after each failure, including the final one",
        )
        assertTrue(client.enableReasserts.all { it == "matrix/lettabot:true" })
        assertEquals(1, result.failures.size)
        assertEquals(ChannelRestorePhase.START_ACCOUNT, result.failures.single().phase)
    }

    @Test
    fun startRetriesUntilSuccessAndStopsRetryingAfterwards() = runTest {
        val client = ScriptedChannelClient(
            channels = listOf(summary("matrix")),
            accounts = mapOf("matrix" to listOf(matrixAccount())),
            startFailures = 1,
        )

        val result = coordinator(client).restore()

        assertEquals(1, result.startedAccounts)
        assertEquals(2, client.starts.size)
        assertEquals(1, client.enableReasserts.size)
        assertTrue(result.isFullySuccessful)
    }

    @Test
    fun retryCapIsBoundedAndBackoffIsCapped() = runTest {
        val slept = mutableListOf<Long>()
        val client = ScriptedChannelClient(
            channels = listOf(summary("matrix")),
            accounts = mapOf("matrix" to listOf(matrixAccount())),
            startFailures = Int.MAX_VALUE,
        )
        val coordinator = ChannelRestoreCoordinator(
            client = client,
            requestIdFactory = { "req" },
            maxAttemptsPerAccount = 5,
            baseBackoffMs = 100,
            maxBackoffMs = 400,
            sleep = { slept += it },
        )

        coordinator.restore()

        assertEquals(5, client.starts.size)
        // One sleep between attempts, none after the final failure.
        assertEquals(listOf(100L, 200L, 400L, 400L), slept)
        assertEquals(400L, coordinator.backoffMs(20))
    }

    @Test
    fun channelsListFailureAbortsWithoutTouchingAccounts() = runTest {
        val client = ScriptedChannelClient(
            channels = emptyList(),
            accounts = emptyMap(),
            channelsListError = "boom",
        )

        val result = coordinator(client).restore()

        assertTrue(result.channelIds.isEmpty())
        assertEquals(0, result.startedAccounts)
        assertEquals(ChannelRestorePhase.LIST_CHANNELS, result.failures.single().phase)
        assertTrue(client.accountListings.isEmpty())
    }

    @Test
    fun accountsListFailureOnOneChannelDoesNotBlockOthers() = runTest {
        val client = ScriptedChannelClient(
            channels = listOf(summary("matrix"), summary("mobile")),
            accounts = mapOf("mobile" to listOf(matrixAccount(accountId = "mobile-default").copy(channelId = "mobile"))),
            accountsListErrors = mapOf("matrix" to "registry unavailable"),
        )

        val result = coordinator(client).restore()

        assertEquals(1, result.startedAccounts)
        assertEquals(listOf("mobile/mobile-default"), client.starts)
        assertEquals(ChannelRestorePhase.LIST_ACCOUNTS, result.failures.single().phase)
    }

    @Test
    fun transportExceptionsAreCapturedRatherThanThrown() = runTest {
        val client = ScriptedChannelClient(
            channels = listOf(summary("matrix")),
            accounts = mapOf("matrix" to listOf(matrixAccount())),
            throwOnStart = true,
        )

        val result = coordinator(client).restore()

        assertEquals(0, result.startedAccounts)
        assertFalse(result.isFullySuccessful)
    }

    /**
     * LANDMINE 2: `channel_accounts_list` returns plugin config (Matrix
     * accessToken / syncAccessToken) in CLEARTEXT. No token substring may reach
     * any diagnostic path — logs, telemetry attributes, the result object, or an
     * accidental toString of the frames.
     */
    @Test
    fun noAccountConfigOrTokenReachesLogsOrTelemetry() = runTest {
        Telemetry.clear()
        val logged = mutableListOf<String>()
        val client = ScriptedChannelClient(
            channels = listOf(summary("matrix")),
            accounts = mapOf("matrix" to listOf(matrixAccount())),
            startFailures = Int.MAX_VALUE,
            startError = "matrix plugin: whoami failed (fetch failed).",
        )

        val result = ChannelRestoreCoordinator(
            client = client,
            requestIdFactory = { "req" },
            sleep = {},
            log = { logged += it },
        ).restore()

        val telemetryDump = Telemetry.events.value
            .filter { it.tag == ChannelRestoreCoordinator.TELEMETRY_TAG }
            .joinToString("\n") { "${it.name} ${it.attrs}" }
        assertTrue(telemetryDump.isNotEmpty(), "restore must emit telemetry to make this assertion meaningful")

        val surfaces = logged + telemetryDump + result.toString() + client.accountFrames.toString()
        for (surface in surfaces) {
            assertFalse(secretAccessToken in surface, "token leaked into diagnostic surface: $surface")
            assertFalse(secretSyncToken in surface, "sync token leaked into diagnostic surface: $surface")
            assertFalse("homeserver.invalid" in surface, "config body leaked into diagnostic surface: $surface")
        }
        // Identifiers ARE expected — the diagnostic must stay useful.
        assertTrue(telemetryDump.contains("matrix"))
        assertTrue(telemetryDump.contains("lettabot"))
        Telemetry.clear()
    }

    @Test
    fun accountToStringWithholdsConfigButRedactedViewRedactsTokens() {
        val account = matrixAccount()

        val rendered = account.toString()

        assertFalse(secretAccessToken in rendered)
        assertTrue("keys withheld" in rendered)
        val redacted = account.redactedConfig().toString()
        assertFalse(secretAccessToken in redacted)
        assertFalse(secretSyncToken in redacted)
        assertTrue(AppServerProtocol.REDACTED_PLACEHOLDER in redacted)
    }

    @Test
    fun enableReassertPatchNeverCarriesConfig() = runTest {
        val client = ScriptedChannelClient(
            channels = listOf(summary("matrix")),
            accounts = mapOf("matrix" to listOf(matrixAccount())),
            startFailures = Int.MAX_VALUE,
        )

        coordinator(client).restore()

        assertTrue(client.updateCommands.isNotEmpty())
        for (command in client.updateCommands) {
            val encoded = AppServerProtocol.encodeCommand(command)
            assertFalse("config" in encoded, "enable re-assertion must not echo plugin config: $encoded")
            assertTrue("\"enabled\":true" in encoded)
        }
    }

    private fun coordinator(client: AppServerClient) = ChannelRestoreCoordinator(
        client = client,
        requestIdFactory = { "req" },
        sleep = {},
    )

    private fun summary(id: String, configured: Boolean = true) = AppServerChannelSummary(
        channelId = id,
        displayName = id,
        configured = configured,
        enabled = true,
        running = false,
    )
}

/**
 * Scripted App Server client replaying the shapes the lgns8.23.1 probe captured.
 * Records every command so the restore sequence can be asserted exactly.
 */
private class ScriptedChannelClient(
    private val channels: List<AppServerChannelSummary>,
    private val accounts: Map<String, List<AppServerChannelAccount>>,
    private val channelsListError: String? = null,
    private val accountsListErrors: Map<String, String> = emptyMap(),
    /** Number of leading channel_start calls that fail (per account). */
    private val startFailures: Int = 0,
    private val startError: String = "Failed to start channel",
    private val throwOnStart: Boolean = false,
) : AppServerClient {
    override val events: Flow<AppServerReceivedFrame> = emptyFlow()

    val accountListings = mutableListOf<String>()
    val starts = mutableListOf<String>()
    val enableReasserts = mutableListOf<String>()
    val updateCommands = mutableListOf<AppServerCommand.ChannelAccountUpdate>()

    /** Every accounts-list frame handed back, for the leak assertions. */
    val accountFrames = mutableListOf<AppServerInboundFrame.ChannelAccountsListResponse>()

    private val failuresLeft = mutableMapOf<String, Int>()

    override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) =
        throw UnsupportedOperationException("not used")

    override suspend fun input(command: AppServerCommand.Input) = Unit

    override suspend fun sync(command: AppServerCommand.Sync) =
        throw UnsupportedOperationException("not used")

    override suspend fun abort(command: AppServerCommand.AbortMessage) =
        throw UnsupportedOperationException("not used")

    override suspend fun adminRpc(command: AppServerCommand.AdminRpc) =
        throw UnsupportedOperationException("not used")

    override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit

    override suspend fun channelsList(
        command: AppServerCommand.ChannelsList,
    ): AppServerInboundFrame.ChannelsListResponse = AppServerInboundFrame.ChannelsListResponse(
        requestId = command.requestId,
        success = channelsListError == null,
        channels = channels,
        error = channelsListError,
    )

    override suspend fun channelAccountsList(
        command: AppServerCommand.ChannelAccountsList,
    ): AppServerInboundFrame.ChannelAccountsListResponse {
        accountListings += command.channelId
        val error = accountsListErrors[command.channelId]
        return AppServerInboundFrame.ChannelAccountsListResponse(
            requestId = command.requestId,
            success = error == null,
            channelId = command.channelId,
            accounts = accounts[command.channelId].orEmpty(),
            error = error,
        ).also { accountFrames += it }
    }

    override suspend fun channelStart(
        command: AppServerCommand.ChannelStart,
    ): AppServerInboundFrame.ChannelStartResponse {
        val key = "${command.channelId}/${command.accountId}"
        starts += key
        if (throwOnStart) throw IllegalStateException("socket closed")
        val remaining = failuresLeft.getOrPut(key) { startFailures }
        if (remaining > 0) {
            failuresLeft[key] = remaining - 1
            return AppServerInboundFrame.ChannelStartResponse(
                requestId = command.requestId,
                success = false,
                error = startError,
            )
        }
        return AppServerInboundFrame.ChannelStartResponse(
            requestId = command.requestId,
            success = true,
            channel = channels.first { it.channelId == command.channelId }.copy(running = true),
        )
    }

    override suspend fun channelAccountUpdate(
        command: AppServerCommand.ChannelAccountUpdate,
    ): AppServerInboundFrame.ChannelAccountUpdateResponse {
        updateCommands += command
        enableReasserts += "${command.channelId}/${command.accountId}:${command.patch.enabled}"
        return AppServerInboundFrame.ChannelAccountUpdateResponse(
            requestId = command.requestId,
            success = true,
            channelId = command.channelId,
        )
    }
}
