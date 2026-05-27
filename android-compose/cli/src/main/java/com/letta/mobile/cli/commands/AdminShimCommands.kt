package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.letta.mobile.cli.runtime.AdminShimRecorder
import com.letta.mobile.cli.runtime.CliJson
import com.letta.mobile.cli.runtime.CliConnection
import com.letta.mobile.cli.runtime.CliProfileStore
import com.letta.mobile.cli.runtime.CliRestClient
import com.letta.mobile.cli.runtime.CliWsSession
import com.letta.mobile.cli.runtime.ReplayInteractiveShell
import com.letta.mobile.cli.runtime.readImageAttachments
import com.letta.mobile.data.timeline.headless.HeadlessReplayDumpOptions
import com.letta.mobile.data.timeline.headless.HeadlessTimelineReplayer
import com.letta.mobile.data.timeline.headless.HeadlessTimelineStore
import com.letta.mobile.data.timeline.headless.HydrationReplayOrder
import com.letta.mobile.data.timeline.headless.TimelineAssertionOptions
import com.letta.mobile.data.transport.ChannelTransport
import com.letta.mobile.data.transport.RunCursorStore
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.WsChatBridge
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal abstract class AdminShimCommand(
    name: String,
    @Suppress("unused") help: String,
) : CliktCommand(name = name) {
    private val baseUrlOption by option(
        "--base-url",
        envvar = "LETTA_BASE_URL",
        help = "Admin-shim/Letta base URL."
    )

    private val tokenOption by option(
        "--token",
        envvar = "LETTA_TOKEN",
        help = "Bearer token."
    )

    private val profileName by option(
        "--profile",
        envvar = "LETTA_PROFILE",
        help = "CLI profile name. Defaults to active profile."
    )

    protected val deviceId by option("--device-id").default("letta-mobile-cli")
    protected val clientVersion by option("--client-version").default("letta-mobile-cli")

    protected val connection: CliConnection
        get() = CliProfileStore.default().resolve(profileName, baseUrlOption, tokenOption)

    protected val baseUrl: String get() = connection.baseUrl

    protected val token: String
        get() = connection.token ?: throw UsageError(
            "Missing token. Pass --token, set LETTA_TOKEN, or configure a CLI profile."
        )

    protected val optionalToken: String? get() = connection.token

    protected fun defaultAgentId(): String? = connection.profile?.defaultAgentId

    protected fun defaultConversationId(): String? = connection.profile?.defaultConversationId

    protected fun defaultProjectId(): String? = connection.profile?.defaultProjectId

    protected fun requireAgentId(value: String?): String =
        value ?: defaultAgentId() ?: throw UsageError(
            "Missing agent id. Pass --agent, set LETTA_AGENT_ID, or configure profile --agent."
        )

    protected fun requireConversationId(value: String?): String =
        value ?: defaultConversationId() ?: throw UsageError(
            "Missing conversation id. Pass --conversation, set LETTA_CONVERSATION_ID, or configure profile --conversation."
        )
}

internal class ConnectCommand : AdminShimCommand(
    name = "connect",
    help = "Open admin-shim mobile WebSocket, print welcome/session state, optionally hold.",
) {
    private val holdMs by option("--hold-ms", help = "Keep the socket open for this many ms after connect.").long().default(0)
    private val timeoutMs by option("--timeout-ms").long().default(5_000)
    private val conversation by option("--conversation", envvar = "LETTA_CONVERSATION_ID")
    private val runId by option("--run-id")
    private val resumeCursor by option("--resume-cursor").long()

    override fun run() = runBlocking {
        val cursorStore = RunCursorStore.inMemory()
        if (resumeCursor != null || runId != null) {
            val resolvedRunId = runId ?: throw UsageError("--resume-cursor requires --run-id")
            val resolvedConversationId = requireConversationId(conversation)
            cursorStore.record(resolvedConversationId, resolvedRunId, resumeCursor ?: 0)
        }
        val transport = ChannelTransport(cursorStore)
        val bridge = WsChatBridge(transport)
        val collector = launch {
            transport.events.collect { frame -> println("[frame] ${frame.typeName()}") }
        }
        try {
            bridge.connect(baseUrl, token, deviceId, clientVersion)
            withTimeout(timeoutMs) {
                bridge.state.filter { it is ChannelTransport.State.Connected }.first()
            }
            val connected = bridge.state.value as ChannelTransport.State.Connected
            println(
                "[connect] serverId=${connected.serverId} sessionId=${connected.sessionId} " +
                    "deviceId=${connected.deviceId ?: "<none>"} a2ui=${connected.a2uiEnabled} " +
                    "canonical=${connected.canonicalLiveTransport ?: "<unspecified>"}"
            )
            if (holdMs > 0) delay(holdMs)
        } finally {
            bridge.disconnect()
            collector.cancel()
        }
    }
}

internal class SendCommand : AdminShimCommand(
    name = "send",
    help = "Send a message through admin-shim WS and fold frames into the headless timeline.",
) {
    private val text by argument("text").optional()
    private val agentId by option("--agent", envvar = "LETTA_AGENT_ID")
    private val conversation by option("--conversation", envvar = "LETTA_CONVERSATION_ID")
    private val images by option(
        "--image",
        "-i",
        help = "Image file path or data:image/*;base64,... URL. Repeatable.",
    ).multiple()
    private val waitForStable by option("--wait-for-stable").flag(default = false)
    private val dumpTimeline by option("--dump-timeline").flag(default = false)
    private val timeoutMs by option("--timeout-ms").long().default(120_000)

    override fun run() = runBlocking {
        val rest = CliRestClient(baseUrl, token)
        try {
            val resolvedAgentId = requireAgentId(agentId)
            val conversationId = conversation ?: defaultConversationId() ?: rest.createConversation(resolvedAgentId).id
            val attachments = readImageAttachments(images)
            val messageText = text.orEmpty()
            if (messageText.isBlank() && attachments.isEmpty()) {
                throw UsageError("Pass text, --image, or both")
            }
            coroutineScope {
                val session = CliWsSession(
                    scope = this,
                    agentId = resolvedAgentId,
                    initialConversationId = conversationId,
                )
                session.startCollecting()
                try {
                    session.connect(baseUrl, token, deviceId, clientVersion, timeoutMs = 5_000)
                    session.send(
                        text = messageText,
                        attachments = attachments,
                        waitForStable = waitForStable,
                        timeoutMs = timeoutMs,
                    )
                    if (dumpTimeline) println(session.dump())
                } finally {
                    session.disconnect()
                }
            }
        } finally {
            rest.close()
        }
    }
}

internal class CaptureCommand : AdminShimCommand(
    name = "capture",
    help = "Capture REST hydrate snapshots and admin-shim mobile WS frames as replayable JSONL.",
) {
    private val shimUrl by option("--shim", help = "Admin-shim base URL. Alias for --base-url on this command.")
    private val out by option("--output", "--out").required()
    private val conversation by option("--conversation", envvar = "LETTA_CONVERSATION_ID")
    private val agentId by option("--agent", envvar = "LETTA_AGENT_ID")
    private val message by option("--message", "-m")
    private val runId by option("--run-id")
    private val cursor by option("--cursor").long().default(0)
    private val timeoutMs by option("--timeout-ms").long().default(120_000)
    private val restLimit by option("--rest-limit").long().default(200)
    private val skipRestSnapshot by option("--skip-rest-snapshot").flag(default = false)
    private val fromPhone by option("--from-phone").flag(default = false)
    private val adbSerial by option("--adb")

    override fun run() = runBlocking {
        if (fromPhone || adbSerial != null) {
            throw UsageError(
                "--from-phone/--adb capture requires a device-side diagnostic channel that is not " +
                    "available yet. Use --shim capture against the admin-shim WS fixture path."
            )
        }
        val captureBaseUrl = shimUrl ?: baseUrl
        val conversationId = requireConversationId(conversation)
        val resolvedAgentId = if (message != null) requireAgentId(agentId) else agentId ?: defaultAgentId()
        val restMessages = if (skipRestSnapshot) {
            null
        } else {
            val rest = CliRestClient(captureBaseUrl, token)
            try {
                rest.fetchMessages(conversationId, restLimit.validatedIntLimit())
            } finally {
                rest.close()
            }
        }
        val count = AdminShimRecorder().record(
            baseUrl = captureBaseUrl,
            token = token,
            agentId = resolvedAgentId,
            conversationId = conversationId,
            message = message,
            attachments = emptyList(),
            runId = runId,
            cursor = cursor,
            out = Path.of(out),
            timeoutMs = timeoutMs,
            deviceId = deviceId,
            clientVersion = clientVersion,
            restSnapshot = restMessages,
            recordCursorEvents = true,
        )
        println(
            "[capture] wrote $count events to $out " +
                "(restSnapshot=${restMessages?.size ?: 0} messages, conversation=$conversationId)"
        )
    }
}

internal class DumpTimelineCommand : AdminShimCommand(
    name = "dump-timeline",
    help = "Fetch conversation history and emit stable, diffable timeline JSON.",
) {
    private val conversation by option("--conversation", envvar = "LETTA_CONVERSATION_ID")
    private val limit by option("--limit").long().default(200)

    override fun run() = runBlocking {
        val rest = CliRestClient(baseUrl, token)
        try {
            val conversationId = requireConversationId(conversation)
            val messages = rest.fetchMessages(conversationId, limit.validatedIntLimit())
            val store = HeadlessTimelineStore()
            store.hydrate(conversationId, messages)
            println(store.dumpJson(conversationId))
        } finally {
            rest.close()
        }
    }
}

internal class ReplayCommand : AdminShimCommand(
    name = "replay",
    help = "Replay a recorded WS JSONL fixture through the reducer.",
) {
    private val recording by option("--recording").required()
    private val conversation by option("--conversation", envvar = "LETTA_CONVERSATION_ID")
    private val assertNoDups by option("--assert-no-dups").flag(default = false)
    private val assertOtidUnique by option("--assert-otid-unique").flag(default = false)
    private val assertSeqMonotonic by option("--assert-seq-monotonic").flag(default = false)
    private val assertNoEmptyBodies by option("--assert-no-empty-bodies").flag(default = false)
    private val assertNoPrefixOrphans by option("--assert-no-prefix-orphans").flag(default = false)
    private val assertUiMessageCountPerRun by option("--assert-ui-message-count-per-run").int()
    private val assertFinalStatusMatches by option("--assert-final-status-matches")
    private val assertNoOrphanToolReturns by option("--assert-no-orphan-tool-returns").flag(default = false)
    private val assertRunCompletes by option("--assert-run-completes").flag(default = false)
    private val assertNoAbandonedToolCalls by option("--assert-no-abandoned-tool-calls").flag(default = false)
    private val assertApprovalToolReturnOnApprovalRun by option(
        "--assert-approval-tool-return-on-approval-run"
    ).flag(default = false)
    private val assertOtidStableAcrossRetry by option("--assert-otid-stable-across-retry").flag(default = false)
    private val dumpTimeline by option("--dump-timeline").flag(default = false)
    private val dumpAfterEachFrame by option("--dump-after-each-frame").flag(default = false)
    private val dumpAfterFrame by option("--dump-after-frame").int()
    private val dumpFrames by option("--dump-frames")
    private val hydrationOrder by option(
        "--hydration-order",
        help = "REST/WS sequencing for mixed hydration recordings: rest-first, ws-first, or interleaved."
    ).default(HydrationReplayOrder.INTERLEAVED.cliValue)
    private val interactive by option("--interactive").flag(default = false)
    private val bisectFrame by option("--bisect-frame").flag(default = false)
    private val bisectOut by option("--bisect-out")
    private val resumeFromCursor by option("--resume-from-cursor").long()
    private val assertNoGapOnResume by option("--assert-no-gap-on-resume").flag(default = false)
    private val assertNoDupOnResume by option("--assert-no-dup-on-resume").flag(default = false)
    private val assertCursorExpiredGraceful by option("--assert-cursor-expired-graceful").flag(default = false)
    private val assertIsStreamingClears by option("--assert-isStreaming-clears-by-terminal-frame").flag(default = false)
    private val assertNoLocksHeldAfterTerminal by option("--assert-no-locks-held-after-terminal").flag(default = false)
    private val assertTypingIndicatorState by option("--assert-typing-indicator-state").flag(default = false)
    private val assertNoOrphanedRunTracker by option("--assert-no-orphaned-run-tracker").flag(default = false)
    private val assertTerminalFrameReceived by option("--assert-terminal-frame-received").flag(default = false)
    private val assertAllState by option("--assert-all").flag(default = false)
    private val traceStateTransitions by option("--trace-state-transitions").flag(default = false)

    override fun run() = runBlocking {
        val replayOrder = hydrationOrder.validatedHydrationOrder()
        val assertionOptions = TimelineAssertionOptions(
            assertNoDuplicateUiMessages = assertNoDups,
            assertOtidUnique = assertOtidUnique,
            assertSeqMonotonic = assertSeqMonotonic,
            resumeFromCursor = resumeFromCursor.validatedNonNegativeLongOrNull("--resume-from-cursor"),
            assertNoGapOnResume = assertNoGapOnResume,
            assertNoDupOnResume = assertNoDupOnResume,
            assertCursorExpiredGraceful = assertCursorExpiredGraceful,
            assertIsStreamingClearsByTerminalFrame = assertIsStreamingClears || assertAllState,
            assertNoLocksHeldAfterTerminal = assertNoLocksHeldAfterTerminal || assertAllState,
            assertTypingIndicatorState = assertTypingIndicatorState || assertAllState,
            assertNoOrphanedRunTracker = assertNoOrphanedRunTracker || assertAllState,
            assertTerminalFrameReceived = assertTerminalFrameReceived || assertAllState,
            traceStateTransitions = traceStateTransitions,
            assertNoEmptyBodies = assertNoEmptyBodies,
            assertNoPrefixOrphans = assertNoPrefixOrphans,
            expectedUiMessageCountPerRun = assertUiMessageCountPerRun.validatedPositiveOrNull(
                "--assert-ui-message-count-per-run"
            ),
            expectedFinalStatus = assertFinalStatusMatches.validatedFinalStatusOrNull(),
            assertNoOrphanToolReturns = assertNoOrphanToolReturns,
            assertRunCompletes = assertRunCompletes,
            assertNoAbandonedToolCalls = assertNoAbandonedToolCalls,
            assertApprovalToolReturnOnApprovalRun = assertApprovalToolReturnOnApprovalRun,
            assertOtidStableAcrossRetry = assertOtidStableAcrossRetry,
        )
        if (interactive) {
            validateResumeAssertions(assertionOptions)
            val conversationId = requireConversationId(conversation)
            ReplayInteractiveShell(
                recording = Path.of(recording),
                conversationId = conversationId,
                defaultAssertionOptions = assertionOptions,
            ).run()
            return@runBlocking
        }
        val conversationId = requireConversationId(conversation)
        validateResumeAssertions(assertionOptions)
        if (bisectFrame) {
            if (!assertionOptions.hasAssertions()) {
                throw UsageError("--bisect-frame requires at least one replay assertion flag")
            }
            val result = HeadlessTimelineReplayer().bisectFailingJsonl(
                conversationId = conversationId,
                lines = Files.readAllLines(Path.of(recording)),
                assertionOptions = assertionOptions,
            )
            if (result.fullReplayPassed) {
                println("[bisect] full replay passed; no failing frame set to minimize")
                return@runBlocking
            }
            println(
                "[bisect] original=${result.originalLineCount} kept=${result.keptLineCount} " +
                    "removed=${result.removedOriginalIndexes.size}"
            )
            println("[bisect] kept original line indexes=${result.keptOriginalIndexes.joinToString(",")}")
            println("[bisect] removed original line indexes=${result.removedOriginalIndexes.joinToString(",")}")
            println("[bisect] failures:")
            result.finalFailures.forEach { println("[bisect] FAIL $it") }
            bisectOut?.let { path ->
                Files.write(Path.of(path), result.keptLines)
                println("[bisect] wrote minimized fixture to $path")
            }
            return@runBlocking
        }
        val dumpOptions = HeadlessReplayDumpOptions(
            dumpAfterEachFrame = dumpAfterEachFrame,
            dumpAfterFrame = dumpAfterFrame.validatedNonNegativeOrNull("--dump-after-frame"),
            dumpFrames = dumpFrames.parseFrameSet(),
        )
        val result = Files.newBufferedReader(Path.of(recording)).use { reader ->
            HeadlessTimelineReplayer().replayJsonl(
                conversationId = conversationId,
                lines = reader.lineSequence(),
                assertionOptions = assertionOptions,
                hydrationOrder = replayOrder,
                dumpOptions = dumpOptions,
            )
        }
        val statusOut = if (dumpOptions.enabled) System.err else System.out
        val hydrationStatus = if (result.hydrationsApplied > 0) {
            " hydrations=${result.hydrationsApplied} hydrated_messages=${result.messagesHydrated}"
        } else {
            ""
        }
        statusOut.println(
            "[replay] frames=${result.framesSeen} ingested=${result.messagesIngested}$hydrationStatus " +
                "events=${result.assertionReport.eventCount}"
        )
        if (result.ignoredFrameTypes.isNotEmpty()) {
            statusOut.println("[replay] ignored=${result.ignoredFrameTypes}")
        }
        if (traceStateTransitions) {
            result.stateTransitions.forEach { statusOut.println("[state] ${it.toTraceLine()}") }
            if (result.stateTransitions.isEmpty()) {
                statusOut.println("[state] no state transitions observed")
            }
        }
        if (!result.assertionReport.passed) {
            result.assertionReport.failures.forEach { statusOut.println("[replay] FAIL $it") }
            throw IllegalStateException("replay assertions failed")
        }
        statusOut.println("[replay] assertions passed")
        if (dumpOptions.enabled) {
            println(result.frameSnapshotsJson())
            if (dumpTimeline) {
                System.err.println("[replay] final timeline:")
                System.err.println(result.timelineJson)
            }
        } else if (dumpTimeline) {
            println(result.timelineJson)
        }
    }
}

internal class RecordCommand : AdminShimCommand(
    name = "record",
    help = "Record admin-shim mobile WS wire frames to replay-compatible JSONL.",
) {
    private val out by option("--out").required()
    private val agentId by option("--agent", envvar = "LETTA_AGENT_ID")
    private val conversation by option("--conversation", envvar = "LETTA_CONVERSATION_ID")
    private val message by option("--message", "-m")
    private val images by option(
        "--image",
        "-i",
        help = "Image file path or data:image/*;base64,... URL. Repeatable; can be used with or without --message.",
    ).multiple()
    private val runId by option("--run-id")
    private val cursor by option("--cursor").long().default(0)
    private val timeoutMs by option("--timeout-ms").long().default(120_000)

    override fun run() = runBlocking {
        val attachments = readImageAttachments(images)
        val shouldSendMessage = message != null || attachments.isNotEmpty()
        val resolvedAgentId = if (shouldSendMessage) requireAgentId(agentId) else agentId ?: defaultAgentId()
        val resolvedConversationId = if (shouldSendMessage) {
            requireConversationId(conversation)
        } else {
            conversation ?: defaultConversationId()
        }
        val count = AdminShimRecorder().record(
            baseUrl = baseUrl,
            token = token,
            agentId = resolvedAgentId,
            conversationId = resolvedConversationId,
            message = message,
            attachments = attachments,
            runId = runId,
            cursor = cursor,
            out = Path.of(out),
            timeoutMs = timeoutMs,
            deviceId = deviceId,
            clientVersion = clientVersion,
        )
        println("[record] wrote $count frames to $out")
    }
}

internal class RecordCursorStateCommand : CliktCommand(name = "record-cursor-state") {
    private val recording by option("--recording").required()

    override fun run() {
        val output = buildCursorStateSnapshot(Files.readAllLines(Path.of(recording)))
        println(CliJson.encodeToString(JsonObject.serializer(), output))
    }
}

internal class DisconnectCommand : AdminShimCommand(
    name = "disconnect",
    help = "Open the admin-shim WS and close it cleanly with bye.",
) {
    override fun run() = runBlocking {
        val transport = ChannelTransport(RunCursorStore.inMemory())
        val bridge = WsChatBridge(transport)
        bridge.connect(baseUrl, token, deviceId, clientVersion)
        withTimeout(5_000) {
            bridge.state.filter { it is ChannelTransport.State.Connected }.first()
        }
        println("[disconnect] connected; sending bye")
        bridge.bye()
        bridge.disconnect()
        println("[disconnect] closed")
    }
}

internal class ReconnectCommand : AdminShimCommand(
    name = "reconnect",
    help = "Connect, disconnect, then reconnect; optionally seed a run cursor to exercise resume.",
) {
    private val conversation by option("--conversation", envvar = "LETTA_CONVERSATION_ID")
    private val runId by option("--run-id")
    private val cursor by option("--cursor").long().default(0)
    private val holdMs by option("--hold-ms").long().default(1_000)

    override fun run() = runBlocking {
        val conversationId = conversation ?: defaultConversationId()
        val cursorStore = RunCursorStore.inMemory()
        if (conversationId != null && runId != null && cursor > 0) {
            cursorStore.record(conversationId, runId.orEmpty(), cursor)
        }
        val transport = ChannelTransport(cursorStore)
        val bridge = WsChatBridge(transport)
        val collector = launch {
            transport.events.collect { frame -> println("[frame] ${frame.typeName()}") }
        }
        try {
            bridge.connect(baseUrl, token, deviceId, clientVersion)
            withTimeout(5_000) { bridge.state.filter { it is ChannelTransport.State.Connected }.first() }
            println("[reconnect] first connection up")
            bridge.disconnect()
            println("[reconnect] disconnected")
            bridge.connect(baseUrl, token, deviceId, clientVersion)
            withTimeout(5_000) { bridge.state.filter { it is ChannelTransport.State.Connected }.first() }
            println("[reconnect] second connection up")
            delay(holdMs)
        } finally {
            bridge.disconnect()
            collector.cancel()
        }
    }
}

private fun ServerFrame.typeName(): String = when (this) {
    is ServerFrame.Welcome -> "welcome"
    is ServerFrame.Error -> "error:${code}"
    is ServerFrame.Ping -> "ping"
    is ServerFrame.TurnStarted -> "turn_started runId=$runId"
    is ServerFrame.TurnDone -> "turn_done runId=$runId status=$status"
    is ServerFrame.StopReason -> "stop_reason $stopReason"
    is ServerFrame.UsageStatistics -> "usage_statistics total=$totalTokens"
    is ServerFrame.AssistantMessage -> "assistant_message id=$id seq=${seqId ?: "<none>"}"
    is ServerFrame.ReasoningMessage -> "reasoning_message id=$id"
    is ServerFrame.ToolCallMessage -> "tool_call_message id=$id"
    is ServerFrame.ToolReturnMessage -> "tool_return_message id=$id"
    is ServerFrame.SubscribeFrameMessage -> "subscribe_frame runId=$runId seq=$seq"
    is ServerFrame.SubscribeDone -> "subscribe_done runId=$runId lastSeq=$lastSeq"
    is ServerFrame.A2ui -> "a2ui_frame id=$id"
    is ServerFrame.A2uiCapabilities -> "a2ui_capabilities version=$version"
    is ServerFrame.UserActionAck -> "user_action_ack status=$status"
    is ServerFrame.UserActionOutcome -> "user_action_outcome outcome=$outcome"
    is ServerFrame.CronListResponse -> "cron_list_response success=$success"
    is ServerFrame.CronAddResponse -> "cron_add_response success=$success"
    is ServerFrame.CronGetResponse -> "cron_get_response success=$success"
    is ServerFrame.CronDeleteResponse -> "cron_delete_response success=$success"
    is ServerFrame.CronDeleteAllResponse -> "cron_delete_all_response success=$success"
    is ServerFrame.CronsUpdated -> "crons_updated reason=$reason"
    is ServerFrame.Unknown -> "unknown:$type"
}

private fun Long.validatedIntLimit(): Int {
    if (this !in 1..Int.MAX_VALUE.toLong()) {
        throw UsageError("--limit must be between 1 and ${Int.MAX_VALUE}")
    }
    return toInt()
}

private fun Int?.validatedNonNegativeOrNull(optionName: String): Int? {
    if (this != null && this < 0) throw UsageError("$optionName must be >= 0")
    return this
}

private fun Int?.validatedPositiveOrNull(optionName: String): Int? {
    if (this != null && this < 1) throw UsageError("$optionName must be >= 1")
    return this
}

private fun Long?.validatedNonNegativeLongOrNull(optionName: String): Long? {
    if (this != null && this < 0) throw UsageError("$optionName must be >= 0")
    return this
}

private fun String?.validatedFinalStatusOrNull(): String? {
    if (this == null) return null
    val normalized = trim().lowercase()
    if (normalized !in setOf("completed", "cancelled", "failed")) {
        throw UsageError("--assert-final-status-matches must be completed, cancelled, or failed")
    }
    return normalized
}

private fun String.validatedHydrationOrder(): HydrationReplayOrder =
    HydrationReplayOrder.fromCliValue(this) ?: throw UsageError(
        "--hydration-order must be rest-first, ws-first, or interleaved"
    )

private fun String?.parseFrameSet(): Set<Int> {
    if (this.isNullOrBlank()) return emptySet()
    return split(",").map { raw ->
        val value = raw.trim().toIntOrNull()
            ?: throw UsageError("--dump-frames must be a comma-separated list of frame indices")
        value.validatedNonNegativeOrNull("--dump-frames") ?: value
    }.toSet()
}

private fun TimelineAssertionOptions.hasAssertions(): Boolean =
    assertNoDuplicateUiMessages ||
        assertOtidUnique ||
        assertSeqMonotonic ||
        assertNoGapOnResume ||
        assertNoDupOnResume ||
        assertCursorExpiredGraceful ||
        assertIsStreamingClearsByTerminalFrame ||
        assertNoLocksHeldAfterTerminal ||
        assertTypingIndicatorState ||
        assertNoOrphanedRunTracker ||
        assertTerminalFrameReceived ||
        assertNoEmptyBodies ||
        assertNoPrefixOrphans ||
        expectedUiMessageCountPerRun != null ||
        expectedFinalStatus != null ||
        assertNoOrphanToolReturns ||
        assertRunCompletes ||
        assertNoAbandonedToolCalls ||
        assertApprovalToolReturnOnApprovalRun ||
        assertOtidStableAcrossRetry

private fun validateResumeAssertions(options: TimelineAssertionOptions) {
    if ((options.assertNoGapOnResume || options.assertNoDupOnResume) && options.resumeFromCursor == null) {
        throw UsageError("--assert-no-gap-on-resume/--assert-no-dup-on-resume require --resume-from-cursor")
    }
}

internal fun buildCursorStateSnapshot(lines: Iterable<String>): JsonObject {
    val cursors = linkedMapOf<String, LinkedHashMap<String, Long>>()
    lines.forEach { line ->
        val obj = runCatching { CliJson.parseToJsonElement(line).jsonObject }.getOrNull()
            ?: return@forEach
        val cursorEvent = obj.takeIf { it["kind"]?.jsonPrimitive?.contentOrNull == "cursor" }
        if (cursorEvent != null) {
            cursorEvent.recordCursorInto(cursors)
            return@forEach
        }
        val frame = (obj["frame"] as? JsonObject) ?: obj.takeIf { it["type"] != null } ?: return@forEach
        frame.recordCursorInto(cursors)
    }
    return buildJsonObject {
        cursors.forEach { (conversationId, runs) ->
            put(conversationId, buildJsonObject {
                runs.forEach { (runId, seq) -> put(runId, seq) }
            })
        }
    }
}

private fun JsonObject.recordCursorInto(cursors: MutableMap<String, LinkedHashMap<String, Long>>) {
    val innerFrame = this["frame"] as? JsonObject
    val conversationId = this["conversation_id"]?.jsonPrimitive?.contentOrNull
        ?: innerFrame?.get("conversation_id")?.jsonPrimitive?.contentOrNull
        ?: return
    val runId = this["run_id"]?.jsonPrimitive?.contentOrNull
        ?: innerFrame?.get("run_id")?.jsonPrimitive?.contentOrNull
        ?: return
    val seq = this["seq"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?: innerFrame?.get("seq")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?: this["seq_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?: innerFrame?.get("seq_id")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?: return
    val perConversation = cursors.getOrPut(conversationId) { linkedMapOf() }
    val existing = perConversation[runId]
    if (existing == null || seq > existing) {
        perConversation[runId] = seq
    }
}
