package com.letta.mobile.plugin.api

import kotlinx.serialization.json.JsonObject

/**
 * Kotlin SPI for a JVM-runtime plugin. The manifest's `runtime.entry` identifies a public class
 * with a public no-argument constructor implementing this interface. This declaration defines
 * the contract; the production host currently executes process plugins over LCP and does not
 * provide an in-process JAR loader.
 *
 * Members map to LCP wire methods through [LcpMethod]. Lifecycle ordering is:
 *
 * 1. [initialize] once, with the [PluginHost] the plugin keeps (wire `plugin.initialize`);
 * 2. [activate] once (wire `plugin.activate`);
 * 3. any number of [invoke], [onElementEvent], [onSettingsChanged] and [health];
 * 4. [deactivate], after which an SPI executor must cancel [PluginHost.scope] and release resources.
 *
 * Active calls may overlap; the wire implementation bounds concurrent requests rather than
 * serializing every call. Request errors and deadlines do not by themselves mean the driver
 * becomes Faulted: the executor owns its lifecycle and restart policy. Keep background work in
 * [PluginHost.scope] and honor call cancellation and [LcpMethod.deadlineMillis].
 */
public interface CanvasPlugin {
    /**
     * Receives the [host] for the plugin's lifetime and answers who the plugin is. Keep it cheap:
     * no network, no work. The default answers an empty [PluginInfo].
     */
    public fun initialize(host: PluginHost): PluginInfo = PluginInfo()

    /** Starts the plugin's work (background jobs go in [PluginHost.scope]). Called once, after [initialize]. */
    public fun activate()

    /**
     * Runs one action from an agent, one of the plugin's own pages, or the host. [call]'s
     * `action` is one the manifest declares and its `input` already holds the action's input
     * schema; an action the plugin does not know answers [ActionResult.Error] with
     * [ActionResult.Error.UNKNOWN_ACTION], never a throw.
     */
    public suspend fun invoke(call: ActionCall): ActionResult

    /** Learns that one of the plugin's own elements moved, was removed, focused, or had its page opened or closed. */
    public suspend fun onElementEvent(event: ElementEvent) {}

    /** Learns that the owner changed the settings; [settings] are resolved and valid, as [PluginHost.settings] now is. */
    public fun onSettingsChanged(settings: JsonObject) {}

    /** Answers at once whether the plugin can do its work (no network round trip inside the call). */
    public fun health(): PluginHealth

    /**
     * Stops the plugin's work and releases what it holds. Called once, but must tolerate a second
     * call (a crash path may repeat it); the host uses no member of the plugin afterwards.
     */
    public fun deactivate()
}
