package com.letta.mobile.runtime.local

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelCatalogParser
import java.io.File
import java.net.Socket

/**
 * ADB usage:
 *   adb shell am broadcast -n com.letta.mobile.dev/com.letta.mobile.runtime.local.LiteRtSelfTestReceiver
 *   adb shell am broadcast -n com.letta.mobile.dev/com.letta.mobile.runtime.local.LiteRtSelfTestReceiver --es model google/gemma-3n-E2B-it-litert-lm
 */
class LiteRtSelfTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        selfTestLog("receiver_start", "PASS", "component=${javaClass.name}")
        val pendingResult = goAsync()
        Thread {
            try {
                runSelfTest(context.applicationContext, intent)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun runSelfTest(context: Context, intent: Intent) {
        val modelHandle = intent.getStringExtra(EXTRA_MODEL)?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL_HANDLE
        selfTestLog("model_handle", "PASS", "model=$modelHandle")
        val modelPath = try {
            resolveModelPath(context, modelHandle)
        } catch (error: Throwable) {
            selfTestLog("resolve_model", "FAIL", error.fullLiteRtErrorText(), Log.ERROR)
            return
        }
        val modelFile = File(modelPath)
        selfTestLog(
            "resolve_model",
            "PASS",
            "model=$modelHandle path=${modelFile.absolutePath} exists=${modelFile.exists()} isFile=${modelFile.isFile} size=${modelFile.takeIf { it.exists() }?.length() ?: -1L}",
        )

        val selection = EmbeddedLettaCodeModelSelection(
            modelHandle = modelHandle,
            modelPath = modelPath,
            runtime = EmbeddedLettaCodeModelSelection.DEFAULT_MODEL_RUNTIME,
            accelerator = EmbeddedLettaCodeModelSelection.DEFAULT_ACCELERATOR,
            maxTokens = SELF_TEST_MAX_TOKENS,
        )
        val engine = LiteRtLmOnDeviceChatCompletionEngine(context)
        val bridge = LocalOpenAiOnDeviceBridge(engine)
        val session = try {
            selfTestLog("bridge_start", "PASS", "starting production OnDeviceOpenAiBridge")
            bridge.start(selection)
        } catch (error: Throwable) {
            selfTestLog("bridge_start", "FAIL", error.fullLiteRtErrorText(), Log.ERROR)
            return
        }
        session.use {
            val response = try {
                selfTestLog("inference", "PASS", "sending one-shot chat completion")
                postChatCompletion(it, modelHandle)
            } catch (error: Throwable) {
                selfTestLog("inference", "FAIL", error.fullLiteRtErrorText(), Log.ERROR)
                return
            }
            val passed = response.startsWith("HTTP/1.1 200")
            selfTestLog(
                "completion",
                if (passed) "PASS" else "FAIL",
                response,
                if (passed) Log.INFO else Log.ERROR,
            )
        }
    }

    private fun resolveModelPath(context: Context, modelHandle: String): String {
        val entries = context.assets.open(CATALOG_ASSET).bufferedReader().use { reader ->
            EmbeddedModelCatalogParser().parse(reader.readText())
        }
        val entry = entries.firstOrNull { it.modelId == modelHandle }
            ?: error("No embedded model catalog entry found for model handle '$modelHandle'.")
        val target = File(File(context.filesDir, MODEL_DIRECTORY), entry.modelFile)
        check(target.isFile && target.length() > 0L) {
            "Resolved LiteRT model for '$modelHandle' was not found at ${target.absolutePath}. exists=${target.exists()} size=${target.takeIf { it.exists() }?.length() ?: -1L}"
        }
        return target.absolutePath
    }

    private fun postChatCompletion(session: OnDeviceOpenAiBridgeSession, modelHandle: String): String {
        val port = session.baseUrl.substringAfterLast(':').substringBefore('/').toInt()
        val body = """
            {"model":"$modelHandle","messages":[{"role":"user","content":"Reply with the word pong."}],"stream":false}
        """.trimIndent()
        val request = buildString {
            append("POST /v1/chat/completions HTTP/1.1\r\n")
            append("Host: 127.0.0.1:$port\r\n")
            append("Authorization: Bearer ${session.authToken}\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
            append(body)
        }
        return Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = SELF_TEST_SOCKET_TIMEOUT_MS
            socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun selfTestLog(stage: String, result: String, detail: String, priority: Int = Log.INFO) {
        logChunked(priority, TAG, "stage=$stage result=$result detail=$detail")
    }

    companion object {
        private const val TAG = "LITERT_SELFTEST"
        private const val EXTRA_MODEL = "model"
        private const val DEFAULT_MODEL_HANDLE = "google/gemma-3n-E2B-it-litert-lm"
        private const val SELF_TEST_MAX_TOKENS = 64
        private const val SELF_TEST_SOCKET_TIMEOUT_MS = 120_000
        private const val CATALOG_ASSET = "embedded-models/model_allowlist.json"
        private const val MODEL_DIRECTORY = "embedded-lettacode/models"
    }
}
