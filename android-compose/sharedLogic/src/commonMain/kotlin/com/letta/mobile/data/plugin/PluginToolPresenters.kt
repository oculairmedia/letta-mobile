package com.letta.mobile.data.plugin

import com.letta.mobile.data.chat.tools.ToolPresenterRegistry

/** [this] with the tool and command presenters every one of [manifests] declares, each owned by its plugin's id. */
fun ToolPresenterRegistry.contributedBy(manifests: List<PluginManifest>): ToolPresenterRegistry =
    manifests.fold(this) { registry, manifest -> registry.contribute(manifest.id, manifest.toolPresenters, manifest.commandPresenters) }
