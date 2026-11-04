package com.letta.mobile.wrapper.notebook

import com.letta.mobile.cli.commands.parseNotebookPeers
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NotebookSyncConfigurationTest {
    @Test
    fun grantConfigurationCannotAlsoEnableUnrestrictedSync() {
        val cases = requireNotNull(javaClass.getResourceAsStream("/notebook-authority/sync-config-v1.json"))
            .use { Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
        val root = Files.createTempDirectory("notebook-sync-config")
        Files.writeString(root.resolve("grants.json"), """{"version":1,"grants":[]}""")
        Files.writeString(root.resolve("corrupt-grants.json"), "{corrupt")
        val peer = "a".repeat(64)
        cases.forEach { (name, raw) ->
            val case = raw.jsonObject
            val file = case.getValue("grantFile").takeUnless { it == JsonNull }
                ?.jsonPrimitive?.content?.let { root.resolve(it).toString() }
            if (case.getValue("allowed").jsonPrimitive.content == "true") {
                assertEquals(setOf(peer), parseNotebookPeers(root.toString(), peer, file), name)
            } else {
                val error = assertThrows(IllegalArgumentException::class.java, {
                    parseNotebookPeers(root.toString(), peer, file)
                }, name)
                assertTrue(error.message.orEmpty().contains("--notebook-authority-dir"), name)
            }
        }
        assertEquals(null, parseNotebookPeers(null, null, root.resolve("grants.json").toString()))
    }
}
