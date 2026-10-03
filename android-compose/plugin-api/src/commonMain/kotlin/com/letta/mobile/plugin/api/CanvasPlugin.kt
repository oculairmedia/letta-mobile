package com.letta.mobile.plugin.api

import kotlinx.serialization.json.JsonObject

/**
 * What a jvm-runtime plugin implements (plan section 3.2). The manifest's `runtime.entry` names a
 * public class with a public no-argument constructor that implements this interface; the host
 * loads it in its own child-first class loader (R1: consented code, isolated by the loader, a
 * bounded dispatcher and deadlines, never sandboxed).
 *
 * Every member maps one-to-one to an LCP wire method ([LcpMethod]), so a jar plugin and a process
 * or service plugin behave alike. The host calls them in this order, one at a time:
 *
 * 1. [initialize] once, with the [PluginHost] the plugin keeps (wire `plugin.initialize`);
 * 2. [activate] once (wire `plugin.activate`);
 * 3. any number of [invoke], [onElementEvent], [onSettingsChanged] and [health];
 * 4. [deactivate], after which the host cancels [PluginHost.scope] and drops the class loader.
 *
 * A call that throws is a fault: the host marks the plugin `Faulted` and answers the caller with an
 * error. A call that outlives its deadline ([LcpMethod.deadlineMillis]) is cancelled and counted as
 * a fault too, so long work belongs in [PluginHost.scope], not in a call.
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
