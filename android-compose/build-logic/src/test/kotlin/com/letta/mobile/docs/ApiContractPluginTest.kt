package com.letta.mobile.docs

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertTrue

class ApiContractPluginTest {
    @TempDir
    lateinit var projectDir: File

    @Test
    fun `API check rejects signature drift until dump is explicitly reviewed`() {
        projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"api-contract-fixture\"")
        projectDir.resolve("build.gradle.kts").writeText("""
            plugins {
                id("org.jetbrains.kotlin.jvm")
                id("com.letta.mobile.api-documentation")
            }
            repositories { mavenCentral() }
        """.trimIndent())
        val source = projectDir.resolve("src/main/kotlin/example/Contract.kt")
        source.parentFile.mkdirs()
        source.writeText("package example; class Contract { fun value(): Int = 1 }")
        runner("apiDump").build()
        runner("apiCheck").build()
        source.writeText("package example; class Contract { fun value(): String = \"one\" }")
        val failure = runner("apiCheck").buildAndFail()
        assertTrue(failure.output.contains("ABI", ignoreCase = true))
        runner("apiDump").build()
        runner("apiCheck").build()
    }

    @Test
    fun `Android-only signature drift is checked with AGP KMP target`() {
        projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"android-api-contract-fixture\"")
        projectDir.resolve("build.gradle.kts").writeText("""
            plugins {
                id("org.jetbrains.kotlin.multiplatform")
                id("com.android.kotlin.multiplatform.library")
                id("com.letta.mobile.api-documentation")
            }
            repositories { google(); mavenCentral() }
            kotlin {
                jvm()
                android {
                    namespace = "example.contract"
                    compileSdk = 37
                    minSdk = 26
                }
            }
        """.trimIndent())
        val common = projectDir.resolve("src/commonMain/kotlin/example/Common.kt")
        common.parentFile.mkdirs()
        common.writeText("package example; class Common")
        val android = projectDir.resolve("src/androidMain/kotlin/example/AndroidOnly.kt")
        android.parentFile.mkdirs()
        android.writeText("package example; class AndroidOnly { fun value(): Int = 1 }")
        runner("apiDump").build()
        runner("apiCheck").build()
        val snapshot = projectDir.resolve("api-android/android.api").readText()
        assertTrue(snapshot.contains("AndroidOnly"))
        android.writeText("package example; class AndroidOnly { fun value(): String = \"one\" }")
        val failure = runner("apiCheck").buildAndFail()
        assertTrue(failure.output.contains("Android API changed"))
        runner("apiDump").build()
        runner("apiCheck").build()
    }

    @Test
    fun `host native contract updates independently and rejects native drift`() {
        projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"native-api-contract-fixture\"")
        projectDir.resolve("build.gradle.kts").writeText("""
            plugins {
                id("org.jetbrains.kotlin.multiplatform")
                id("com.letta.mobile.api-documentation")
            }
            repositories { mavenCentral() }
            kotlin {
                when {
                    System.getProperty("os.name").startsWith("Windows") -> mingwX64("hostNative")
                    System.getProperty("os.name") == "Mac OS X" && System.getProperty("os.arch") == "aarch64" -> macosArm64("hostNative")
                    System.getProperty("os.name") == "Mac OS X" -> macosX64("hostNative")
                    else -> linuxX64("hostNative")
                }
            }
        """.trimIndent())
        val source = projectDir.resolve("src/hostNativeMain/kotlin/example/NativeOnly.kt")
        source.parentFile.mkdirs()
        source.writeText("package example; class NativeOnly { fun value(): Int = 1 }")
        val otherHost = projectDir.resolve("api/other-host/reviewed.api")
        otherHost.parentFile.mkdirs()
        otherHost.writeText("Other host evidence must remain unchanged")
        runner("apiDump").build()
        val snapshots = projectDir.resolve("api").walkTopDown().filter { it.name.endsWith(".klib.api") }.toList()
        assertTrue(snapshots.size == 1)
        assertTrue(snapshots.single().parentFile.name != "api")
        assertTrue(snapshots.single().readText().contains("NativeOnly"))
        runner("apiCheck").build()
        source.writeText("package example; class NativeOnly { fun value(): String = \"one\" }")
        assertTrue(runner("apiCheck").buildAndFail().output.contains("ABI", ignoreCase = true))
        runner("apiDump").build()
        runner("apiCheck").build()
        assertTrue(otherHost.readText() == "Other host evidence must remain unchanged")
    }

    private fun runner(task: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(task, "--max-workers=1", "-Dorg.gradle.daemon=false", "-Pkotlin.compiler.execution.strategy=in-process")
}
