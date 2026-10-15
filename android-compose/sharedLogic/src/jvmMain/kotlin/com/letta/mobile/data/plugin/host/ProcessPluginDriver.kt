package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.model.PluginId
import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginManifest
import com.letta.mobile.data.plugin.PluginRuntime
import com.letta.mobile.data.plugin.PluginSecrets
import com.letta.mobile.data.plugin.PluginTemplates
import com.letta.mobile.data.plugin.TemplateResolution
import com.letta.mobile.data.plugin.wire.InitializeParams
import com.letta.mobile.data.plugin.wire.LcpCallException
import com.letta.mobile.data.plugin.wire.LcpErrorCode
import com.letta.mobile.data.plugin.wire.LcpHostBinding
import com.letta.mobile.data.plugin.wire.LcpHostHandlers
import com.letta.mobile.data.plugin.wire.LcpHostSession
import com.letta.mobile.data.plugin.wire.LcpWire
import com.letta.mobile.plugin.api.ActionCall
import com.letta.mobile.plugin.api.ActionResult
import com.letta.mobile.plugin.api.ElementEvent
import com.letta.mobile.plugin.api.HostInfo
import com.letta.mobile.plugin.api.PluginHealth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * One installed `process` plugin, as the host runs it: the package it lives in, its resolved
 * settings and secrets, and the capabilities the user consented to (only those consented AND still
 * declared reach the capability guard).
 */
class ProcessPluginSpec(
    val manifest: PluginManifest,
    val packageDir: Path,
    val settings: JsonObject,
    val secrets: PluginSecrets,
    val consentedCapabilities: Set<PluginCapability>,
)

/** The knobs of a [ProcessPluginDriver]; the timeouts default to the [com.letta.mobile.plugin.api.LcpMethod] deadlines. */
data class ProcessDriverOptions(
    val hostInfo: HostInfo,
    /** The only host variable the plugin inherits, and where a bare `runtime.command` is looked up. */
    val path: String = System.getenv("PATH").orEmpty(),
    val confinement: ProcessConfinementSource = SystemdScopeProbe.DEFAULT,
    val caps: ProcessCaps = ProcessCaps(),
    val healthInterval: Duration = 30.seconds,
    val healthTimeout: Duration? = null,
    val invokeTimeout: Duration? = null,
    /** How long a stopping plugin gets between SIGTERM and SIGKILL. */
    val killGrace: Duration = 5.seconds,
    val crashPolicy: CrashPolicy = CrashPolicy(),
    /** Each stderr line, already scrubbed of the plugin's secrets. */
    val onStderr: (String) -> Unit = {},
)

/**
 * Runs a manifest's `runtime.process` as a subprocess speaking LCP over stdio (letta-mobile-cdgpr.4).
 *
 * Trust minimum: the environment is `PATH` plus the manifest's resolved `env` substitutions and
 * nothing else of the host's (secrets reach the plugin only that way); the working directory and
 * a path-qualified command stay inside the package; the process runs in a capped
 * `systemd-run --user --scope` when the host has one, else a node runtime gets its heap capped by
 * flag; every call goes through [LcpHostSession] under its [com.letta.mobile.plugin.api.LcpMethod]
 * deadline and the 4 MiB message cap.
 *
 * Lifecycle: [start] spawns, initializes, activates and polls `plugin.health` every
 * [ProcessDriverOptions.healthInterval]. An exit, a failed handshake or a failed health check is a
 * crash: the process is killed and restarted after a full-jitter backoff, until
 * [CrashPolicy.maxCrashes] crashes in the window leave the driver [PluginDriverState.Faulted].
 * [stop] sends `plugin.deactivate`, then SIGTERM, then SIGKILL after [ProcessDriverOptions.killGrace].
 * The supervisor runs in the caller's [scope] and ends with [stop], a fault, or that scope.
 */
class ProcessPluginDriver(
    private val spec: ProcessPluginSpec,
    private val handlers: LcpHostHandlers,
    private val scope: CoroutineScope,
    private val options: ProcessDriverOptions,
) : PluginDriver {
    private class Live(val process: Process, val session: LcpHostSession)

    private val mutableState = MutableStateFlow<PluginDriverState>(PluginDriverState.Starting)
    private val ledger = CrashLedger(options.crashPolicy)
    private val scrubber = spec.secrets.scrubber()

    @Volatile private var current: Live? = null

    @Volatile private var stopping = false

    private var supervisor: Job? = null

    override val pluginId: String get() = spec.manifest.id

    override val state: StateFlow<PluginDriverState> = mutableState.asStateFlow()

    override suspend fun start(): PluginDriverState {
        if (supervisor == null) {
            val plan = try {
                withContext(Dispatchers.IO) { planLaunch() }
            } catch (e: PluginLaunchException) {
                return fault(e.message.orEmpty())
            }
            supervisor = scope.launch { supervise(plan) }
        }
        return state.first { it is PluginDriverState.Running || it.terminal }
    }

    override suspend fun invoke(call: ActionCall): ActionResult {
        val began = TimeSource.Monotonic.markNow()
        val outcome = runCatching { live().session.invoke(call, options.invokeTimeout) }
        val label = outcome.fold(
            onSuccess = { if (it is ActionResult.Error) "error:${it.code}" else "ok" },
            onFailure = { if (it is LcpCallException) "failed:${it.code}" else "failed" },
        )
        val tookMs = began.elapsedNow().inWholeMilliseconds
        PluginHostEvents.timed(PluginHostEvent.INVOKE, PluginId(pluginId), tookMs, "action" to call.action, "outcome" to label)
        return outcome.getOrThrow()
    }

    override suspend fun elementEvent(event: ElementEvent) = live().session.elementEvent(event)

    override suspend fun stop() {
        stopping = true
        current?.let { live ->
            try {
                live.session.deactivate()
            } catch (_: LcpCallException) {
                // The plugin would not deactivate cleanly; the signals below end it either way.
            }
        }
        supervisor?.cancelAndJoin()
        if (!state.value.terminal) mutableState.value = PluginDriverState.Stopped("deactivated")
    }

    private fun live(): Live = current ?: throw LcpCallException(LcpErrorCode.CLOSED, "plugin $pluginId is not running")

    private fun fault(reason: String): PluginDriverState = PluginDriverState.Faulted(reason).also { mutableState.value = it }

    private fun planLaunch(): ProcessLaunchPlan {
        val runtime = spec.manifest.runtime as? PluginRuntime.Process
            ?: throw PluginLaunchException("runtime ${PluginRuntime.kindOf(spec.manifest.runtime)} is not a process")
        val env = when (val resolution = PluginTemplates.resolve(runtime.env, spec.settings, spec.secrets)) {
            is TemplateResolution.Resolved -> resolution.values
            is TemplateResolution.Refused -> throw PluginLaunchException(resolution.problems.joinToString("; "))
        }
        return ProcessLaunchPlanner.plan(
            runtime,
            spec.packageDir,
            env,
            ProcessHostEnvironment(options.path, options.confinement.confinement(), options.caps),
        )
    }

    private suspend fun supervise(plan: ProcessLaunchPlan) {
        while (true) {
            val reason = runOnce(plan)
            if (stopping) return
            val crashes = ledger.record()
            if (ledger.exhausted(crashes)) {
                fault("crashed $crashes times within ${options.crashPolicy.window}; last: $reason")
                return
            }
            mutableState.value = PluginDriverState.Restarting(reason, crashes)
            delay(ledger.delayFor(crashes))
        }
    }

    /** One process from spawn to exit; answers why it ended. */
    private suspend fun runOnce(plan: ProcessLaunchPlan): String = coroutineScope {
        mutableState.value = PluginDriverState.Starting
        val process = try {
            withContext(Dispatchers.IO) { plan.start() }
        } catch (e: IOException) {
            return@coroutineScope "could not start: ${e.message.orEmpty()}"
        }
        val transport = StdioLcpTransport(process, this, ::onStderr)
        val session = LcpHostSession(transport, binding(), this)
        val ended = CompletableDeferred<String>()
        launch { ended.complete("exited with code ${process.onExit().await().exitValue()}") }
        try {
            session.initialize(initializeParams())
            session.activate()
            current = Live(process, session)
            mutableState.value = PluginDriverState.Running(process.pid())
            launch { watchHealth(session, ended) }
            ended.await()
        } catch (e: LcpCallException) {
            "did not start: ${e.message.orEmpty()}"
        } finally {
            current = null
            withContext(NonCancellable) {
                session.close()
                terminate(process, if (stopping) options.killGrace else Duration.ZERO)
            }
            coroutineContext.cancelChildren()
        }
    }

    private suspend fun watchHealth(session: LcpHostSession, ended: CompletableDeferred<String>) {
        while (true) {
            delay(options.healthInterval)
            val failure = try {
                (session.health(options.healthTimeout) as? PluginHealth.Failed)?.let { "unhealthy: ${it.reason}" }
            } catch (e: LcpCallException) {
                "health check failed: ${e.message.orEmpty()}"
            }
            if (failure != null) {
                ended.complete(failure)
                return
            }
        }
    }

    private fun binding(): LcpHostBinding =
        LcpHostBinding(spec.consentedCapabilities intersect spec.manifest.capabilities.toSet(), scrubber, handlers)

    private fun initializeParams(): InitializeParams = InitializeParams(
        contractVersion = LcpWire.CONTRACT_VERSION,
        contractVersions = LcpWire.SUPPORTED_CONTRACT_VERSIONS,
        pluginId = spec.manifest.id,
        settings = spec.settings,
        hostInfo = options.hostInfo,
    )

    private fun onStderr(line: String) = options.onStderr(scrubber.scrub(line))

    private companion object {
        /** SIGTERM, then SIGKILL once [grace] passes (at once for zero); descendants are killed too. */
        suspend fun terminate(process: Process, grace: Duration) = withContext(Dispatchers.IO) {
            val descendants = process.descendants().toList()
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(grace.inWholeMilliseconds, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                process.waitFor()
            }
            descendants.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly)
        }
    }
}
