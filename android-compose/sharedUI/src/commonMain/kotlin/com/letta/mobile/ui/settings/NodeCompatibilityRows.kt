package com.letta.mobile.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.node.info.NodeAvailability
import com.letta.mobile.data.node.info.NodeCompatibility
import com.letta.mobile.data.node.info.NodeHandshake
import com.letta.mobile.data.node.info.NodeHandshakeResult
import com.letta.mobile.data.node.info.NodeIncompatibility
import com.letta.mobile.data.node.info.NodeIncompatibilityKind
import com.letta.mobile.data.node.info.NodeInfoRpc
import com.letta.mobile.data.settings.SettingsRow
import com.letta.mobile.data.settings.SettingsStatus
import com.letta.mobile.data.settings.SettingsTone

/** The Backend section's row key for the connected node's compatibility. */
const val NODE_COMPATIBILITY_KEY: String = "backend-node"

/**
 * The connected node's version handshake: runs once each time the host hands the page an admin_rpc
 * to a newly connected host ([rpc]) or the [connection] changes, and is null while there is none or before it answers.
 */
@Composable
fun rememberNodeHandshake(rpc: NodeInfoRpc?, connection: Any? = null): NodeHandshakeResult? {
    var result by remember(rpc, connection) { mutableStateOf<NodeHandshakeResult?>(null) }
    LaunchedEffect(rpc, connection) {
        result = rpc?.let { NodeHandshake(it).run() }
    }
    return result
}

/** The handshake as the Backend section's Node row: a status in words and tone, and why. */
fun NodeHandshakeResult.settingsRow(): SettingsRow.Status =
    SettingsRow.Status(NODE_COMPATIBILITY_KEY, "Node", settingsStatus(), settingsHelper())

/** The Node row's status: never colour alone. */
fun NodeHandshakeResult.settingsStatus(): SettingsStatus = when (this) {
    NodeHandshakeResult.Legacy -> SettingsStatus("Not checked")
    is NodeHandshakeResult.Failed -> SettingsStatus("Not checked", SettingsTone.Warning)
    is NodeHandshakeResult.Checked -> when (compatibility.availability) {
        NodeAvailability.COMPATIBLE -> SettingsStatus("Compatible", SettingsTone.Positive)
        NodeAvailability.LIMITED -> SettingsStatus("Can't run agents here", SettingsTone.Warning)
        NodeAvailability.UNAVAILABLE -> SettingsStatus("Unavailable", SettingsTone.Error)
        NodeAvailability.UNVERIFIED -> SettingsStatus("Not checked")
    }
}

/** The Node row's helper: the versions when compatible, otherwise every reason. */
fun NodeHandshakeResult.settingsHelper(): String = when (this) {
    NodeHandshakeResult.Legacy -> "This node does not report its version yet."
    is NodeHandshakeResult.Failed -> message
    is NodeHandshakeResult.Checked -> compatibility.helper()
}

private fun NodeCompatibility.helper(): String {
    if (reasons.isNotEmpty()) return reasons.joinToString(" ") { it.sentence() }
    val node = info ?: return "This node does not report its version yet."
    return "letta-code ${node.lettaCodeVersion} · store format ${node.storeFormat}"
}

/** One reason as a sentence a person can act on: what differs, this side's value and the node's. */
fun NodeIncompatibility.sentence(): String = when (kind) {
    NodeIncompatibilityKind.SCHEMA -> "The node speaks node info $actual; this app reads $expected. Update the older one."
    NodeIncompatibilityKind.STORE_FORMAT -> "Store format $actual on the node, $expected here: agents can't move or replicate."
    NodeIncompatibilityKind.LETTA_CODE -> "letta-code $actual on the node, $expected here: agents can't run there."
    NodeIncompatibilityKind.RUNTIME_PATCHES -> "Runtime patches differ ($actual on the node, $expected here): agents can't run there."
}
