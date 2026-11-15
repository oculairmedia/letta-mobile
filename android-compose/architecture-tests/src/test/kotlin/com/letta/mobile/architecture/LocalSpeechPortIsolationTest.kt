package com.letta.mobile.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Local listening is generic: the `LocalSpeechRecognizer` port and the one `LocalVoiceEar` over it
 * live in sharedLogic's common code and name no platform type, so every client (Android, the web,
 * the desktop) supplies only its binding of the port and inherits the behaviour unchanged.
 */
class LocalSpeechPortIsolationTest {
    private val projectRoot: Path = Path.of(requireNotNull(System.getProperty("architecture.projectRoot")))
    private val localPackage = projectRoot.resolve("android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/voice/local")

    @Test
    fun `the local speech port and its ear import no platform types`() {
        val files = Files.list(localPackage).use { listing -> listing.filter { it.name.endsWith(".kt") }.toList() }
        val names = files.map { it.name }.toSet()
        check(setOf("LocalSpeechRecognizer.kt", "LocalVoiceEar.kt").all { it in names }) {
            "the port and the ear must stay in $localPackage, found $names"
        }
        val offending = files.flatMap { file ->
            IMPORT.findAll(file.readText()).map { it.groupValues[1] }
                .filterNot { imported -> ALLOWED.any { imported.startsWith(it) } }
                .map { "${file.name}: $it" }
                .toList()
        }
        check(offending.isEmpty()) { "local listening must stay platform-neutral; these imports are not: $offending" }
    }

    private companion object {
        val IMPORT = Regex("""(?m)^import\s+([\w.]+)""")
        val ALLOWED = listOf("com.letta.mobile.data.", "kotlin.", "kotlinx.coroutines.", "kotlinx.atomicfu.")
    }
}
