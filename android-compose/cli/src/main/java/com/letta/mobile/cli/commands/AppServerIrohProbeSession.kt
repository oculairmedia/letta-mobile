package com.letta.mobile.cli.commands

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.iroh.IrohAppServerTransport
import com.letta.mobile.data.transport.iroh.IrohProbeAssertions
import com.letta.mobile.data.transport.iroh.IrohProbeTurnMetrics
import computer.iroh.Endpoint
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock

internal class ProbeAdminClient(private val baseUrl: String) {
    fun adminPort(): Int {
        val parsed = runCatching { URI(baseUrl).port }.getOrDefault(-1)
        return if (parsed > 0) parsed else 8291
    }

    fun json(method: ProbeHttpMethod, path: ProbeHttpPath, body: ProbeJsonBody? = null): JsonElement {
        val connection = URL("${baseUrl.trimEnd('/')}${path.value}").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method.value
            connection.connectTimeout = 5_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Connection", "close")
            if (body != null) {
                connection.doOutput = true
                OutputStreamWriter(connection.outputStream).use { it.write(body.value) }
            }
            val status = connection.responseCode
            val text = if (status in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                error("HTTP $status: ${connection.errorStream?.bufferedReader()?.readText().orEmpty().take(200)}")
            }
            Json.parseToJsonElement(text)
        } finally {
            connection.disconnect()
        }
    }
}

internal data class ProbeSession(
    val endpoint: Endpoint,
    val transport: IrohAppServerTransport,
    val client: DefaultAppServerClient,
    val runtime: AppServerRuntimeScope,
    val dialMs: Long,
    val scenarioViolations: List<String>,
    val scope: CoroutineScope,
)

internal class ProbeSetupMetrics(private val turn: Int) {
    private var dialMs: Long? = null
    private var currentStage: String = "dial"
    private val completedStages = mutableListOf<String>()

    fun markDialSucceeded(value: Long) {
        dialMs = value
        completedStages += "dial"
    }

    fun beginStage(stage: String) {
        currentStage = stage
    }

    fun markStageSucceeded(stage: String) {
        completedStages += stage
    }

    fun toFailureMetrics(message: String, timedOut: Boolean = false): IrohProbeTurnMetrics {
        val violation = IrohProbeAssertions.classifyConversationBootstrap(message)
        val error = listOfNotNull(
            completedStages.takeIf { it.isNotEmpty() }?.joinToString(prefix = "completed_stages:", separator = ","),
            "failed_stage:$currentStage",
            violation,
        )
        return IrohProbeTurnMetrics(
            turn = turn,
            dialMs = dialMs,
            errorFrames = error,
            scenarioViolations = listOf(violation),
            dialSucceeded = dialMs != null,
            timedOut = timedOut,
        )
    }
}

internal fun newProbeScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
internal fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

internal fun AppServerInboundFrame.matches(runtime: AppServerRuntimeScope): Boolean {
    val frameRuntime = this.runtime ?: return true
    return frameRuntime.agentId == runtime.agentId && frameRuntime.conversationId == runtime.conversationId
}
