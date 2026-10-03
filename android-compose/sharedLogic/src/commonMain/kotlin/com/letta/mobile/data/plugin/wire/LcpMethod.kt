package com.letta.mobile.data.plugin.wire

import com.letta.mobile.data.plugin.PluginCapability
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Which side sends a method. */
enum class LcpDirection(val wire: String) {
    HOST_TO_PLUGIN("host->plugin"),
    PLUGIN_TO_HOST("plugin->host"),
    EITHER("either"),
    ;

    /** Whether a peer on [side] may receive this method. */
    fun deliversTo(side: LcpSide): Boolean = when (this) {
        HOST_TO_PLUGIN -> side == LcpSide.PLUGIN
        PLUGIN_TO_HOST -> side == LcpSide.HOST
        EITHER -> true
    }
}

/** The side a peer plays. */
enum class LcpSide { HOST, PLUGIN }

/** A request is answered; a notification is not. */
enum class LcpCallKind { REQUEST, NOTIFICATION }

/** How a method travels: who sends it, whether it is answered, the receiver's deadline, the capability it needs. */
data class LcpRoute(
    val direction: LcpDirection,
    val kind: LcpCallKind,
    val deadline: Duration? = null,
    val capability: PluginCapability? = null,
)

private fun toPlugin(deadline: Duration): LcpRoute = LcpRoute(LcpDirection.HOST_TO_PLUGIN, LcpCallKind.REQUEST, deadline)

private fun toHost(deadline: Duration, capability: PluginCapability? = null): LcpRoute =
    LcpRoute(LcpDirection.PLUGIN_TO_HOST, LcpCallKind.REQUEST, deadline, capability)

private fun notification(direction: LcpDirection): LcpRoute = LcpRoute(direction, LcpCallKind.NOTIFICATION)

/**
 * The closed method set of LCP wire v1 (plan section 5): the one registry both the wire and the
 * Kotlin SPI (`:plugin-api`, letta-mobile-s416w.26) mirror. [spi] names the SPI member a method
 * serialises, so a driver (.28) maps the two one to one. The [route]'s deadline is how long the
 * receiver gets; its capability is what a plugin must hold for the host to serve the method (emit
 * is checked per content by [LcpCapabilityGuard]).
 */
enum class LcpMethod(val wire: String, val route: LcpRoute, val spi: String) {
    INITIALIZE("plugin.initialize", toPlugin(10.seconds), "PluginHost.contractVersion + PluginHost.settings"),
    ACTIVATE("plugin.activate", toPlugin(30.seconds), "CanvasPlugin.activate"),
    HEALTH("plugin.health", toPlugin(5.seconds), "CanvasPlugin.health"),
    DEACTIVATE("plugin.deactivate", toPlugin(10.seconds), "CanvasPlugin.deactivate"),
    INVOKE("action.invoke", toPlugin(100.seconds), "CanvasPlugin.invoke"),
    ELEMENT_EVENT("element.event", notification(LcpDirection.HOST_TO_PLUGIN), "CanvasPlugin.onElementEvent"),
    SETTINGS_CHANGED("host.settingsChanged", notification(LcpDirection.HOST_TO_PLUGIN), "PluginHost.settings"),
    EMIT("host.emit", toHost(30.seconds), "PluginHost.emit"),
    PUT_ASSET_BEGIN("host.putAsset.begin", toHost(10.seconds, PluginCapability.ASSETS_WRITE), "PluginHost.putAsset"),
    PUT_ASSET_CHUNK("host.putAsset.chunk", toHost(10.seconds, PluginCapability.ASSETS_WRITE), "PluginHost.putAsset"),
    PUT_ASSET_END("host.putAsset.end", toHost(30.seconds, PluginCapability.ASSETS_WRITE), "PluginHost.putAsset"),
    READ_ELEMENTS("host.readElements", toHost(10.seconds, PluginCapability.CANVAS_READ), "PluginHost.readElements"),
    LOG("host.log", notification(LcpDirection.PLUGIN_TO_HOST), "PluginHost.log"),
    CANCEL("\$/cancel", notification(LcpDirection.EITHER), "coroutine cancellation"),
    ;

    val direction: LcpDirection get() = route.direction
    val isRequest: Boolean get() = route.kind == LcpCallKind.REQUEST
    val deadline: Duration? get() = route.deadline
    val capability: PluginCapability? get() = route.capability

    companion object {
        private val byWire: Map<String, LcpMethod> = entries.associateBy { it.wire }

        /** The method spelled [wire], or null for anything outside the closed set. */
        fun of(wire: String): LcpMethod? = byWire[wire]
    }
}
