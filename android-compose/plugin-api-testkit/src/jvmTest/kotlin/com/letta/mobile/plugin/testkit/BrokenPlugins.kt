package com.letta.mobile.plugin.testkit

import com.letta.mobile.plugin.api.ActionResult
import com.letta.mobile.plugin.api.ElementQuery
import com.letta.mobile.plugin.api.LogLevel
import com.letta.mobile.plugin.api.PlaceElement
import com.letta.mobile.plugin.api.PluginEmit
import com.letta.mobile.plugin.api.PluginHealth
import com.letta.mobile.plugin.api.PluginHost
import com.letta.mobile.plugin.api.PluginInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.thread

// Purposely broken variants of SamplePlugin: each breaks one ConformanceRule.

// LIFECYCLE

class ThrowsOnActivate : SamplePlugin() {
    override fun activate(): Unit = error("cannot start")
}

class DeactivateOnlyOnce : SamplePlugin() {
    private var stopped = false

    override fun deactivate() {
        check(!stopped) { "already stopped" }
        stopped = true
    }
}

class EmitsBeforeActivate : SamplePlugin() {
    override fun initialize(host: PluginHost): PluginInfo {
        val info = super.initialize(host)
        runCatching { runBlocking { host.emit(PluginEmit(remove = listOf("nothing"))) } }
        return info
    }
}

class UsesHostAfterDeactivate : SamplePlugin() {
    override fun deactivate() {
        thread {
            Thread.sleep(LATE_MILLIS)
            runCatching { host.log(LogLevel.INFO, "still here") }
        }
    }

    private companion object {
        const val LATE_MILLIS = 60L
    }
}

class FailedAfterActivate : SamplePlugin() {
    override fun health(): PluginHealth = PluginHealth.Failed("no service")
}

// ACTION

class ThrowsInDeclaredAction : SamplePlugin() {
    override suspend fun echo(input: JsonObject): ActionResult = error("echo is broken")
}

class ForgetsDeclaredAction : SamplePlugin() {
    override suspend fun echo(input: JsonObject): ActionResult = unknown("echo")
}

class AcceptsUndeclaredAction : SamplePlugin() {
    override fun unknown(action: String): ActionResult = ActionResult.Ok("sure, $action")
}

// EMIT

class EmitsUndeclaredKind : SamplePlugin() {
    override fun card(label: String, snapshotRef: String): PlaceElement = super.card(label, snapshotRef).copy(kind = "poster")
}

class EmitsInvalidProps : SamplePlugin() {
    override fun card(label: String, snapshotRef: String): PlaceElement =
        super.card(label, snapshotRef).let { it.copy(props = JsonObject(it.props + ("status" to JsonPrimitive("busy")))) }
}

class EmitsStaleVersion : SamplePlugin() {
    override fun card(label: String, snapshotRef: String): PlaceElement = super.card(label, snapshotRef).copy(v = 1)
}

class RemovesForeignElement : SamplePlugin() {
    override suspend fun echo(input: JsonObject): ActionResult = ActionResult.Ok("bye", emit = PluginEmit(remove = listOf("someone-elses")))
}

// CAPABILITY

class CallsUndeclaredOrigin : SamplePlugin() {
    override suspend fun echo(input: JsonObject): ActionResult {
        runCatching { host.httpClient.get("https://elsewhere.test/track") }
        return super.echo(input)
    }
}

class ReadsElements : SamplePlugin() {
    override suspend fun echo(input: JsonObject): ActionResult {
        runCatching { host.readElements(ElementQuery()) }
        return super.echo(input)
    }
}

// DEADLINE

class SlowAction : SamplePlugin() {
    override suspend fun echo(input: JsonObject): ActionResult {
        delay(SLOW_MILLIS)
        return super.echo(input)
    }

    companion object {
        const val SLOW_MILLIS = 2_000L
    }
}

class BlockingHealth : SamplePlugin() {
    override fun health(): PluginHealth {
        Thread.sleep(BLOCK_MILLIS)
        return PluginHealth.Ok
    }

    companion object {
        const val BLOCK_MILLIS = 600L
    }
}

// SECRET_LEAK

class LogsSecret : SamplePlugin() {
    override fun activate() {
        host.log(LogLevel.DEBUG, "token is ${host.secret("apiToken")}")
    }
}

class SecretInUrl : SamplePlugin() {
    override suspend fun echo(input: JsonObject): ActionResult {
        host.httpClient.get("$SERVICE/echo?token=${host.secret("apiToken")}")
        return super.echo(input)
    }
}

class SecretInResult : SamplePlugin() {
    override suspend fun echo(input: JsonObject): ActionResult = ActionResult.Ok("configured with ${host.secret("apiToken")}")
}
