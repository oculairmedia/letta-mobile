package com.letta.mobile.cli.commands

internal fun parseNotebookPeers(directory: String?, ids: String?, grantsFile: String? = null): Set<String>? {
    require((directory == null) == (ids == null)) {
        "Notebook sync requires both --notebook-dir and --notebook-peer-ids"
    }
    require(directory == null || grantsFile == null) {
        "Notebook grants cannot use unrestricted repository sync. " +
            "Use --notebook-authority-dir with --notebook-grants-file and remove --notebook-dir / --notebook-peer-ids"
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
