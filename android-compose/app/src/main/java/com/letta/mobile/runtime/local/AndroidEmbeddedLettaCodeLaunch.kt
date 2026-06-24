package com.letta.mobile.runtime.local

import android.content.Context
import android.util.Log
import com.letta.mobile.runtime.sensors.DeviceSensorGroundingWriter
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val embeddedProviderAuthJson = Json { prettyPrint = true }

data class AndroidEmbeddedLettaCodeLaunchSpec(
    val entrypoint: File,
    val workingDirectory: File,
    val modelHandle: String,
    val preloadRequireFiles: List<File>,
    val environment: Map<String, String>,
) {
    fun toStreamJsonNodeStartRequest(session: EmbeddedLettaCodeSessionKey): LettaCodeNodeStartRequest =
        LettaCodeNodeStartRequest(
            arguments = buildList {
                add("node")
                add("--max-old-space-size=384")
                add("--max-semi-space-size=16")
                preloadRequireFiles.forEach { preload ->
                    add("--require")
                    add(preload.absolutePath)
                }
                add(entrypoint.absolutePath)
                add("--backend")
                add("local")
                add("--model")
                add(modelHandle)
                add("--agent")
                add(session.agentId)
                add("--conversation")
                add("default")
                add("--input-format")
                add("stream-json")
                add("--output-format")
                add("stream-json")
                add("--permission-mode")
                add("unrestricted")
            },
            environment = environment,
            workingDirectory = workingDirectory,
        )
}

fun EmbeddedLettaCodeModelSelection.androidEmbeddedLaunchModelHandle(
    onDeviceProviderBaseUrl: String?,
): String =
    if (onDeviceProviderBaseUrl == null) modelHandle else lettaCodeModelHandle

fun PreparedLettaCodeProject.prepareAndroidEmbeddedLettaCodeLaunchSpec(
    modelSelection: EmbeddedLettaCodeModelSelection,
    onDeviceProviderBaseUrl: String? = null,
    onDeviceProviderApiKey: String? = null,
    androidNetworkBridgeBaseUrl: String,
    androidNetworkBridgeToken: String,
    deviceSensorGroundingWriter: DeviceSensorGroundingWriter? = null,
    toolInstaller: AndroidEmbeddedToolInstaller,
): AndroidEmbeddedLettaCodeLaunchSpec {
    workingDirectory.mkdirs()
    storageDirectory.mkdirs()
    homeDirectory.mkdirs()
    val modelCacheDirectory = File(storageDirectory, "model-cache").apply { mkdirs() }
    val tempDirectory = File(storageDirectory.parentFile, "tmp").apply { mkdirs() }
    val backgroundDirectory = File(storageDirectory.parentFile, "background").apply { mkdirs() }
    val modelHandle = modelSelection.androidEmbeddedLaunchModelHandle(onDeviceProviderBaseUrl)
    if (onDeviceProviderBaseUrl != null) {
        writeEmbeddedLettaCodeProviderAuth(
            baseUrl = onDeviceProviderBaseUrl,
            apiKey = onDeviceProviderApiKey ?: EmbeddedLettaCodeModelSelection.DEFAULT_LM_STUDIO_API_KEY,
            modelId = modelSelection.openAiModelId,
        )
    }
    return AndroidEmbeddedLettaCodeLaunchSpec(
        entrypoint = entrypoint,
        workingDirectory = workingDirectory,
        modelHandle = modelHandle,
        preloadRequireFiles = embeddedLettaCodePreloadRequireFiles(projectDir),
        environment = buildMap {
            put("HOME", homeDirectory.absolutePath)
            put("LETTA_LOCAL_BACKEND_EXPERIMENTAL", "1")
            put("LETTA_LOCAL_BACKEND_DIR", storageDirectory.absolutePath)
            put("LETTA_LOCAL_BACKEND_EXECUTOR", "pi")
            putAll(memfsEnvironment(toolInstaller))
            put("LETTA_CODE_VISION_MODEL_IDS", VISION_MODEL_PATTERNS.joinToString(","))
            put("LETTA_ANDROID_ON_DEVICE_MODEL_HANDLE", modelHandle)
            put("LETTA_ANDROID_ON_DEVICE_MODEL_RUNTIME", modelSelection.runtime)
            put("LETTA_ANDROID_ON_DEVICE_MODEL_ACCELERATOR", modelSelection.accelerator)
            put("LETTA_ANDROID_ON_DEVICE_MODEL_MAX_TOKENS", modelSelection.maxTokens.toString())
            put("LETTA_ANDROID_ON_DEVICE_MODEL_CACHE_DIR", modelCacheDirectory.absolutePath)
            onDeviceProviderBaseUrl?.let { put("LMSTUDIO_BASE_URL", it) }
            onDeviceProviderApiKey?.let { put("LMSTUDIO_API_KEY", it) }
            modelSelection.modelPath?.let { put("LETTA_ANDROID_ON_DEVICE_MODEL_PATH", it) }
            put("SHELL", "/system/bin/sh")
            put("NO_COLOR", "1")
            put("UV_USE_IO_URING", "0")
            put("UV_THREADPOOL_SIZE", "2")
            put("TMPDIR", tempDirectory.absolutePath)
            put("TMP", tempDirectory.absolutePath)
            put("TEMP", tempDirectory.absolutePath)
            put("LETTA_BACKGROUND_DIR", backgroundDirectory.absolutePath)
            put("LETTA_TASK_OUTPUT_DIR", backgroundDirectory.absolutePath)
            put("LETTA_ANDROID_NETWORK_BRIDGE_URL", androidNetworkBridgeBaseUrl)
            put("LETTA_ANDROID_NETWORK_BRIDGE_TOKEN", androidNetworkBridgeToken)
            deviceSensorGroundingWriter?.let {
                put("LETTA_MOBILE_DEVICE_SENSOR_GROUNDING_PATH", it.outputFile.absolutePath)
            }
            put("NODE_OPTIONS", "--max-old-space-size=384 --max-semi-space-size=16")
        },
    )
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
    if (!toolInstaller.linkPackagedTool(binDirectory, "git", "libgit.so", "git-backed local memfs")) {
        return pathEnvironment + ("LETTA_LOCAL_BACKEND_NO_MEMFS" to "1")
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

private fun PreparedLettaCodeProject.writeEmbeddedLettaCodeProviderAuth(
    baseUrl: String,
    apiKey: String,
    modelId: String,
) {
    val authFile = File(storageDirectory, "providers/auth.json")
    val root = buildJsonObject {
        put("version", 1)
        put(
            "providers",
            buildJsonObject {
                put(
                    "lc-lmstudio",
                    buildJsonObject {
                        put("id", "local-provider-lc-lmstudio")
                        put("name", "lc-lmstudio")
                        put("provider_type", "lmstudio")
                        put("provider_category", "byok")
                        put(
                            "auth",
                            buildJsonObject {
                                put("type", "api")
                                put("key", apiKey)
                            },
                        )
                        put("base_url", baseUrl)
                        put(
                            "models",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("id", modelId)
                                        put("contextWindow", DEFAULT_LOCAL_CONTEXT_WINDOW)
                                        put("maxTokens", EmbeddedLettaCodeModelSelection.DEFAULT_MAX_TOKENS)
                                        put("input", buildJsonArray {
                                            add(JsonPrimitive("text"))
                                        })
                                        put("cost", buildJsonObject {
                                            put("input", 0)
                                            put("output", 0)
                                        })
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }
    authFile.parentFile?.mkdirs()
    authFile.writeText(embeddedProviderAuthJson.encodeToString(JsonObject.serializer(), root))
}

private const val DEFAULT_LOCAL_CONTEXT_WINDOW = 128_000

private val VISION_MODEL_PATTERNS = listOf(
    "llava", "vision", "opus", "sonnet", "haiku", "claude", "fable",
    "gpt-", "gpt5", "gemini", "grok", "minimax",
    "qwen-vl", "qwen2-vl", "qwen2.5-vl",
)
