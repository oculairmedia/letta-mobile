package com.letta.mobile.plugin.testkit

import com.letta.mobile.plugin.api.ActionCall
import com.letta.mobile.plugin.api.ActionContext
import com.letta.mobile.plugin.api.ActionOrigin
import com.letta.mobile.plugin.api.ActionResult
import com.letta.mobile.plugin.api.CanvasPlugin
import com.letta.mobile.plugin.api.ElementEvent
import com.letta.mobile.plugin.api.ElementEventType
import com.letta.mobile.plugin.api.LcpMethod
import com.letta.mobile.plugin.api.PluginHealth
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.CopyOnWriteArrayList

/** One pass of [PluginConformance.run] over one plugin: the lifetime in order, then the secret scan. */
internal class ConformanceRun(
    private val plugin: CanvasPlugin,
    private val manifest: ConformanceManifest,
    private val options: ConformanceOptions,
) {
    private val findings = CopyOnWriteArrayList<ConformanceFinding>()
    private val secrets = options.secretsFor(manifest)
    private val host = FakePluginHost(
        manifest = manifest,
        settings = options.settings ?: manifest.defaultSettings(),
        secrets = secrets,
        httpHandler = options.httpHandler,
    )
    private val caller = DeadlineCaller(options, ::finding)
    private val said = PluginOutputs()
    private var calls = 0

    suspend fun execute(): ConformanceReport {
        host.phase = HostPhase.INITIALIZING
        if (start()) exercise()
        stop()
        caller.close()
        val leaks = SecretScan.leaks(secrets, said.with(host))
        return ConformanceReport(findings + host.violations + leaks)
    }

    private suspend fun start(): Boolean {
        val info = caller.call(LcpMethod.INITIALIZE) { plugin.initialize(host) }
        if (info is CallOutcome.Answered) said.add("the plugin info", info.value.toString())
        if (!completed(info, "initialize")) return false
        host.phase = HostPhase.ACTIVE
        return completed(caller.call(LcpMethod.ACTIVATE) { plugin.activate() }, "activate")
    }

    private suspend fun exercise() {
        checkHealth()
        manifest.actions.forEach { (name, action) -> invokeDeclared(name, action) }
        invokeUndeclared()
        sendElementEvents()
        lifecycleCall(LcpMethod.SETTINGS_CHANGED, "onSettingsChanged") { plugin.onSettingsChanged(host.settings) }
    }

    private suspend fun stop() {
        lifecycleCall(LcpMethod.DEACTIVATE, "deactivate") { plugin.deactivate() }
        lifecycleCall(LcpMethod.DEACTIVATE, "a second deactivate") { plugin.deactivate() }
        host.close()
        delay(options.settleMillis)
    }

    private suspend fun checkHealth() {
        val outcome = caller.call(LcpMethod.HEALTH) { plugin.health() }
        completed(outcome, "health")
        val health = (outcome as? CallOutcome.Answered)?.value ?: return
        said.add("health", health.toString())
        if (health is PluginHealth.Failed) finding(ConformanceRule.LIFECYCLE, "health() is Failed right after activate: ${health.reason}")
    }

    private suspend fun invokeDeclared(name: String, action: ConformanceManifest.Action) {
        val input = options.inputs[name] ?: JsonSchemaSubset.sample(action.input).jsonObject
        val problems = JsonSchemaSubset.problems(action.input, input)
        if (problems.isNotEmpty()) {
            finding(ConformanceRule.ACTION, "the input for '$name' breaks its schema: ${problems.joinToString("; ")}")
            return
        }
        when (val outcome = invoke(name, input)) {
            is CallOutcome.Answered -> judgeDeclared(name, outcome.value)
            is CallOutcome.Threw -> finding(ConformanceRule.ACTION, "invoke('$name') threw ${outcome.error}")
            CallOutcome.Late -> Unit
        }
    }

    private fun judgeDeclared(name: String, result: ActionResult) {
        said.add("the result of '$name'", result.toString())
        when (result) {
            is ActionResult.Ok -> result.emit?.let(host::apply)
            is ActionResult.Error -> declaredErrorProblem(name, result)?.let { finding(ConformanceRule.ACTION, it) }
        }
    }

    private fun declaredErrorProblem(name: String, error: ActionResult.Error): String? = when {
        error.code.isBlank() -> "'$name' answered an Error with a blank code"
        error.code == ActionResult.Error.UNKNOWN_ACTION -> "'$name' is declared in the manifest, but the plugin answered unknown_action"
        else -> null
    }

    private suspend fun invokeUndeclared() {
        val expected = "Error(${ActionResult.Error.UNKNOWN_ACTION})"
        when (val outcome = invoke(UNDECLARED_ACTION, JsonObject(emptyMap()))) {
            is CallOutcome.Answered -> if (!isUnknownAction(outcome.value)) {
                said.add("the result of an undeclared action", outcome.value.toString())
                finding(ConformanceRule.ACTION, "the undeclared action '$UNDECLARED_ACTION' answered ${outcome.value}, not $expected")
            }
            is CallOutcome.Threw -> finding(ConformanceRule.ACTION, "the undeclared action '$UNDECLARED_ACTION' threw ${outcome.error}, not $expected")
            CallOutcome.Late -> Unit
        }
    }

    private fun isUnknownAction(result: ActionResult): Boolean =
        result is ActionResult.Error && result.code == ActionResult.Error.UNKNOWN_ACTION

    private suspend fun invoke(action: String, input: JsonObject): CallOutcome<ActionResult> {
        calls += 1
        val origin = ActionOrigin.Agent(agentId = "conformance-agent", conversationId = "conformance-conversation", toolCallId = "call-$calls")
        val call = ActionCall(action, input, ActionContext(origin, canvasId = FakePluginHost.CANVAS_ID))
        return caller.call(LcpMethod.INVOKE) { plugin.invoke(call) }
    }

    private suspend fun sendElementEvents() {
        host.elements.forEach { element ->
            EVENTS.forEach { type ->
                val event = ElementEvent(element.type.substringAfter('/'), element.id, type, element.frame, element.canvasId)
                lifecycleCall(LcpMethod.ELEMENT_EVENT, "onElementEvent($type)") { plugin.onElementEvent(event) }
            }
        }
    }

    private suspend fun lifecycleCall(method: LcpMethod, what: String, block: suspend () -> Unit) {
        completed(caller.call(method, block), what)
    }

    /** Whether the call answered; a throw is a [ConformanceRule.LIFECYCLE] finding (a late call is already a deadline one). */
    private fun completed(outcome: CallOutcome<*>, what: String): Boolean {
        if (outcome is CallOutcome.Threw) finding(ConformanceRule.LIFECYCLE, "$what threw ${outcome.error}")
        return outcome is CallOutcome.Answered
    }

    private fun finding(rule: ConformanceRule, message: String) {
        findings += ConformanceFinding(rule, message)
    }

    private companion object {
        const val UNDECLARED_ACTION = "lcp_conformance_undeclared"
        val EVENTS = listOf(ElementEventType.FOCUSED, ElementEventType.REMOVED)
    }
}
