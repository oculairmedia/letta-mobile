package com.letta.mobile.runtime.local

import android.content.Context
import android.util.Log
import com.letta.mobile.data.runtime.host.LocalAppServerLaunch
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val embeddedProviderAuthJson = Json { prettyPrint = true }

/** Where letta.js reaches [AndroidNetworkBridge] (`/fetch`, `/dns/lookup` for android-network-polyfill.cjs). */
data class AndroidNetworkBridgeEndpoint(val baseUrl: String, val token: String)

/**
 * The on-device App Server launch (letta-mobile-by4vm.110.5): `node` with the heap flags and the
 * App Server preloads in front of letta.js, plus the device environment.
 * [com.letta.mobile.data.runtime.host.LocalAppServerHost] appends `server --backend local
 * --listen …` and the local-backend variables. Providers and models are the App Server's own
 * (`providers/auth.json` in the backend dir, `agent_create`), so nothing here is model-specific.
 */
fun PreparedLettaCodeProject.androidLocalAppServerLaunch(
    networkBridge: AndroidNetworkBridgeEndpoint,
    toolInstaller: AndroidEmbeddedToolInstaller,
): LocalAppServerLaunch = LocalAppServerLaunch(
    command = buildList {
        add("node")
        addAll(NodeHeap.APP_SERVER.flags)
        embeddedAppServerPreloadRequireFiles(projectDir).forEach { preload ->
            add("--require")
            add(preload.absolutePath)
        }
        add(entrypoint.absolutePath)
    },
    backendDir = storageDirectory.absolutePath,
    environment = embeddedRuntimeEnvironment(networkBridge, toolInstaller, NodeHeap.APP_SERVER),
    workingDirectory = workingDirectory.absolutePath,
)

/**
 * node18-compat.cjs must come first: the other preloads may already use the globals it backfills.
 * Device tools reach the App Server through `runtime_start.register_external_tools` (the channel's
 * [com.letta.mobile.data.controller.extras.ExternalToolRegistry]), so there is no tools preload.
 */
fun embeddedAppServerPreloadRequireFiles(projectDir: File): List<File> = listOf(
    File(projectDir, "node18-compat.cjs"),
    File(projectDir, "regexp-polyfill.cjs"),
    File(projectDir, "android-network-polyfill.cjs"),
)

/** The device environment: home, tools on PATH, temp dirs, the network bridge, the heap. */
private fun PreparedLettaCodeProject.embeddedRuntimeEnvironment(
    networkBridge: AndroidNetworkBridgeEndpoint,
    toolInstaller: AndroidEmbeddedToolInstaller,
    heap: NodeHeap,
): Map<String, String> {
    workingDirectory.mkdirs()
    storageDirectory.mkdirs()
    homeDirectory.mkdirs()
    val tempDirectory = File(storageDirectory.parentFile, "tmp").apply { mkdirs() }
    val backgroundDirectory = File(storageDirectory.parentFile, "background").apply { mkdirs() }
    return buildMap {
        put("HOME", homeDirectory.absolutePath)
        put("LETTA_LOCAL_BACKEND_EXECUTOR", "pi")
        putAll(memfsEnvironment(toolInstaller))
        put("LETTA_CODE_VISION_MODEL_IDS", VISION_MODEL_PATTERNS.joinToString(","))
        put("SHELL", "/system/bin/sh")
        put("NO_COLOR", "1")
        put("UV_USE_IO_URING", "0")
        put("UV_THREADPOOL_SIZE", "2")
        put("TMPDIR", tempDirectory.absolutePath)
        put("TMP", tempDirectory.absolutePath)
        put("TEMP", tempDirectory.absolutePath)
        put("LETTA_BACKGROUND_DIR", backgroundDirectory.absolutePath)
        put("LETTA_TASK_OUTPUT_DIR", backgroundDirectory.absolutePath)
        put("LETTA_ANDROID_NETWORK_BRIDGE_URL", networkBridge.baseUrl)
        put("LETTA_ANDROID_NETWORK_BRIDGE_TOKEN", networkBridge.token)
        put("NODE_OPTIONS", heap.flags.joinToString(" "))
    }
}

/** The V8 heap; the flags go on argv and into NODE_OPTIONS. */
private enum class NodeHeap(oldSpaceMb: Int) {
    /** App Server mode keeps more resident (runtime cache, cron, terminals): the plan's 512, measured in W7. */
    APP_SERVER(oldSpaceMb = 512),
    ;

    val flags: List<String> = listOf("--max-old-space-size=$oldSpaceMb", "--max-semi-space-size=16")
}

interface AndroidEmbeddedToolInstaller {
    fun ensureBashLink(binDirectory: File)

    fun linkPackagedTool(binDirectory: File, commandName: String, libraryName: String, label: String): Boolean
}

class AndroidContextEmbeddedToolInstaller(
    private val context: Context,
) : AndroidEmbeddedToolInstaller {
    override fun ensureBashLink(binDirectory: File) {
        val bashLib = File(context.applicationInfo.nativeLibraryDir, "libbash.so")
        val bashTarget = if (bashLib.canExecute()) bashLib.absolutePath else "/system/bin/sh"
        val bashLink = File(binDirectory, "bash")
        val bashLinkCurrent = runCatching { android.system.Os.readlink(bashLink.absolutePath) }.getOrNull()
        if (bashLinkCurrent != bashTarget || !bashLink.canExecute()) {
            bashLink.delete()
            runCatching { android.system.Os.symlink(bashTarget, bashLink.absolutePath) }
                .onFailure { error -> Log.w(TAG, "Failed to link bash; Bash tool will be unavailable", error) }
        }
    }

    override fun linkPackagedTool(
        binDirectory: File,
        commandName: String,
        libraryName: String,
        label: String,
    ): Boolean {
        val library = File(context.applicationInfo.nativeLibraryDir, libraryName)
        if (!library.canExecute()) {
            Log.i(TAG, "$libraryName not packaged; $label unavailable")
            return false
        }
        val link = File(binDirectory, commandName)
        val current = runCatching { android.system.Os.readlink(link.absolutePath) }.getOrNull()
        if (current != library.absolutePath || !link.canExecute()) {
            link.delete()
            runCatching { android.system.Os.symlink(library.absolutePath, link.absolutePath) }
                .onFailure { error ->
                    Log.w(TAG, "Failed to link $commandName; $label will be unavailable", error)
                    return false
                }
        }
        return link.canExecute()
    }

    private companion object {
        private const val TAG = "LettaCodeLaunch"
    }
}

private fun PreparedLettaCodeProject.memfsEnvironment(toolInstaller: AndroidEmbeddedToolInstaller): Map<String, String> {
    val binDirectory = File(storageDirectory.parentFile, "bin").apply { mkdirs() }
    toolInstaller.ensureBashLink(binDirectory)
    toolInstaller.linkPackagedTool(binDirectory, "curl", "libcurl.so", "curl helper")
    val nodeAvailable = toolInstaller.linkPackagedTool(binDirectory, "node", "libnodecli.so", "node CLI")
    val npmCli = File(projectDir, "node_modules/npm/bin/npm-cli.js")
    val nodeLink = File(binDirectory, "node")
    val npmEnvironment = if (npmCli.isFile && nodeAvailable) {
        toolInstaller.linkPackagedTool(binDirectory, "npm", "libnodecli.so", "npm launcher")
        mapOf(
            "LETTA_NPM_CLI_JS" to npmCli.absolutePath,
            "LETTA_NODE_BIN" to nodeLink.absolutePath,
        )
    } else {
        Log.i("LettaCodeLaunch", "npm-cli.js not bundled or node missing; npm unavailable")
        emptyMap()
    }
    val pathEnvironment = mapOf(
        "PATH" to "${binDirectory.absolutePath}:${System.getenv("PATH") ?: "/system/bin"}",
    ) + npmEnvironment
    // letta-code 0.34 has no process-wide memfs opt-out (LETTA_LOCAL_BACKEND_NO_MEMFS is gone; only
    // stateless sessions, `--no-memfs` and the `memfs:disabled` agent tag skip it). The embedded build
    // is arm64-v8a only, where libgit.so is always packaged; without it memfs startup fails loudly.
    if (!toolInstaller.linkPackagedTool(binDirectory, "git", "libgit.so", "git-backed local memfs")) {
        return pathEnvironment
    }
    val disabledHooksDirectory = File(storageDirectory.parentFile, "git-hooks-disabled").apply { mkdirs() }
    File(homeDirectory, ".gitconfig").writeText(
        """
        [user]
            name = Letta Mobile
            email = letta-mobile@localhost
        [core]
            hooksPath = ${disabledHooksDirectory.absolutePath}
        [maintenance]
            auto = false
        [safe]
            directory = *
        """.trimIndent() + "\n",
    )
    return pathEnvironment + mapOf(
        "GIT_EXEC_PATH" to binDirectory.absolutePath,
        "GIT_TEMPLATE_DIR" to "",
        "GIT_CONFIG_NOSYSTEM" to "1",
    )
}

/** Where the runtime's `lc-lmstudio` provider sends model calls: the on-device bridge or a model server. */
data class ProviderEndpoint(val baseUrl: String, val apiKey: String)

/**
 * letta-mobile-by4vm.110.6: the runtime's own provider file, `<backendDir>/providers/auth.json`,
 * with one `lc-lmstudio` provider at [endpoint] listing [selection]'s model, so agents created on
 * [EmbeddedLettaCodeModelSelection.lettaCodeModelHandle] resolve (the CI node18 smoke and the
 * device smoke use this shape). Vision stays with `LETTA_CODE_VISION_MODEL_IDS`: 0.34.14 ignores
 * `models[].input` for lmstudio (by4vm.110.2).
 */
fun File.writeLocalProviderAuth(endpoint: ProviderEndpoint, selection: EmbeddedLettaCodeModelSelection) {
    val root = buildJsonObject {
        put("version", 1)
        put(
            "providers",
            buildJsonObject {
                put(
                    LOCAL_PROVIDER_NAME,
                    buildJsonObject {
                        put("id", "local-provider-$LOCAL_PROVIDER_NAME")
                        put("name", LOCAL_PROVIDER_NAME)
                        put("provider_type", "lmstudio")
                        put("provider_category", "byok")
                        put("auth", buildJsonObject { put("type", "api"); put("key", endpoint.apiKey) })
                        put("base_url", endpoint.baseUrl)
                        put(
                            "models",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("id", selection.openAiModelId)
                                        put("contextWindow", DEFAULT_LOCAL_CONTEXT_WINDOW)
                                        put("maxTokens", selection.maxTokens)
                                        put("input", buildJsonArray { add(JsonPrimitive("text")) })
                                        put("cost", buildJsonObject { put("input", 0); put("output", 0) })
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }
    val authFile = File(this, "providers/auth.json")
    authFile.parentFile?.mkdirs()
    authFile.writeText(embeddedProviderAuthJson.encodeToString(JsonObject.serializer(), root))
}

private const val LOCAL_PROVIDER_NAME = "lc-lmstudio"
private const val DEFAULT_LOCAL_CONTEXT_WINDOW = 128_000

private val VISION_MODEL_PATTERNS = listOf(
    "llava", "vision", "opus", "sonnet", "haiku", "claude", "fable",
    "gpt-", "gpt5", "gemini", "grok", "minimax",
    "qwen-vl", "qwen2-vl", "qwen2.5-vl",
)
