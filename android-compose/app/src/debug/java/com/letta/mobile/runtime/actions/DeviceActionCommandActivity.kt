package com.letta.mobile.runtime.actions

import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject

/**
 * Debug-only ADB facade for stable JSON device-action commands.
 *
 * Example:
 * adb shell am start \
 *   -n com.letta.mobile.dev/com.letta.mobile.runtime.actions.DeviceActionCommandActivity \
 *   --es command_json '{"command":"sensors.summary"}'
 * adb shell run-as com.letta.mobile.dev cat files/device-action-command-result.json
 */
@AndroidEntryPoint
class DeviceActionCommandActivity : ComponentActivity() {
    @Inject lateinit var runner: DeviceActionCommandRunner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            val commandJson = decodedExtra(EXTRA_COMMAND_JSON_BASE64)
                ?: intent.getStringExtra(EXTRA_COMMAND_JSON)
                ?: commandJsonFromExtras()
                ?: "{\"command\":\"help\"}"
            val output = runner.runJson(commandJson)
            val out = File(filesDir, OUTPUT_FILE)
            out.writeText(output)
            Log.w(TAG, "[device-action-command] wrote ${out.absolutePath} command=$commandJson")
        }.onFailure { error ->
            Log.e(TAG, "[device-action-command] failed", error)
            File(filesDir, OUTPUT_FILE).writeText(
                """{"command":"unknown","success":false,"error":{"code":"activity_failed","message":${error.message.orEmpty().jsonString()}}}"""
            )
        }
        finish()
        overridePendingTransition(0, 0)
    }

    private fun commandJsonFromExtras(): String? {
        val command = intent.getStringExtra(EXTRA_COMMAND)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val inputJson = (decodedExtra(EXTRA_INPUT_JSON_BASE64) ?: intent.getStringExtra(EXTRA_INPUT_JSON))
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return if (inputJson == null) {
            "{\"command\":${command.jsonString()}}"
        } else {
            "{\"command\":${command.jsonString()},\"input\":$inputJson}"
        }
    }

    private fun decodedExtra(name: String): String? = intent.getStringExtra(name)
        ?.takeIf { it.isNotBlank() }
        ?.let { encoded -> String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8) }

    companion object {
        const val EXTRA_COMMAND_JSON = "command_json"
        const val EXTRA_COMMAND_JSON_BASE64 = "command_json_b64"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_INPUT_JSON = "input_json"
        const val EXTRA_INPUT_JSON_BASE64 = "input_json_b64"
        const val OUTPUT_FILE = "device-action-command-result.json"
        private const val TAG = "DeviceActionCommand"
    }
}

private fun String.jsonString(): String = buildString {
    append('"')
    this@jsonString.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
    append('"')
}
