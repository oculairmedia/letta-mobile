plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.gitlab.arturbosch.detekt")
    id("org.jetbrains.kotlinx.kover")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/detekt.yml")
    parallel = true
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xcontext-parameters",
        )
    }

    android {
        namespace = "com.letta.mobile.sharedlogic"
        compileSdk = 36
        minSdk = 26

        withHostTestBuilder {}
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
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
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                api("org.jetbrains.kotlinx:atomicfu:0.32.1")
                api("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.0-beta01")
                api("org.jetbrains.compose.runtime:runtime:1.9.0")
                api("io.ktor:ktor-http:3.5.0")
                api("io.ktor:ktor-io:3.5.0")
                // Multiplatform HTTP client core for shared repository logic
                // (the engine is supplied per-platform). Lets HTTP admin
                // repositories live once in commonMain instead of being
                // duplicated per platform (letta-mobile-mqzkc).
                api("io.ktor:ktor-client-core:3.5.0")
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation("app.cash.turbine:turbine:1.2.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                // MockEngine drives the shared HTTP repositories' commonTest.
                implementation("io.ktor:ktor-client-mock:3.5.0")
                implementation("io.ktor:ktor-client-content-negotiation:3.5.0")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.0")
            }
        }
    }
}
