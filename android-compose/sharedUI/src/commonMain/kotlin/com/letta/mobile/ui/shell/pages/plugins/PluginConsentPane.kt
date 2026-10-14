package com.letta.mobile.ui.shell.pages.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.catalog.PluginConsentItem
import com.letta.mobile.data.plugin.catalog.PluginConsentSheet
import com.letta.mobile.data.plugin.catalog.PluginsPageActions
import com.letta.mobile.ui.host.InspectorPane
import com.letta.mobile.ui.host.LettaPaneButton
import com.letta.mobile.ui.host.LettaPaneButtonRow
import com.letta.mobile.ui.host.LettaPaneButtons
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The consent sheet of a staged plugin: who made it, how it runs and the package's sha256, then every
 * capability, network origin and page it asks for, each with an explicit Allow or Deny. Install
 * waits for a decision on every item; any Deny refuses the install (sharedLogic's PluginsPageModel).
 */
@Composable
internal fun PluginConsentPane(sheet: PluginConsentSheet, busy: Boolean, actions: PluginsPageActions) {
    val request = sheet.request
    InspectorPane(title = "Install ${request.name}?", onClose = actions::cancelConsent, testTag = PluginsPageTags.CONSENT) {
        Column(
            Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = LettaDimens.Space.lg),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            Text("${request.id} ${request.version} by ${request.publisher}", style = MaterialTheme.typography.bodyLarge)
            Detail("Runs as a ${request.runtimeKind} plugin on the host.")
            Detail("Package SHA-256 ${request.sha256}")
            ConsentGroup(CAPABILITIES, sheet.capabilities, sheet, actions)
            ConsentGroup(ORIGINS, sheet.origins, sheet, actions)
            ConsentGroup(PAGES, sheet.pages, sheet, actions)
            if (sheet.items.isEmpty()) Detail(ASKS_NOTHING)
        }
        LettaPaneButtonRow(
            LettaPaneButtons(
                confirm = LettaPaneButton(INSTALL, actions::installConsented, enabled = sheet.allDecided && !busy, testTag = PluginsPageTags.CONSENT_INSTALL),
                dismiss = LettaPaneButton(CANCEL, actions::cancelConsent, testTag = PluginsPageTags.CONSENT_CANCEL),
            ),
        )
    }
}

@Composable
private fun ConsentGroup(title: String, items: List<PluginConsentItem>, sheet: PluginConsentSheet, actions: PluginsPageActions) {
    if (items.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = LettaDimens.Space.sm))
    items.forEach { item -> ConsentItemRow(item, sheet.decision(item)) { allow -> actions.decide(item, allow) } }
}

@Composable
private fun ConsentItemRow(item: PluginConsentItem, decision: Boolean?, onDecide: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(item.label, style = MaterialTheme.typography.bodyMedium)
            item.description?.let { Detail(it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
            FilterChip(decision == true, onClick = { onDecide(true) }, label = { Text(ALLOW) }, modifier = Modifier.testTag(PluginsPageTags.allow(item)))
            FilterChip(decision == false, onClick = { onDecide(false) }, label = { Text(DENY) }, modifier = Modifier.testTag(PluginsPageTags.deny(item)))
        }
    }
}

@Composable
private fun Detail(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** What allowing the item lets the plugin do. */
private val PluginConsentItem.description: String?
    get() = when (this) {
        is PluginConsentItem.Capability -> CapabilityDescriptions[capability]
        is PluginConsentItem.Origin -> "The plugin may connect to this address."
        is PluginConsentItem.Page -> "A page the plugin shows inside its elements."
    }

private val CapabilityDescriptions: Map<PluginCapability, String> = mapOf(
    PluginCapability.CANVAS_PLACE to "Place its elements on canvas boards.",
    PluginCapability.CANVAS_READ to "Read the boards it places elements on.",
    PluginCapability.ASSETS_WRITE to "Store images and files on the host.",
    PluginCapability.NET_CONNECT to "Reach the network origins listed below.",
    PluginCapability.UI_PAGES to "Show its own pages inside its elements.",
    PluginCapability.UI_OPEN_LINK to "Open links (asked again on first use).",
    PluginCapability.UI_CLIPBOARD_WRITE to "Write to the clipboard (asked again on first use).",
    PluginCapability.UI_CAMERA to "Use the camera (asked again on first use).",
    PluginCapability.UI_MICROPHONE to "Use the microphone (asked again on first use).",
    PluginCapability.UI_GEOLOCATION to "Read this device's location (asked again on first use).",
)

private const val CAPABILITIES = "Capabilities"
private const val ORIGINS = "Network origins"
private const val PAGES = "Pages"
private const val ASKS_NOTHING = "This plugin asks for no capabilities, origins or pages."
private const val ALLOW = "Allow"
private const val DENY = "Deny"
private const val INSTALL = "Install"
private const val CANCEL = "Cancel"
