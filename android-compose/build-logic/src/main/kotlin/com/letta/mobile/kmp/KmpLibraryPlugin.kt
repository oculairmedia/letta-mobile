package com.letta.mobile.kmp

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

/**
 * `com.letta.mobile.kmp.library` (letta-mobile-by4vm.70): the target defaults every Kotlin
 * Multiplatform library module used to copy-paste. Apply it next to
 * `org.jetbrains.kotlin.multiplatform` (and `com.android.kotlin.multiplatform.library` when the
 * module has an Android target); modules then declare only what differs (namespace, extra targets,
 * source sets).
 *
 * - every `jvm()` target compiles to JVM 17;
 * - the KMP `android {}` target gets `compileSdk`/`minSdk` from the version catalog and JVM 17.
 *
 * desktop / appserver-cli / iroh-wrapper-cli stay on JVM 21 (computer.iroh:iroh needs 21+) and do
 * not apply this plugin. The host-native test target is opt-in per module via [hostNative].
 */
class KmpLibraryPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
            kotlin.targets.withType(KotlinJvmTarget::class.java).configureEach {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            }
        }
        project.pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
            val compileSdk = project.catalogInt("compileSdk")
            val minSdk = project.catalogInt("minSdk")
            val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
            kotlin.targets.withType(KotlinMultiplatformAndroidLibraryTarget::class.java).configureEach {
                this.compileSdk = compileSdk
                this.minSdk = minSdk
                compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }
}

private fun Project.catalogInt(name: String): Int =
    extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        .findVersion(name)
        .orElseThrow { IllegalStateException("Missing catalog version $name") }
        .requiredVersion
        .toInt()

/**
 * Declares the Kotlin/Native target for the build host as `hostNative` (macOS arm64/x64, Windows
 * x64 or Linux x64), so `hostNativeTest` runs the common tests natively wherever the build runs.
 * Unknown hosts get no native target.
 */
fun KotlinMultiplatformExtension.hostNative() {
    val hostOs = System.getProperty("os.name")
    val hostArch = System.getProperty("os.arch")
    when {
        hostOs == "Mac OS X" && hostArch == "aarch64" -> macosArm64("hostNative")
        hostOs == "Mac OS X" -> macosX64("hostNative")
        hostOs.startsWith("Windows") -> mingwX64("hostNative")
        hostOs == "Linux" -> linuxX64("hostNative")
    }
}
