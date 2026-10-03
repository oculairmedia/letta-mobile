package com.letta.mobile.plugin.testkit

import com.letta.mobile.plugin.api.ActionCall
import com.letta.mobile.plugin.api.ActionContext
import com.letta.mobile.plugin.api.ActionOrigin
import com.letta.mobile.plugin.api.ActionResult
import com.letta.mobile.plugin.api.CanvasPlugin
import com.letta.mobile.plugin.api.ElementEvent
import com.letta.mobile.plugin.api.ElementEventType
import com.letta.mobile.plugin.api.LcpMethod
import com.letta.mobile.plugin.api.PluginElementView
import com.letta.mobile.plugin.api.PluginHealth
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.CopyOnWriteArrayList

/** One lifecycle call the kit makes, under its [method]'s deadline, named [label] in findings. */
internal enum class LifecycleStep(val method: LcpMethod, private val label: String) {
    INITIALIZE(LcpMethod.INITIALIZE, "initialize"),
    ACTIVATE(LcpMethod.ACTIVATE, "activate"),
    HEALTH(LcpMethod.HEALTH, "health"),
    ELEMENT_FOCUSED(LcpMethod.ELEMENT_EVENT, "onElementEvent(focused)"),
    ELEMENT_REMOVED(LcpMethod.ELEMENT_EVENT, "onElementEvent(removed)"),
    SETTINGS_CHANGED(LcpMethod.SETTINGS_CHANGED, "onSettingsChanged"),
    DEACTIVATE(LcpMethod.DEACTIVATE, "deactivate"),
    SECOND_DEACTIVATE(LcpMethod.DEACTIVATE, "a second deactivate"),
    ;

    override fun toString(): String = label
}

/** An action the kit invokes: its manifest name and the input it sends. */
internal data class ActionRequest(val name: String, val input: JsonObject) {
    val declared: Boolean get() = name != UNDECLARED.name

    override fun toString(): String = "'$name'"

    companion object {
        /** An action no manifest declares; the plugin must answer it with `Error(unknown_action)`. */
        val UNDECLARED = ActionRequest("lcp_conformance_undeclared", JsonObject(emptyMap()))
    }
}

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
    private val caller = DeadlineCaller(options) { findings += it }
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
        if (!completed(info, LifecycleStep.INITIALIZE)) return false
        host.phase = HostPhase.ACTIVE
        return completed(caller.call(LcpMethod.ACTIVATE) { plugin.activate() }, LifecycleStep.ACTIVATE)
    }

    private suspend fun exercise() {
        checkHealth()
        manifest.actions.forEach { (name, action) -> invokeDeclared(requestFor(name, action)) }
        invokeUndeclared()
        host.elements.forEach { sendEvents(it) }
        lifecycleCall(LifecycleStep.SETTINGS_CHANGED) { plugin.onSettingsChanged(host.settings) }
    }

    private suspend fun stop() {
        lifecycleCall(LifecycleStep.DEACTIVATE) { plugin.deactivate() }
        lifecycleCall(LifecycleStep.SECOND_DEACTIVATE) { plugin.deactivate() }
        host.close()
        delay(options.settleMillis)
    }

    private suspend fun checkHealth() {
        val outcome = caller.call(LcpMethod.HEALTH) { plugin.health() }
        completed(outcome, LifecycleStep.HEALTH)
        val health = (outcome as? CallOutcome.Answered)?.value ?: return
        said.add("health", health.toString())
        if (health is PluginHealth.Failed) {
            findings += ConformanceFinding(ConformanceRule.LIFECYCLE, "health() is Failed right after activate: ${health.reason}")
        }
    }

    private fun requestFor(name: String, action: ConformanceManifest.Action): ActionRequest =
        ActionRequest(name, options.inputs[name] ?: JsonSchemaSubset.sample(action.input).jsonObject)

    private suspend fun invokeDeclared(request: ActionRequest) {
        val schema = manifest.actions.getValue(request.name).input
        val problems = JsonSchemaSubset.problems(schema, request.input)
        if (problems.isNotEmpty()) {
            findings += actionFinding("the input for $request breaks its schema: ${problems.joinToString("; ")}")
            return
        }
        when (val outcome = invoke(request)) {
            is CallOutcome.Answered -> judge(request, outcome.value)
            is CallOutcome.Threw -> findings += actionFinding("invoke($request) threw ${outcome.error}")
            CallOutcome.Late -> Unit
        }
    }

    private suspend fun invokeUndeclared() {
        val request = ActionRequest.UNDECLARED
        when (val outcome = invoke(request)) {
            is CallOutcome.Answered -> judge(request, outcome.value)
            is CallOutcome.Threw -> findings += actionFinding("the undeclared action $request threw ${outcome.error}, not $UNKNOWN")
            CallOutcome.Late -> Unit
        }
    }

    private fun judge(request: ActionRequest, result: ActionResult) {
        said.add("the result of $request", result.toString())
        if (result is ActionResult.Ok && request.declared) result.emit?.let(host::apply)
        ActionVerdict.of(request, result)?.let { findings += actionFinding(it) }
    }

    private suspend fun invoke(request: ActionRequest): CallOutcome<ActionResult> {
        calls += 1
        val origin = ActionOrigin.Agent(agentId = "conformance-agent", conversationId = "conformance-conversation", toolCallId = "call-$calls")
        val call = ActionCall(request.name, request.input, ActionContext(origin, canvasId = FakePluginHost.CANVAS_ID))
        return caller.call(LcpMethod.INVOKE) { plugin.invoke(call) }
    }

    private suspend fun sendEvents(element: PluginElementView) {
        EVENTS.forEach { (type, step) ->
            val event = ElementEvent(element.type.substringAfter('/'), element.id, type, element.frame, element.canvasId)
            lifecycleCall(step) { plugin.onElementEvent(event) }
        }
    }

    private suspend fun lifecycleCall(step: LifecycleStep, block: suspend () -> Unit) {
        completed(caller.call(step.method, block), step)
    }

    /** Whether the call answered; a throw is a [ConformanceRule.LIFECYCLE] finding (a late call is already a deadline one). */
    private fun completed(outcome: CallOutcome<*>, step: LifecycleStep): Boolean {
        if (outcome is CallOutcome.Threw) findings += ConformanceFinding(ConformanceRule.LIFECYCLE, "$step threw ${outcome.error}")
        return outcome is CallOutcome.Answered
    }

    private fun actionFinding(message: String): ConformanceFinding = ConformanceFinding(ConformanceRule.ACTION, message)

    private companion object {
        const val UNKNOWN = "Error(${ActionResult.Error.UNKNOWN_ACTION})"
        val EVENTS = listOf(
            ElementEventType.FOCUSED to LifecycleStep.ELEMENT_FOCUSED,
            ElementEventType.REMOVED to LifecycleStep.ELEMENT_REMOVED,
        )
    }
}

/** What the action rule says about one answer: null when it keeps the contract. */
internal object ActionVerdict {
    fun of(request: ActionRequest, result: ActionResult): String? = when {
        !request.declared -> undeclared(request, result)
        result is ActionResult.Error -> declaredError(request, result)
        else -> null
    }

    private fun undeclared(request: ActionRequest, result: ActionResult): String? {
        val refused = result is ActionResult.Error && result.code == ActionResult.Error.UNKNOWN_ACTION
        return if (refused) null else "the undeclared action $request answered $result, not Error(${ActionResult.Error.UNKNOWN_ACTION})"
    }

    private fun declaredError(request: ActionRequest, error: ActionResult.Error): String? = when {
        error.code.isBlank() -> "$request answered an Error with a blank code"
        error.code == ActionResult.Error.UNKNOWN_ACTION -> "$request is declared in the manifest, but the plugin answered unknown_action"
        else -> null
    }
}
