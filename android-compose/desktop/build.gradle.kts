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
// Kizitonwose Calendar (Compose Multiplatform) — backs the Schedules surface's
// Agenda date-strip (WeekCalendar) and History reliability grid
// (HeatMapCalendar). Uses kotlinx-datetime types, matching our shared
// schedule projection (Phase 7).
val calendarVersion = "2.10.1"
// Pet-window surface host (avatar PRD P4): embedded Chromium for the
// off-screen renderer + Win32 window styles (no-activate / click-through).
val jcefMavenVersion = "146.0.10"
val jnaVersion = "5.17.0"

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

java {
    // JVM 21: the desktop module transitively consumes sharedLogic's Iroh QUIC
    // transport binding (computer.iroh:iroh:1.0.0), which requires JVM 21+.
    // Desktop already needs JDK 25+ at runtime (Jewel UI, class-file v69), so
    // targeting 21 here is consistent with the runtime contract, not a regression.
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
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
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=org.jetbrains.jewel.foundation.ExperimentalJewelApi",
        )
    }
}

val mermaidNativeDir = layout.buildDirectory.dir("generated/mermaid-native")
val mermaidNativeLibraryName = when {
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "letta_mermaid_renderer.dll"
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "libletta_mermaid_renderer.dylib"
    else -> "libletta_mermaid_renderer.so"
}
val mermaidRendererDir = rootProject.layout.projectDirectory.dir("native/mermaid_renderer")
val buildDesktopMermaidNative by tasks.registering(Exec::class) {
    val manifest = mermaidRendererDir.file("Cargo.toml")
    inputs.files(
        manifest,
        mermaidRendererDir.file("Cargo.lock"),
        fileTree(mermaidRendererDir.dir("src")),
    )
    outputs.file(mermaidRendererDir.file("target/release/$mermaidNativeLibraryName"))
    commandLine(
        providers.environmentVariable("CARGO").orElse("cargo").get(),
        "build",
        "--release",
        "--locked",
        "--manifest-path",
        manifest.asFile.absolutePath,
    )
}
val stageDesktopMermaidNative by tasks.registering(Sync::class) {
    dependsOn(buildDesktopMermaidNative)
    from(mermaidRendererDir.file("target/release/$mermaidNativeLibraryName"))
    into(mermaidNativeDir)
}

sourceSets.main {
    resources.srcDir(mermaidNativeDir)
}

tasks.named("processResources") {
    dependsOn(stageDesktopMermaidNative)
}

dependencies {
    implementation(project(":sharedLogic"))
    // letta-mobile-cq2ju: Iroh QUIC transport for desktop. sharedLogic declares
    // computer.iroh:iroh as `implementation` (not `api`), so it is NOT exposed
    // transitively for desktop compilation — declare it directly here. The JAR
    // bundles host-OS native libs (linux/darwin/win, x86-64 + aarch64), so no
    // native packaging is needed.
    implementation("computer.iroh:iroh:1.0.0")
    // Avatar companion: renderer bridge + loopback web host (brings :avatar:core).
    implementation(project(":avatar:renderer-web"))
    // Avatar library: import pipeline + local catalog (license capture/display).
    implementation(project(":avatar:asset-pipeline"))

    implementation("io.github.vinceglb:filekit-core-jvm:0.14.1")
    implementation("io.github.vinceglb:filekit-dialogs-compose-jvm:0.14.1")
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.jewel:jewel-decorated-window:$jewelVersion")
    implementation("org.jetbrains.compose.material3:material3:$composeDesktopMaterial3Version")
    implementation("org.jetbrains.compose.material:material-icons-extended:$composeDesktopMaterialIconsVersion")
    implementation("org.jetbrains.skiko:skiko-awt:0.9.37.3")
    implementation("io.github.justdeko:kuiver:$kuiverVersion")
    implementation("sh.calvin.autolinktext:autolinktext:$autoLinkTextVersion")
    implementation("com.arjunjadeja:texty:$textyVersion")
    implementation("com.kizitonwose.calendar:compose-multiplatform:$calendarVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:$coroutinesVersion")
    implementation("me.friwi:jcefmaven:$jcefMavenVersion")
    implementation("net.java.dev.jna:jna-platform:$jnaVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-websockets:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.compose.ui:ui-test:1.10.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// P4 spike entry point (see avatar/DESIGN-BRIEF.md + docs/design/avatar-system-prd.md):
// frameless transparent pet window hosting the web avatar renderer off-screen.
tasks.register<JavaExec>("runPetSpike") {
    group = "application"
    description = "Runs the frameless pet-window spike (-PpetVrm=path\\to\\model.vrm to override the avatar)."
    mainClass.set("com.letta.mobile.desktop.avatar.pet.PetWindowSpikeKt")
    classpath = sourceSets.main.get().runtimeClasspath
    jvmArgs(
        // jcefmaven OSR-mode requirements.
        "--add-exports=java.base/java.lang=ALL-UNNAMED",
        "--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
        "--add-exports=java.desktop/sun.java2d=ALL-UNNAMED",
    )
    providers.gradleProperty("petVrm").orNull?.let { args(it) }
}

// RUNTIME NOTE: this module compiles to JVM 21 bytecode (required by the
// transitively-consumed Iroh transport binding, computer.iroh:iroh:1.0.0). The
// Jewel UI dependency ships class-file version 69 (Java 25), so running the app
// (`:desktop:run` or a packaged distribution) requires a JDK 25+ at runtime — an
// older JRE fails at startup with UnsupportedClassVersionError loading
// org.jetbrains.jewel.*. Compilation and unit tests run on JDK 21+.
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
