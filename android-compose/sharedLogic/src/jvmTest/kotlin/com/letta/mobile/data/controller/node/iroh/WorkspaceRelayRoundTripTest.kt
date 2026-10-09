package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.memory.memfs.AppServerMemfsSource
import com.letta.mobile.data.memory.memfs.MemfsCommitRef
import com.letta.mobile.data.memory.memfs.MemfsException
import com.letta.mobile.data.memory.memfs.MemfsFileRef
import com.letta.mobile.data.secrets.AgentSecretChanges
import com.letta.mobile.data.secrets.AgentSecretsException
import com.letta.mobile.data.secrets.AppServerAgentSecretsSource
import com.letta.mobile.data.secrets.SecretValue
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerFileCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerMemfsCommand
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerSecretCommand
import com.letta.mobile.data.transport.appserver.AppServerWorkspaceCommand
import com.letta.mobile.data.transport.appserver.WorkspaceRelay
import com.letta.mobile.data.transport.appserver.WorkspaceRelayAccess
import com.letta.mobile.data.transport.appserver.WorkspaceRelayMethod
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import com.letta.mobile.data.workspace.AppServerWorkspaceFileSource
import com.letta.mobile.data.workspace.WorkspaceFileContent
import com.letta.mobile.data.workspace.relay.WorkspaceRelayCall
import com.letta.mobile.data.workspace.relay.WorkspaceRelayClient
import com.letta.mobile.util.Telemetry
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * letta-mobile-bzvro.37: an Iroh client's workspace sources against a host relaying to its App
 * Server, over the host's real admin_rpc stream server (frame codec, capability gate, router), with
 * the App Server faked. Proves the relay forwards exactly the commands the direct path sends, that
 * the capability matrix applies, and that secret values never reach telemetry.
 */
class WorkspaceRelayRoundTripTest {
    /** Everything telemetry holds (its ring keeps the newest events). */
    private fun recordedTelemetry(): String = Telemetry.events.value.joinToString("\n")

    @Test
    fun theMemoryBrowserWorksOverTheRelay() = runTest {
        val server = FakeAppServer()
        val memfs = AppServerMemfsSource(client = { client(server) }, events = emptyFlow(), requestId = { "client-$it" })

        val listing = memfs.list("agent-1")
        val body = memfs.read(MemfsFileRef("agent-1", "system/human/communication_style.md"))
        val history = memfs.history(com.letta.mobile.data.memory.memfs.MemfsHistoryScope("agent-1", "system/human/communication_style.md"))
        val diff = memfs.commitDiff(MemfsCommitRef("agent-1", "abc123"))
        val sha = memfs.write(MemfsFileRef("agent-1", "notes.md"), "hello")

        assertEquals(listOf("system/human/communication_style.md", "notes.md"), listing.files.map { it.path })
        assertEquals("---\nstyle\n", body)
        assertEquals(listOf("abc123"), history.map { it.sha })
        assertEquals("diff --git", diff)
        assertEquals("def456", sha)
        // The App Server saw exactly the commands a direct session sends, under the host's request ids.
        assertEquals(
            listOf("list_memory", "read_memory_file", "memory_history", "memory_commit_diff", "write_memory_file"),
            server.received.map { it.first },
        )
        assertTrue(server.received.none { (_, requestId) -> requestId.startsWith("client-") })
    }

    @Test
    fun workspaceFilesAreSearchedAndReadOverTheRelay() = runTest {
        val server = FakeAppServer()
        val files = AppServerWorkspaceFileSource(client = { client(server) }, requestId = { it })

        assertEquals(listOf("src/main.kt"), files.search("main", "/repo", 25))
        assertEquals(WorkspaceFileContent.Text("/repo/README.md", "# readme"), files.read("/repo/README.md"))
    }

    @Test
    fun secretsRoundTripButTheirValuesNeverReachTelemetryOrTheHostsErrors() = runTest {
        val server = FakeAppServer()
        val secrets = AppServerAgentSecretsSource(client = { client(server) }, requestId = { it })

        val listed = secrets.list("agent-1")
        secrets.apply("agent-1", AgentSecretChanges(set = mapOf("NEW_KEY" to SecretValue("N3W-S3CRET")), unset = emptySet()))

        assertEquals("L1ST-S3CRET", listed.single().value.reveal())
        assertEquals(mapOf("NEW_KEY" to "N3W-S3CRET"), server.applied.single())
        val recorded = recordedTelemetry()
        assertTrue(recorded.contains("secret.list"), "the relay is observable by method")
        assertFalse(recorded.contains("S3CRET"), "secret values reached telemetry:\n$recorded")
    }

    @Test
    fun aHostAppServerFailureIsAFixedSentenceNotTheUpstreamText() = runTest {
        val server = FakeAppServer(failWith = IllegalStateException("upstream said S3CRET"))
        val secrets = AppServerAgentSecretsSource(client = { client(server) }, requestId = { it })

        val error = assertFailsWith<AgentSecretsException> { secrets.list("agent-1") }
        assertEquals("secret_list failed on the host", error.message)
        assertFalse(recordedTelemetry().contains("S3CRET"))
    }

    @Test
    fun theCapabilityMatrixGatesEachFamily() = runTest {
        val server = FakeAppServer()
        val desktopRole = IrohPeerCapabilities.DEFAULT_DESKTOP_ROLE
        // A paired desktop reads and writes memory ...
        AppServerMemfsSource(client = { client(server, desktopRole) }, events = emptyFlow(), requestId = { it }).list("agent-1")
        // ... but reads host files and manages secrets only with admin.full; a denial never reaches the App Server.
        val before = server.received.size
        val filesDenied = assertFailsWith<com.letta.mobile.data.workspace.WorkspaceFileException> {
            AppServerWorkspaceFileSource(client = { client(server, desktopRole) }, requestId = { it }).read("/etc/passwd")
        }
        assertEquals(WorkspaceRelayClient.forbidden(WorkspaceRelayAccess.Files), filesDenied.message)
        val denied = assertFailsWith<AgentSecretsException> {
            AppServerAgentSecretsSource(client = { client(server, desktopRole) }, requestId = { it }).list("agent-1")
        }
        assertEquals(WorkspaceRelayClient.forbidden(WorkspaceRelayAccess.Secrets), denied.message)
        assertEquals(before, server.received.size)

        val readOnly = setOf(IrohPeerCapabilities.CHAT_READ)
        val memoryDenied = assertFailsWith<MemfsException> {
            AppServerMemfsSource(client = { client(server, readOnly) }, events = emptyFlow(), requestId = { it }).list("agent-1")
        }
        assertEquals(WorkspaceRelayClient.forbidden(WorkspaceRelayAccess.MemoryRead), memoryDenied.message)
    }

    @Test
    fun aHostWithoutTheRelayAsksForAnUpdateAndOneWithoutAnAppServerSaysSo() = runTest {
        val outdated = AppServerMemfsSource(
            client = { WorkspaceRelayClient(hostCall(AdminRpcRouter(), setOf(IrohPeerCapabilities.ADMIN_FULL))) },
            events = emptyFlow(),
            requestId = { it },
        )
        assertEquals(WorkspaceRelayClient.HOST_NEEDS_UPDATE, assertFailsWith<MemfsException> { outdated.list("agent-1") }.message)

        val noAppServer = AdminRpcRouter().also { WorkspaceAdminHandlers.register(it, nativeClient = null) }
        assertFalse(WorkspaceRelay.CAPABILITY in noAppServer.featureCapabilities)
        val unavailable = AppServerMemfsSource(
            client = { WorkspaceRelayClient(hostCall(noAppServer, setOf(IrohPeerCapabilities.ADMIN_FULL))) },
            events = emptyFlow(),
            requestId = { it },
        )
        assertTrue(assertFailsWith<MemfsException> { unavailable.list("agent-1") }.message.orEmpty().contains("capability_unavailable"))
    }

    @Test
    fun aToolCardsRelativeMemoryPathResolvesUnderTheAgentsMemoryRoot() = runTest {
        val root = memoryRoot()
        val server = FakeAppServer(memoryRoot = root)
        val viewer = com.letta.mobile.data.workspace.WorkspaceFileViewerController(
            AppServerWorkspaceFileSource(client = { client(server) }, requestId = { it }),
            backgroundScope,
        )

        viewer.open("system/human/communication_style.md", cwd = null, memoryAgentId = "agent-1")
        // The host's stream server answers on its own dispatcher; wait for the read to settle.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) { viewer.state.first { !it.loading } }
        }

        assertEquals(
            WorkspaceFileContent.Text("system/human/communication_style.md", "Be brief."),
            viewer.state.value.content,
            "viewer state: ${viewer.state.value}",
        )
        // Read from the agent's memory root (read_memory_file), never relative to the host's cwd (read_file).
        assertEquals(listOf("read_memory_file"), server.received.map { it.first })
    }

    @Test
    fun memoryPathsThatEscapeTheRootNeverReachTheAppServer() = runTest {
        val server = FakeAppServer(memoryRoot = memoryRoot())
        val files = AppServerWorkspaceFileSource(client = { client(server) }, requestId = { it })

        listOf("../../etc/passwd", "/etc/passwd", "%2e%2e/%2e%2e/etc/passwd", "system/../../../etc/passwd", "C:\\Windows\\win.ini").forEach { path ->
            val error = assertFailsWith<com.letta.mobile.data.workspace.WorkspaceFileException>(path) { files.readMemory("agent-1", path) }
            assertTrue(error.message.orEmpty().contains("not allowed"), "$path: ${error.message}")
        }
        // A relative read_file would resolve against the host process's cwd: refused too.
        assertFailsWith<com.letta.mobile.data.workspace.WorkspaceFileException> { files.read("system/human/communication_style.md") }
        assertEquals(emptyList(), server.received)
    }

    @Test
    fun aMissingMemoryFileIsATypedNotFoundWithoutTheHostPath() = runTest {
        val root = memoryRoot()
        val files = AppServerWorkspaceFileSource(client = { client(FakeAppServer(memoryRoot = root)) }, requestId = { it })

        val error = assertFailsWith<com.letta.mobile.data.workspace.WorkspaceFileException> { files.readMemory("agent-1", "system/missing.md") }

        assertEquals(com.letta.mobile.data.workspace.WorkspaceFileErrors.NOT_FOUND, error.message)
        assertFalse(error.toString().contains("ENOENT") || error.toString().contains(root.toString()))
    }

    @Test
    fun gitInternalsAndUnsafeAgentIdsNeverReachTheAppServer() = runTest {
        val server = FakeAppServer(memoryRoot = memoryRoot())
        val memfs = AppServerMemfsSource(client = { client(server) }, events = emptyFlow(), requestId = { it })
        val files = AppServerWorkspaceFileSource(client = { client(server) }, requestId = { it })
        val readPaths = listOf(
            ".git/config", ".GIT/config", ".git./config", ".git /config", "sub/.git/hooks/x", ".git\\config", "sub\\.git\\hooks\\x", ".Git...\\config",
            "GIT~1/config", "git~1/config", "GIT~2/hooks/x", "sub\\GIT~1\\config",
        )
        readPaths.forEach { path ->
            assertFailsWith<MemfsException>(path) { memfs.read(MemfsFileRef("agent-1", path)) }
            assertFailsWith<MemfsException>(path) { memfs.write(MemfsFileRef("agent-1", path), "[filter \"x\"]") }
            assertFailsWith<MemfsException>(path) { memfs.history(com.letta.mobile.data.memory.memfs.MemfsHistoryScope("agent-1", path)) }
            assertFailsWith<MemfsException>(path) { memfs.fileAtRef(MemfsFileRef("agent-1", path), "HEAD") }
            assertFailsWith<com.letta.mobile.data.workspace.WorkspaceFileException>(path) { files.readMemory("agent-1", path) }
        }
        listOf(".gitattributes", ".GITATTRIBUTES", "sub/.gitmodules", "sub\\.gitattributes.", ".gitmodules ", "GITATT~1", "GITMOD~1").forEach { path ->
            assertFailsWith<MemfsException>(path) { memfs.write(MemfsFileRef("agent-1", path), "* filter=x") }
        }
        listOf(".", "..", "../agent-2", "a/b", "a\\b", "a".repeat(WorkspaceRelay.MAX_ID_CHARS + 1), "a b", "").forEach { agentId ->
            assertFailsWith<MemfsException>("agent id '$agentId'") { memfs.list(agentId) }
            assertFailsWith<MemfsException>("agent id '$agentId'") { memfs.read(MemfsFileRef(agentId, "x.md")) }
        }
        assertEquals(emptyList(), server.received)
    }

    @Test
    fun everyMemfsFailureIsAFixedSentenceWithoutTheHostPath() {
        val raw = Json.parseToJsonElement(
            """{"type":"read_memory_file_response","request_id":"h","success":false,"error":"EACCES: permission denied, open '/root/.letta/agents/a/memory/x.md'"}""",
        ).jsonObject
        val memfs = WorkspaceRelay.normalizeFrame(WorkspaceRelayMethod.ReadMemoryFile, raw)
        assertEquals(WorkspaceRelay.MEMFS_FAILED_MESSAGE, (memfs["error"] as JsonPrimitive).content)
        assertFalse(memfs.toString().contains("/root"))
        // Not a memfs method: only the missing-file answer is rewritten.
        assertEquals(raw, WorkspaceRelay.normalizeFrame(WorkspaceRelayMethod.SecretList, raw))
    }

    @Test
    fun theHostTypesAMissingFileAnswer() {
        val raw = Json.parseToJsonElement(
            """{"type":"read_file_response","request_id":"h","success":false,"error":"ENOENT: no such file or directory, open '/root/x'"}""",
        ).jsonObject
        val normalized = WorkspaceRelay.normalizeFrame(WorkspaceRelayMethod.ReadFile, raw)
        assertEquals(WorkspaceRelay.NOT_FOUND_CODE, (normalized["error_code"] as JsonPrimitive).content)
        assertFalse(normalized.toString().contains("/root/x"))
        val ok = Json.parseToJsonElement("""{"type":"read_file_response","success":true,"content":"no such file in prose"}""").jsonObject
        assertEquals(ok, WorkspaceRelay.normalizeFrame(WorkspaceRelayMethod.ReadFile, ok))
    }

    private fun memoryRoot(): java.nio.file.Path {
        val root = java.nio.file.Files.createTempDirectory("memfs-root").toRealPath()
        val file = root.resolve("system/human/communication_style.md")
        java.nio.file.Files.createDirectories(file.parent)
        java.nio.file.Files.writeString(file, "Be brief.")
        return root
    }

    @Test
    fun theHostAdvertisesTheRelay() {
        val router = AdminRpcRouter().also { WorkspaceAdminHandlers.register(it, FakeAppServer()) }
        assertTrue(WorkspaceRelay.CAPABILITY in IrohNodeConnection.advertisedCapabilities(router))
    }

    private fun CoroutineScope.client(server: AppServerClient, capabilities: Set<String> = setOf(IrohPeerCapabilities.ADMIN_FULL)): AppServerClient {
        val router = AdminRpcRouter().also { WorkspaceAdminHandlers.register(it, server) }
        return WorkspaceRelayClient(hostCall(router, capabilities))
    }

    /** The host end of one Iroh connection: its admin_rpc stream server with the real capability gate. */
    private fun CoroutineScope.hostCall(router: AdminRpcRouter, capabilities: Set<String>): WorkspaceRelayCall {
        val server = AdminRpcStreamServer(
            router = router,
            authenticated = AtomicBoolean(true),
            peerSupportsFrameParts = { true },
            capabilityGate = { method ->
                val required = IrohPeerCapabilities.forAdminMethod(method)
                if (IrohPeerCapabilities.isAllowed(capabilities, required)) null else required
            },
        )
        val scope = this
        return WorkspaceRelayCall { method, params ->
            val stream = FakeBiStream()
            val job = with(server) { scope.launchHandler(stream) }
            val request = AppServerProtocol.encodeCommand(AppServerCommand.AdminRpc(requestId = "admin-1", method = method, params = params))
            stream.recv.chunks.send(IrohFrameCodec.encodeFrame(request))
            stream.recv.chunks.send(ByteArray(0))
            job.join()
            val raw = IrohFrameCodec.Decoder().let { decoder -> stream.send.writes.flatMap { decoder.feed(it) } }.single()
            AppServerProtocol.decodeFrame(raw, AppServerChannel.Control).frame as AppServerInboundFrame.AdminRpcResponse
        }
    }

    /** The App Server behind the host: answers each workspace command as letta-code 0.32 does. */
    private class FakeAppServer(
        private val failWith: Exception? = null,
        /** When set, `read_memory_file` reads `<memoryRoot>/<path>` as letta-code 0.32 does. */
        private val memoryRoot: java.nio.file.Path? = null,
    ) : AppServerClient {
        val received = mutableListOf<Pair<String, String>>()
        val applied = mutableListOf<Map<String, String>>()
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()

        override suspend fun workspaceRequest(command: AppServerWorkspaceCommand): List<JsonObject> {
            failWith?.let { throw it }
            received += command.responseType.removeSuffix("_response") to command.requestId
            val id = command.requestId
            return when (command) {
                is AppServerMemfsCommand.ListMemory -> listOf(
                    frame("""{"type":"list_memory_response","request_id":"$id","entries":[{"relative_path":"system/human/communication_style.md","is_system":true,"content":"style","size":5}],"done":false,"success":true}"""),
                    frame("""{"type":"list_memory_response","request_id":"$id","entries":[{"relative_path":"notes.md","content":"n","size":1}],"done":true,"success":true}"""),
                )
                is AppServerMemfsCommand.ReadMemoryFile -> listOf(
                    memoryRoot?.let { readFromMemoryRoot(it, command) }
                        ?: frame("""{"type":"read_memory_file_response","request_id":"$id","content":"---\nstyle\n","success":true}"""),
                )
                is AppServerMemfsCommand.MemoryHistory ->
                    listOf(frame("""{"type":"memory_history_response","request_id":"$id","commits":[{"sha":"abc123","message":"m","timestamp":"t"}],"success":true}"""))
                is AppServerMemfsCommand.MemoryCommitDiff ->
                    listOf(frame("""{"type":"memory_commit_diff_response","request_id":"$id","diff":"diff --git","success":true}"""))
                is AppServerSecretCommand.SecretList ->
                    listOf(frame("""{"type":"secret_list_response","request_id":"$id","secrets":[{"key":"LIST_KEY","value":"L1ST-S3CRET"}],"success":true}"""))
                is AppServerSecretCommand.SecretApply -> {
                    applied += command.set
                    listOf(frame("""{"type":"secret_apply_response","request_id":"$id","names":["LIST_KEY","NEW_KEY"],"success":true}"""))
                }
                is AppServerFileCommand.SearchFiles ->
                    listOf(frame("""{"type":"search_files_response","request_id":"$id","files":[{"path":"src/main.kt","type":"file"}],"success":true}"""))
                is AppServerFileCommand.ReadFile ->
                    listOf(frame("""{"type":"read_file_response","request_id":"$id","path":"${command.path}","content":"# readme","success":true}"""))
                else -> error("unexpected ${command.responseType}")
            }
        }

        override suspend fun writeMemoryFile(command: AppServerCommand.WriteMemoryFile): AppServerInboundFrame.WriteMemoryFileResponse {
            received += "write_memory_file" to command.requestId
            return AppServerInboundFrame.WriteMemoryFileResponse(command.requestId, true, command.agentId, command.path, committed = true, commitSha = "def456")
        }

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = unused()
        override suspend fun input(command: AppServerCommand.Input): Unit = unused()
        override suspend fun sync(command: AppServerCommand.Sync) = unused()
        override suspend fun abort(command: AppServerCommand.AbortMessage) = unused()
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = unused()
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse): Unit = unused()
        private fun unused(): Nothing = error("unused")

        private fun frame(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

        /** letta-code's read_memory_file: join to the agent's memory root, refuse escapes, Node's ENOENT. */
        private fun readFromMemoryRoot(root: java.nio.file.Path, command: AppServerMemfsCommand.ReadMemoryFile): JsonObject {
            val target = root.resolve(command.path).normalize()
            val answer = kotlinx.serialization.json.buildJsonObject {
                put("type", JsonPrimitive("read_memory_file_response"))
                put("request_id", JsonPrimitive(command.requestId))
                when {
                    !target.startsWith(root) -> fail("path must resolve inside the memory root")
                    !java.nio.file.Files.exists(target) -> fail("ENOENT: no such file or directory, open '$target'")
                    else -> {
                        put("content", JsonPrimitive(java.nio.file.Files.readString(target)))
                        put("success", JsonPrimitive(true))
                    }
                }
            }
            return answer
        }

        private fun kotlinx.serialization.json.JsonObjectBuilder.fail(error: String) {
            put("success", JsonPrimitive(false))
            put("error", JsonPrimitive(error))
        }
    }

    private class FakeBiStream : AdminRpcBiStream {
        val recv = FakeRecvStream()
        val send = FakeSendStream()
        override fun recv(): AdminRpcRecvStream = recv
        override fun send(): AdminRpcSendStream = send
    }

    private class FakeRecvStream : AdminRpcRecvStream {
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        override suspend fun read(maxBytes: UInt): ByteArray = chunks.receive()
    }

    private class FakeSendStream : AdminRpcSendStream {
        val writes = mutableListOf<ByteArray>()
        override suspend fun writeAll(bytes: ByteArray) {
            writes += bytes
        }

        override suspend fun finish() = Unit
    }
}
