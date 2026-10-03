import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// The Kotlin SPI a jvm-runtime canvas plugin implements (plan section 3.2, letta-mobile-s416w.26).
// Plugin authors compile against this module, so it stays small: no project dependencies, and only
// kotlinx-coroutines-core and kotlinx-serialization-json. Its DTOs are the LCP wire shapes, so
// :sharedLogic depends on it (KMP -> KMP) and carries the same targets.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    `maven-publish`
}

// The SPI version; its major is the manifest's `contract.version` (PluginApi.CONTRACT_VERSION).
// PluginApiStabilityTest holds this equal to PluginApi.VERSION.
group = "com.letta.mobile"
version = "1.0.0"

kotlin {
    explicitApi()

    android {
        namespace = "com.letta.mobile.plugin.api"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    val hostOs = System.getProperty("os.name")
    val hostArch = System.getProperty("os.arch")
    when {
        hostOs == "Mac OS X" && hostArch == "aarch64" -> macosArm64("hostNative")
        hostOs == "Mac OS X" -> macosX64("hostNative")
        hostOs.startsWith("Windows") -> mingwX64("hostNative")
        hostOs == "Linux" -> linuxX64("hostNative")
    }

    sourceSets {
        commonMain {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.serialization.json)
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// The committed public-API dump (api/plugin-api.api). Regenerate deliberately with
// ./gradlew :plugin-api:jvmTest -PpluginApi.updateDump=true and review the diff.
tasks.withType<Test>().configureEach {
    systemProperty("pluginApi.dump", layout.projectDirectory.file("api/plugin-api.api").asFile.absolutePath)
    systemProperty("pluginApi.version", project.version.toString())
    providers.gradleProperty("pluginApi.updateDump").orNull?.let { systemProperty("pluginApi.updateDump", it) }
}
