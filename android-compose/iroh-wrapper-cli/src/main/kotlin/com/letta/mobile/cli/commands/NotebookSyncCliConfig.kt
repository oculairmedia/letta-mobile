package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.option

internal fun parseNotebookPeers(directory: String?, ids: String?): Set<String>? {
    require((directory == null) == (ids == null)) {
        "Notebook sync requires both --notebook-dir and --notebook-peer-ids"
    }
    if (directory == null) return null
    require(directory.isNotBlank()) { "Notebook directory must not be blank" }
    val peers = requireNotNull(ids).split(',').map(String::trim).filter(String::isNotEmpty).toSet()
    require(peers.isNotEmpty()) { "Notebook sync requires at least one authorized peer" }
    require(peers.all { it.matches(Regex("[0-9a-f]{64}")) }) {
        "Notebook peers must be lowercase 64-character Iroh endpoint IDs"
    }
    return peers
}
