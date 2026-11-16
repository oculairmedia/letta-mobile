package com.letta.mobile.architecture

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AndroidFileApiRepositoryArchitectureTest {
    @Test
    fun `Android shared sources avoid APIs newer than API 26`() {
        val root = File(System.getProperty("architecture.projectRoot"), "android-compose")
        val modules = listOf(File(root, "sharedLogic")) + File(root, "core").listFiles().orEmpty().filter { it.isDirectory }
        val forbidden = Regex(
            "(?:\\b(?:Files\\s*\\.\\s*(?:readString|writeString|mismatch)|Path\\s*\\.\\s*of)\\s*\\(" +
                "|\\.orElseThrow\\s*\\(\\s*\\))",
        )
        val findings = modules.flatMap { module ->
            listOf("jvmAndAndroid", "androidMain").flatMap { sourceSet ->
                File(module, "src/$sourceSet").walkTopDown().filter { it.isFile && it.extension == "kt" }
                    .flatMap { file ->
                        file.readLines().mapIndexedNotNull { index, line ->
                            if (forbidden.containsMatchIn(line)) "${file.relativeTo(root)}:${index + 1}" else null
                        }.asSequence()
                    }.toList()
            }
        }
        assertTrue(findings.isEmpty(), "Android-incompatible APIs: ${findings.joinToString()}")
    }
}
