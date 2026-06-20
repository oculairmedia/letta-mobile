import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// This repo does not yet use a Gradle version catalog. Keep desktop-only
// versions named here until dependency versions are centralized project-wide.
val composeDesktopMaterial3Version = "1.9.0"
val composeDesktopMaterialIconsVersion = "1.7.3"
val coroutinesVersion = "1.11.0"
val ktorVersion = "3.5.0"
val jewelVersion = "0.37.0-262.4852.51"
val kuiverVersion = "0.3.0"
val autoLinkTextVersion = "2.0.2"
val textyVersion = "1.0.0-alpha"

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

fun computeDesktopPackageVersion() = providers.provider {
    providers.gradleProperty("versionNameOverride").orNull?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable("GITHUB_REF_NAME").orNull
            ?.takeIf { it.startsWith("v") }
            ?.removePrefix("v")
            ?.takeIf { Regex("""\d+\.\d+\.\d+.*""").matches(it) }
}
    .orElse(
        providers.exec {
            commandLine("git", "describe", "--tags", "--always", "--dirty", "--match", "v[0-9]*")
            workingDir = rootProject.projectDir.parentFile ?: rootProject.projectDir
            isIgnoreExitValue = true
        }.standardOutput.asText.map { output ->
            output.trim()
                .removePrefix("v")
                .substringBefore('-')
                .substringBefore('+')
                .takeIf { it.isNotBlank() && it.matches(Regex("""\d+\.\d+\.\d+""")) }
                ?: "0.0.0"
        },
    )

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=org.jetbrains.jewel.foundation.ExperimentalJewelApi",
            "-Xcontext-parameters",
        )
    }
}

dependencies {
    implementation(project(":sharedLogic"))

    implementation("io.github.vinceglb:filekit-core-jvm:0.14.1")
    implementation("io.github.vinceglb:filekit-dialogs-compose-jvm:0.14.1")
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.jewel:jewel-decorated-window:$jewelVersion")
    implementation("org.jetbrains.compose.material3:material3:$composeDesktopMaterial3Version")
    implementation("org.jetbrains.compose.material:material-icons-extended:$composeDesktopMaterialIconsVersion")
    implementation("io.github.justdeko:kuiver:$kuiverVersion")
    implementation("sh.calvin.autolinktext:autolinktext:$autoLinkTextVersion")
    implementation("com.arjunjadeja:texty:$textyVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:$coroutinesVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "com.letta.mobile.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "Letta Desktop"
            packageVersion = computeDesktopPackageVersion().get()
            description = "Desktop client foundation for the Letta AI platform."
            copyright = "Copyright (C) 2026 Letta"
            vendor = "Letta"

            windows {
                // Create a Start Menu entry ("Letta" group) and a desktop
                // shortcut so the app is discoverable after install instead of
                // only living under AppData/Program Files.
                menuGroup = "Letta"
                menu = true
                shortcut = true
                // Per-user install: no admin/UAC prompt required.
                perUserInstall = true
                // Stable GUID so MSI installs UPGRADE in place across versions
                // instead of stacking side-by-side. Must never change.
                upgradeUuid = "44e25263-67d4-443c-b85c-655a41118add"
            }
        }
    }
}
