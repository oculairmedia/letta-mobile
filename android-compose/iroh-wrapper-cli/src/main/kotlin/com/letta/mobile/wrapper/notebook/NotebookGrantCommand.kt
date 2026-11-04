package com.letta.mobile.wrapper.notebook

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.NotebookPeerGrant
import com.letta.mobile.data.canvas.authority.FileNotebookGrantStore
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

/** Host-local administration of notebook grants; the grant file directory must be protected. */
class NotebookGrantCommand : CliktCommand(name = "notebook-grant") {
    override fun help(context: Context): String = "Provision or revoke one host notebook grant."
    private val file by option("--file", envvar = "LETTA_NOTEBOOK_GRANTS_FILE", help = "Protected host grant file.")
    private val principal by option("--principal", help = "Authenticated Iroh endpoint ID (64 lowercase hex characters).")
    private val document by option("--document", help = "Exact notebook document ID (32 lowercase hex characters).")
    private val read by option("--read", help = "Grant read access.").flag()
    private val edit by option("--edit", help = "Grant edit access.").flag()
    private val reference by option("--reference", help = "Grant reference access (also needs read).").flag()
    private val revoke by option("--revoke", help = "Remove this principal/document grant.").flag()

    override fun run() = runBlocking {
        val path = grantPath()
        val peer = grantPrincipal()
        val id = grantDocument()
        validateCapabilities()
        val store = FileNotebookGrantStore(path)
        if (revoke) store.revoke(peer, id)
        else store.provision(id, NotebookPeerGrant(peer, read, edit, mayReference = reference))
    }

    private fun grantPath(): Path {
        val filePath = file?.takeIf { it.isNotBlank() } ?: fail("--file is required")
        return Path.of(filePath)
    }

    private fun grantPrincipal(): String = principal?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        ?: fail("--principal must be a lowercase Iroh endpoint ID")

    private fun grantDocument(): NotebookDocumentId = NotebookDocumentId(
        document?.takeIf { it.matches(Regex("[0-9a-f]{32}")) }
            ?: fail("--document must be an exact lowercase notebook ID"),
    )

    private fun validateCapabilities() {
        if (!revoke) return
        if (listOf(read, edit, reference).any { it }) fail("--revoke cannot be combined with capabilities")
    }

    private fun fail(message: String): Nothing = throw UsageError(message)
}
