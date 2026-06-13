package com.letta.mobile.runtime.local

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.BuildConfig
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.runtime.BackendCapabilities
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.BackendKind
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.EpochMillis
import com.letta.mobile.runtime.InMemoryRuntimeEventOutbox
import com.letta.mobile.runtime.LocalLettaBackend
import com.letta.mobile.runtime.MemFsCommit
import com.letta.mobile.runtime.MemFsDeleteCommand
import com.letta.mobile.runtime.MemFsFile
import com.letta.mobile.runtime.MemFsPath
import com.letta.mobile.runtime.MemFsRevision
import com.letta.mobile.runtime.MemFsStore
import com.letta.mobile.runtime.MemFsWriteCommand
import com.letta.mobile.runtime.RuntimeEventEnvelope
import com.letta.mobile.runtime.RuntimeEventId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EmbeddedRuntimeDeviceLoopTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val bridgesToStop = mutableListOf<LettaCodeNodeBridge>()
    private val seededAgentIds = mutableListOf<String>()

    @After
    fun stopBridges() = runBlocking {
        bridgesToStop.forEach { bridge -> bridge.stop() }
        bridgesToStop.clear()
        cleanUpSeededAgents()
    }

    /**
     * Tier 3 seeds real agent/conversation/memfs records into the shared
     * local-backend store; without cleanup every run leaves a dead
     * "device-loop-agent-…" conversation in the app's conversations screen.
     */
    private fun cleanUpSeededAgents() {
        val storage = File(context.filesDir, "embedded-lettacode/local-backend")
        seededAgentIds.forEach { agentId ->
            File(File(storage, "agents"), "${base64Url(agentId)}.json").delete()
            File(File(storage, "conversations"), base64Url("default:$agentId")).deleteRecursively()
            File(File(storage, "memfs"), agentId).deleteRecursively()
        }
        seededAgentIds.clear()
    }

    private fun base64Url(value: String): String = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

    @Test
    fun tier1NodeBootSmokePrintsEmbeddedNodeVersion() = runBlocking {
        assumeEmbeddedNative()
        val bridge = NativeLettaCodeNodeBridge().also(bridgesToStop::add)
        val workingDirectory = File(context.filesDir, "embedded-node-smoke").apply { mkdirs() }
        val output = async(Dispatchers.Default) {
            withTimeoutOrNull(NODE_SMOKE_TIMEOUT_MS) {
                bridge.outputLines.first { line -> line.contains(EXPECTED_NODE_VERSION) }
            }
        }

        val started = bridge.start(
            LettaCodeNodeStartRequest(
                arguments = listOf("node", "-e", "console.log(process.version)"),
                environment = mapOf(
                    "HOME" to File(workingDirectory, "home").apply { mkdirs() }.absolutePath,
                    "NO_COLOR" to "1",
                    "UV_USE_IO_URING" to "0",
                    "UV_THREADPOOL_SIZE" to "2",
                    "NODE_OPTIONS" to "--max-old-space-size=384 --max-semi-space-size=16",
                ),
                workingDirectory = workingDirectory,
            )
        )

        assertTrue(started.exceptionOrNull()?.stackTraceToString(), started.isSuccess)
        assertEquals(EXPECTED_NODE_VERSION, output.await()?.trim())
        SystemClock.sleep(NODE_EXIT_SETTLE_MS)
        val stopped = bridge.stop()
        assertTrue(stopped.exceptionOrNull()?.stackTraceToString(), stopped.isSuccess)
    }

    @Test
    fun tier2RuntimeStatusReportsRunnableForEmbeddedBuild() {
        assumeEmbeddedNative()
        val status = BuildConfigEmbeddedLettaCodeRuntimeStatusProvider().status

        assertTrue("embedded assets must be enabled for runnable status", status.assetsEnabled)
        assertTrue("embedded runtime should be runnable", status.runnable)
        // The version carries the asset revision suffix (e.g. 0.26.1-r6);
        // pin only the letta-code release.
        assertTrue(
            "unexpected embedded version: ${status.version}",
            status.version.startsWith("0.26.1"),
        )
    }

    @Test
    fun tier3LocalTurnProducesRuntimeEventWhenModelExists() = runBlocking {
        assumeEmbeddedNative()
        assumeTrue("Tier 3 requires embedded LettaCode assets", BuildConfig.EMBEDDED_LETTACODE_ASSETS_ENABLED)
        val model = findLitertLmModel()
        assumeTrue("Tier 3 requires a .litertlm under filesDir/embedded-lettacode/models", model != null)
        requireNotNull(model)

        val nodeBridge = NativeLettaCodeNodeBridge().also(bridgesToStop::add)
        val controller = AndroidLettaCodeRuntimeController(
            context = context,
            assetExtractor = EmbeddedLettaCodeAssetExtractor(context),
            nodeBridge = nodeBridge,
            runtimeStatusProvider = BuildConfigEmbeddedLettaCodeRuntimeStatusProvider(),
            localBackendStore = LettaCodeLocalBackendStore(context),
            androidNetworkBridge = LocalAndroidNetworkBridge(),
            onDeviceOpenAiBridge = LocalOpenAiOnDeviceBridge(
                engine = object : OnDeviceChatCompletionEngine {
                    override fun generate(
                        modelSelection: EmbeddedLettaCodeModelSelection,
                        prompt: String,
                    ): Result<String> = Result.success("device-loop-ok")
                }
            ),
        )
        val backend = LocalLettaBackend(
            descriptor = descriptor(),
            engine = LettaCodeTurnEngine(
                client = AndroidLettaCodeHeadlessClient(controller, LettaCodeStreamJsonMapper(), LettaCodeLocalBackendStore(context)),
                config = config(model),
            ),
            outbox = InMemoryRuntimeEventOutbox(
                eventIdFactory = { _, offset -> RuntimeEventId("device-loop-${offset.value}") },
                clock = { EpochMillis(System.currentTimeMillis()) },
            ),
            memFsStore = NoopMemFsStore,
        )

        val event = withTimeoutOrNull(LOCAL_TURN_TIMEOUT_MS) {
            backend.runTurn(command()).firstOrNull { envelope -> envelope.isAssistantTextOrCleanFailure() }
        }

        assertTrue("expected assistant text or clean failure runtime event", event != null)
        if (requireAssistantText()) {
            val payload = event?.payload
            assertTrue(
                "strict mode: expected assistant text but got $payload",
                payload is RuntimeEventPayload.RemoteStreamFrame && payload.body.isNotBlank(),
            )
        }
    }

    /**
     * letta-mobile-69i0z: full tool-call round trip on device. The scripted
     * engine first replies with a tool_call (Bash echo); letta.js must parse
     * it through the bridge's OpenAI translation, EXECUTE the tool in-process
     * (sh exists on Android), feed the tool result back, and only then does
     * the engine produce final text. Assistant text therefore proves the
     * entire loop: prompt-format tools → tool_calls → execution → result →
     * follow-up generation.
     */
    @Test
    fun tier4LocalToolCallRoundTripExecutesToolAndCompletes() = runBlocking {
        assumeEmbeddedNative()
        assumeTrue("Tier 4 requires embedded LettaCode assets", BuildConfig.EMBEDDED_LETTACODE_ASSETS_ENABLED)
        val model = findLitertLmModel()
        assumeTrue("Tier 4 requires a .litertlm under filesDir/embedded-lettacode/models", model != null)
        requireNotNull(model)

        val nodeBridge = NativeLettaCodeNodeBridge().also(bridgesToStop::add)
        val controller = AndroidLettaCodeRuntimeController(
            context = context,
            assetExtractor = EmbeddedLettaCodeAssetExtractor(context),
            nodeBridge = nodeBridge,
            runtimeStatusProvider = BuildConfigEmbeddedLettaCodeRuntimeStatusProvider(),
            localBackendStore = LettaCodeLocalBackendStore(context),
            androidNetworkBridge = LocalAndroidNetworkBridge(),
            onDeviceOpenAiBridge = LocalOpenAiOnDeviceBridge(
                engine = object : OnDeviceChatCompletionEngine {
                    override fun generate(
                        modelSelection: EmbeddedLettaCodeModelSelection,
                        prompt: String,
                    ): Result<String> = Result.success(
                        // Require the echo's actual stdout in the follow-up
                        // prompt: a failed exec also feeds back a (error)
                        // tool result, and matching on that proved the loop
                        // while Bash was silently broken on Android (no
                        // /bin/sh — see SHELL in the controller env). The
                        // marker is $((…))-expanded so it only exists in
                        // real shell output, never in the echoed arguments.
                        if (prompt.contains("device-tool-42")) {
                            "tool-loop-ok"
                        } else {
                            "```tool_call\n{\"name\": \"Bash\", \"arguments\": {\"command\": \"echo device-tool-$((6*7))\"}}\n```"
                        }
                    )
                }
            ),
        )
        val backend = LocalLettaBackend(
            descriptor = descriptor(),
            engine = LettaCodeTurnEngine(
                client = AndroidLettaCodeHeadlessClient(controller, LettaCodeStreamJsonMapper(), LettaCodeLocalBackendStore(context)),
                config = config(model),
            ),
            outbox = InMemoryRuntimeEventOutbox(
                eventIdFactory = { _, offset -> RuntimeEventId("device-loop-${offset.value}") },
                clock = { EpochMillis(System.currentTimeMillis()) },
            ),
            memFsStore = NoopMemFsStore,
        )

        val event = withTimeoutOrNull(LOCAL_TURN_TIMEOUT_MS) {
            backend.runTurn(command()).firstOrNull { envelope ->
                val payload = envelope.payload
                payload is RuntimeEventPayload.RemoteStreamFrame && payload.body.contains("tool-loop-ok")
            }
        }

        assertTrue(
            "expected the post-tool assistant text to arrive (tool round trip)",
            event != null,
        )
    }

    /**
     * letta-mobile-3icw7: custom OpenAI-compatible endpoint ("remote brain,
     * local agent"). A second bridge instance plays the remote endpoint; the
     * controller must route letta.js at it, require NO .litertlm model, and
     * never start its own on-device bridge. The scripted endpoint demands a
     * tool round trip before answering, proving native tool calling through
     * the custom provider.
     */
    @Test
    fun tier5CustomProviderTurnSkipsOnDeviceModelAndCallsTools() = runBlocking {
        assumeEmbeddedNative()
        assumeTrue("Tier 5 requires embedded LettaCode assets", BuildConfig.EMBEDDED_LETTACODE_ASSETS_ENABLED)

        // Plays the user's remote OpenAI-compatible server.
        val remoteEndpoint = LocalOpenAiOnDeviceBridge(
            engine = object : OnDeviceChatCompletionEngine {
                override fun generate(
                    modelSelection: EmbeddedLettaCodeModelSelection,
                    prompt: String,
                ): Result<String> = Result.success(
                    if (prompt.contains("tool result")) {
                        "custom-provider-ok"
                    } else {
                        "```tool_call\n{\"name\": \"Bash\", \"arguments\": {\"command\": \"echo custom-provider-tool\"}}\n```"
                    }
                )
            }
        ).start(
            EmbeddedLettaCodeModelSelection(
                modelHandle = "remote-test-model",
                modelPath = null,
                runtime = "openai",
                accelerator = "cpu",
                maxTokens = 1024,
            )
        )

        try {
            val nodeBridge = NativeLettaCodeNodeBridge().also(bridgesToStop::add)
            val controller = AndroidLettaCodeRuntimeController(
                context = context,
                assetExtractor = EmbeddedLettaCodeAssetExtractor(context),
                nodeBridge = nodeBridge,
                runtimeStatusProvider = BuildConfigEmbeddedLettaCodeRuntimeStatusProvider(),
                localBackendStore = LettaCodeLocalBackendStore(context),
                androidNetworkBridge = LocalAndroidNetworkBridge(),
                onDeviceOpenAiBridge = object : OnDeviceOpenAiBridge {
                    override fun start(modelSelection: EmbeddedLettaCodeModelSelection): OnDeviceOpenAiBridgeSession =
                        error("on-device bridge must not start when a custom provider is configured")
                },
            )
            val backend = LocalLettaBackend(
                descriptor = descriptor(),
                engine = LettaCodeTurnEngine(
                    client = AndroidLettaCodeHeadlessClient(controller, LettaCodeStreamJsonMapper(), LettaCodeLocalBackendStore(context)),
                    config = LettaConfig(
                        id = BACKEND_ID.value,
                        mode = LettaConfig.Mode.LOCAL,
                        serverUrl = "local-lettacode://device",
                        localProviderBaseUrl = remoteEndpoint.baseUrl,
                        localProviderModel = "remote-test-model",
                    ),
                ),
                outbox = InMemoryRuntimeEventOutbox(
                    eventIdFactory = { _, offset -> RuntimeEventId("device-loop-${offset.value}") },
                    clock = { EpochMillis(System.currentTimeMillis()) },
                ),
                memFsStore = NoopMemFsStore,
            )

            val event = withTimeoutOrNull(LOCAL_TURN_TIMEOUT_MS) {
                backend.runTurn(command()).firstOrNull { envelope ->
                    val payload = envelope.payload
                    payload is RuntimeEventPayload.RemoteStreamFrame && payload.body.contains("custom-provider-ok")
                }
            }

            assertTrue(
                "expected assistant text from the custom provider after a tool round trip",
                event != null,
            )
        } finally {
            remoteEndpoint.close()
        }
    }

    /**
     * Manual/dev-only smoke against a REAL OpenAI-compatible endpoint.
     * Skipped unless instrumentation args provide the endpoint:
     *   -e customProviderBaseUrl http://host:port/v1 [-e customProviderModel <id>]
     */
    @Test
    fun tier6RealCustomProviderProducesAssistantText() = runBlocking {
        assumeEmbeddedNative()
        assumeTrue("Tier 6 requires embedded LettaCode assets", BuildConfig.EMBEDDED_LETTACODE_ASSETS_ENABLED)
        val arguments = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        val baseUrl = arguments.getString("customProviderBaseUrl")
        assumeTrue("pass -e customProviderBaseUrl to run tier6", !baseUrl.isNullOrBlank())
        requireNotNull(baseUrl)
        val model = arguments.getString("customProviderModel") ?: "default"

        val nodeBridge = NativeLettaCodeNodeBridge().also(bridgesToStop::add)
        val controller = AndroidLettaCodeRuntimeController(
            context = context,
            assetExtractor = EmbeddedLettaCodeAssetExtractor(context),
            nodeBridge = nodeBridge,
            runtimeStatusProvider = BuildConfigEmbeddedLettaCodeRuntimeStatusProvider(),
            localBackendStore = LettaCodeLocalBackendStore(context),
            androidNetworkBridge = LocalAndroidNetworkBridge(),
            onDeviceOpenAiBridge = object : OnDeviceOpenAiBridge {
                override fun start(modelSelection: EmbeddedLettaCodeModelSelection): OnDeviceOpenAiBridgeSession =
                    error("on-device bridge must not start when a custom provider is configured")
            },
        )
        val backend = LocalLettaBackend(
            descriptor = descriptor(),
            engine = LettaCodeTurnEngine(
                client = AndroidLettaCodeHeadlessClient(controller, LettaCodeStreamJsonMapper(), LettaCodeLocalBackendStore(context)),
                config = LettaConfig(
                    id = BACKEND_ID.value,
                    mode = LettaConfig.Mode.LOCAL,
                    serverUrl = "local-lettacode://device",
                    localProviderBaseUrl = baseUrl,
                    localProviderModel = model,
                ),
            ),
            outbox = InMemoryRuntimeEventOutbox(
                eventIdFactory = { _, offset -> RuntimeEventId("device-loop-${offset.value}") },
                clock = { EpochMillis(System.currentTimeMillis()) },
            ),
            memFsStore = NoopMemFsStore,
        )

        val event = withTimeoutOrNull(LOCAL_TURN_TIMEOUT_MS) {
            backend.runTurn(command()).firstOrNull { envelope ->
                val payload = envelope.payload
                payload is RuntimeEventPayload.RemoteStreamFrame &&
                    payload.messageType == "assistant_message" &&
                    payload.body.isNotBlank()
            }
        }

        assertTrue("expected assistant text from the real custom provider", event != null)
    }

    @Test
    fun tier7AndroidNetworkBridgeProvidesDnsAndFetchInsideNode() = runBlocking {
        assumeEmbeddedNative()
        assumeTrue("Tier 7 requires embedded LettaCode assets", BuildConfig.EMBEDDED_LETTACODE_ASSETS_ENABLED)
        val project = EmbeddedLettaCodeAssetExtractor(context).prepare()
        val networkSession = LocalAndroidNetworkBridge().start()
        val bridge = NativeLettaCodeNodeBridge().also(bridgesToStop::add)
        val output = async(Dispatchers.Default) {
            withTimeoutOrNull(NODE_SMOKE_TIMEOUT_MS) {
                bridge.outputLines.first { line -> line.contains("android-network-ok") }
            }
        }
        try {
            val started = bridge.start(
                LettaCodeNodeStartRequest(
                    arguments = listOf(
                        "node",
                        "--max-old-space-size=384",
                        "--max-semi-space-size=16",
                        "--require",
                        File(project.projectDir, "regexp-polyfill.cjs").absolutePath,
                        "--require",
                        File(project.projectDir, "android-network-polyfill.cjs").absolutePath,
                        "-e",
                        """
                        (async () => {
                          const dns = require('dns').promises;
                          const lookup = await dns.lookup('example.com');
                          const response = await fetch('https://example.com/');
                          if (typeof response.headers.entries !== 'function') {
                            throw new Error('response.headers.entries missing');
                          }
                          const headerEntries = Array.from(response.headers.entries());
                          if (!headerEntries.length) {
                            throw new Error('response.headers.entries returned no headers');
                          }
                          if (!response.body || typeof response.body[Symbol.asyncIterator] !== 'function') {
                            throw new Error('response.body async iterator missing');
                          }
                          let streamed = '';
                          for await (const chunk of response.body) {
                            streamed += Buffer.from(chunk).toString('utf8');
                          }
                          if (!lookup.address || response.status !== 200 || !streamed.includes('Example Domain')) {
                            throw new Error('unexpected bridge result ' + JSON.stringify({ lookup, status: response.status, text: streamed.slice(0, 40), headers: headerEntries.slice(0, 3) }));
                          }
                          console.log('android-network-ok ' + lookup.address + ' ' + response.status + ' headers=' + headerEntries.length);
                        })().catch(error => { console.error(error && error.stack ? error.stack : String(error)); process.exit(1); });
                        """.trimIndent(),
                    ),
                    environment = mapOf(
                        "HOME" to File(project.homeDirectory, "network-smoke").apply { mkdirs() }.absolutePath,
                        "LETTA_ANDROID_NETWORK_BRIDGE_URL" to networkSession.baseUrl,
                        "LETTA_ANDROID_NETWORK_BRIDGE_FORCE_FETCH" to "1",
                        "NO_COLOR" to "1",
                        "UV_USE_IO_URING" to "0",
                        "UV_THREADPOOL_SIZE" to "2",
                        "NODE_OPTIONS" to "--max-old-space-size=384 --max-semi-space-size=16",
                    ),
                    workingDirectory = project.workingDirectory,
                )
            )

            assertTrue(started.exceptionOrNull()?.stackTraceToString(), started.isSuccess)
            assertTrue("expected android-network-ok from node", output.await() != null)
            SystemClock.sleep(NODE_EXIT_SETTLE_MS)
            val stopped = bridge.stop()
            assertTrue(stopped.exceptionOrNull()?.stackTraceToString(), stopped.isSuccess)
        } finally {
            networkSession.close()
        }
    }

    @Test
    fun tier8CurlHelperWorksThroughAndroidNetworkBridge() = runBlocking {
        assumeEmbeddedNative()
        val networkSession = LocalAndroidNetworkBridge().start()
        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
        val curlBinary = File(nativeLibraryDir, "libcurl.so")
        assumeTrue("Tier 8 requires packaged libcurl.so helper", curlBinary.canExecute())
        try {
            val process = ProcessBuilder(curlBinary.absolutePath, "-sS", "https://example.com/")
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .apply {
                    environment()["LETTA_ANDROID_NETWORK_BRIDGE_URL"] = networkSession.baseUrl
                }
                .start()
            val output = withTimeoutOrNull(30_000L) {
                process.inputStream.bufferedReader().readText()
            }
            val exitCode = if (output == null) {
                process.destroyForcibly()
                -1
            } else {
                process.waitFor()
            }
            assertEquals("curl helper output: $output", 0, exitCode)
            assertTrue("curl helper should fetch Example Domain, got: $output", output?.contains("Example Domain") == true)
        } finally {
            networkSession.close()
        }
    }

    @Test
    fun tier9NodeCliIsAvailableForLocalToolScripting() = runBlocking {
        assumeEmbeddedNative()
        val project = EmbeddedLettaCodeAssetExtractor(context).prepare()
        val binDirectory = File(project.storageDirectory.parentFile, "bin").apply { mkdirs() }
        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
        val nodeBinary = File(nativeLibraryDir, "libnodecli.so")
        assumeTrue("Tier 9 requires packaged libnodecli.so", nodeBinary.canExecute())
        val nodeLink = File(binDirectory, "node")
        nodeLink.delete()
        android.system.Os.symlink(nodeBinary.absolutePath, nodeLink.absolutePath)
        val process = ProcessBuilder(
            "sh",
            "-c",
            "node -e 'const data = JSON.parse(process.argv[1]); console.log(data.items.reduce((sum, item) => sum + item.value, 0));' '{\"items\":[{\"value\":2},{\"value\":5}]}'"
        )
            .directory(context.filesDir)
            .redirectErrorStream(true)
            .apply {
                environment()["PATH"] = "${binDirectory.absolutePath}:${System.getenv("PATH") ?: "/system/bin"}"
            }
            .start()
        val output = withTimeoutOrNull(30_000L) {
            process.inputStream.bufferedReader().readText()
        }
        val exitCode = if (output == null) {
            process.destroyForcibly()
            -1
        } else {
            process.waitFor()
        }
        assertEquals("node CLI output: $output", 0, exitCode)
        assertEquals("7", output?.trim())
    }

    @Test
    fun tier10GitHelperCommitsAfterStaleSymlink() = runBlocking {
        assumeEmbeddedNative()
        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
        val gitBinary = File(nativeLibraryDir, "libgit.so")
        assumeTrue("Tier 10 requires packaged libgit.so", gitBinary.canExecute())
        val bashBinary = File(nativeLibraryDir, "libbash.so")
        val binDirectory = File(context.filesDir, "embedded-lettacode/bin").apply { mkdirs() }

        // Simulate a stale symlink from a previous install (the s1uis bug):
        // point git at a non-existent old APK path, then prove the self-heal
        // recreates it against the current nativeLibraryDir.
        val gitLink = File(binDirectory, "git")
        gitLink.delete()
        android.system.Os.symlink("/data/app/~~stale-old-install/lib/arm64/libgit.so", gitLink.absolutePath)

        val gitLinkCurrent = runCatching { android.system.Os.readlink(gitLink.absolutePath) }.getOrNull()
        if (gitLinkCurrent != gitBinary.absolutePath || !gitLink.canExecute()) {
            gitLink.delete()
            android.system.Os.symlink(gitBinary.absolutePath, gitLink.absolutePath)
        }
        if (bashBinary.canExecute()) {
            val bashLink = File(binDirectory, "bash")
            val bashLinkCurrent = runCatching { android.system.Os.readlink(bashLink.absolutePath) }.getOrNull()
            if (bashLinkCurrent != bashBinary.absolutePath || !bashLink.canExecute()) {
                bashLink.delete()
                android.system.Os.symlink(bashBinary.absolutePath, bashLink.absolutePath)
            }
        }
        assertEquals("git link should self-heal to current lib", gitBinary.absolutePath, android.system.Os.readlink(gitLink.absolutePath))

        val repo = File(context.filesDir, "embedded-lettacode/memfs-test-${UUID.randomUUID()}").apply { mkdirs() }
        val path = "${binDirectory.absolutePath}:${System.getenv("PATH") ?: "/system/bin"}"
        fun git(vararg args: String): Pair<Int, String> {
            val process = ProcessBuilder(listOf(gitLink.absolutePath) + args)
                .directory(repo)
                .redirectErrorStream(true)
                .apply {
                    environment()["PATH"] = path
                    environment()["HOME"] = repo.absolutePath
                    environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                    environment()["GIT_AUTHOR_NAME"] = "t"
                    environment()["GIT_AUTHOR_EMAIL"] = "t@t"
                    environment()["GIT_COMMITTER_NAME"] = "t"
                    environment()["GIT_COMMITTER_EMAIL"] = "t@t"
                }
                .start()
            val out = process.inputStream.bufferedReader().readText()
            return process.waitFor() to out
        }
        try {
            assertEquals("git init failed", 0, git("init").first)
            File(repo, "memory.md").writeText("# memory\n")
            assertEquals("git add failed", 0, git("add", "memory.md").first)
            val (commitCode, commitOut) = git("-c", "core.hooksPath=", "commit", "-m", "seed")
            assertEquals("git commit failed: $commitOut", 0, commitCode)
            val (logCode, logOut) = git("log", "--oneline")
            assertEquals("git log failed: $logOut", 0, logCode)
            assertTrue("commit should be present: $logOut", logOut.contains("seed"))
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun tier11NpmIsAvailableThroughEmbeddedNode() = runBlocking {
        assumeEmbeddedNative()
        assumeTrue("Tier 11 requires embedded LettaCode assets", BuildConfig.EMBEDDED_LETTACODE_ASSETS_ENABLED)
        val project = EmbeddedLettaCodeAssetExtractor(context).prepare()
        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
        val nodeBinary = File(nativeLibraryDir, "libnodecli.so")
        assumeTrue("Tier 11 requires packaged libnodecli.so", nodeBinary.canExecute())
        val npmCli = File(project.projectDir, "node_modules/npm/bin/npm-cli.js")
        assumeTrue("Tier 11 requires bundled npm-cli.js", npmCli.isFile)

        val binDirectory = File(context.filesDir, "embedded-lettacode/bin").apply { mkdirs() }
        val nodeLink = File(binDirectory, "node")
        nodeLink.delete()
        android.system.Os.symlink(nodeBinary.absolutePath, nodeLink.absolutePath)
        // npm uses the argv0-aware node launcher: symlink `npm` to the same
        // native binary and point it at npm-cli.js via LETTA_NPM_CLI_JS.
        val npmLink = File(binDirectory, "npm")
        npmLink.delete()
        android.system.Os.symlink(nodeBinary.absolutePath, npmLink.absolutePath)

        val process = ProcessBuilder("sh", "-c", "npm --version")
            .directory(context.filesDir)
            .redirectErrorStream(true)
            .apply {
                environment()["PATH"] = "${binDirectory.absolutePath}:${System.getenv("PATH") ?: "/system/bin"}"
                environment()["HOME"] = File(context.filesDir, "embedded-lettacode/home").apply { mkdirs() }.absolutePath
                environment()["LETTA_NPM_CLI_JS"] = npmCli.absolutePath
                environment()["LETTA_NODE_BIN"] = nodeLink.absolutePath
            }
            .start()
        val output = withTimeoutOrNull(60_000L) {
            process.inputStream.bufferedReader().readText()
        }
        val exitCode = if (output == null) {
            process.destroyForcibly()
            -1
        } else {
            process.waitFor()
        }
        assertEquals("npm --version output: $output", 0, exitCode)
        assertTrue("npm should print a semver version, got: $output", output?.trim()?.matches(Regex("\\d+\\.\\d+\\.\\d+")) == true)
    }

    /**
     * When run with -Pandroid.testInstrumentationRunnerArguments.requireAssistantText=true
     * a "clean failure" lifecycle event is NOT accepted — only real assistant text passes.
     * This is how the device loop distinguishes "runtime crashed politely" from
     * "the on-device model actually answered".
     */
    private fun requireAssistantText(): Boolean =
        androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("requireAssistantText") == "true"

    private fun assumeEmbeddedNative() {
        assumeTrue(
            "Embedded LettaCode native runtime is disabled; build with -PembedLettaCodeNative=true",
            BuildConfig.EMBEDDED_LETTACODE_NATIVE_ENABLED,
        )
    }

    private fun findLitertLmModel(): File? = File(context.filesDir, "embedded-lettacode/models")
        .walkTopDown()
        .firstOrNull { file -> file.isFile && file.extension == "litertlm" }

    private fun descriptor(): BackendDescriptor = BackendDescriptor(
        backendId = BACKEND_ID,
        runtimeId = RUNTIME_ID,
        kind = BackendKind.LocalLettaCode,
        label = "Embedded LettaCode device loop",
        capabilities = BackendCapabilities(
            supportsStreaming = true,
            supportsMemFs = true,
            supportsTools = true,
            supportsApprovals = false,
            supportsAgentFileImport = false,
            supportsAgentFileExport = false,
        ),
    )

    private fun config(model: File): LettaConfig = LettaConfig(
        id = BACKEND_ID.value,
        mode = LettaConfig.Mode.LOCAL,
        serverUrl = "local-lettacode://device",
        localModelHandle = "local/${model.nameWithoutExtension}",
        localModelPath = model.absolutePath,
        localModelRuntime = EmbeddedLettaCodeModelSelection.DEFAULT_MODEL_RUNTIME,
        // Overridable for accelerator-specific repros:
        // -e localModelAccelerator gpu
        localModelAccelerator = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("localModelAccelerator") ?: "cpu",
        localModelMaxTokens = 256,
    )

    private fun command(): TurnCommand = TurnCommand(
        backendId = BACKEND_ID,
        runtimeId = RUNTIME_ID,
        agentId = AgentId("device-loop-agent-${UUID.randomUUID()}".also(seededAgentIds::add)),
        conversationId = ConversationId("device-loop-conversation-${UUID.randomUUID()}"),
        input = TurnInput.UserMessage(
            localMessageId = "device-loop-message-${UUID.randomUUID()}",
            text = "Reply with a short device loop acknowledgement.",
        ),
    )

    private fun RuntimeEventEnvelope.isAssistantTextOrCleanFailure(): Boolean = when (val eventPayload = payload) {
        is RuntimeEventPayload.RemoteStreamFrame -> eventPayload.body.isNotBlank()
        is RuntimeEventPayload.RunLifecycleChanged -> eventPayload.status == RuntimeRunStatus.Failed
        else -> false
    }

    private object NoopMemFsStore : MemFsStore {
        override suspend fun read(path: MemFsPath): MemFsFile? = null

        override suspend fun write(command: MemFsWriteCommand): MemFsCommit =
            error("MemFS writes are not used by device-loop tests.")

        override suspend fun delete(command: MemFsDeleteCommand): MemFsCommit =
            error("MemFS deletes are not used by device-loop tests.")

        override fun commits(afterRevision: MemFsRevision) = emptyFlow<MemFsCommit>()
    }

    private companion object {
        private const val EXPECTED_NODE_VERSION = "v18.20.4"
        private const val NODE_SMOKE_TIMEOUT_MS = 30_000L
        private const val NODE_EXIT_SETTLE_MS = 1_000L
        private const val LOCAL_TURN_TIMEOUT_MS = 180_000L
        private val BACKEND_ID = BackendId("local-lettacode:device-loop")
        private val RUNTIME_ID = RuntimeId("local-lettacode:device-loop")
    }
}
