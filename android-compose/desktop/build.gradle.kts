import dev.nucleusframework.desktop.application.dsl.TargetFormat
import dev.nucleusframework.desktop.application.dsl.ReleaseChannel
import dev.nucleusframework.desktop.application.dsl.ReleaseType
import dev.nucleusframework.desktop.application.dsl.SigningAlgorithm
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
val nucleusVersion = "2.1.5"
val nativeTrayVersion = "2.0.1"

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
    id("dev.nucleusframework")
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
    implementation("dev.nucleusframework:nucleus.nucleus-application:$nucleusVersion")
    implementation("dev.nucleusframework:nucleus.updater-runtime:$nucleusVersion")
    implementation("dev.nucleusframework:nucleus.native-http:$nucleusVersion")
    implementation("dev.nucleusframework:nucleus.native-ssl:$nucleusVersion")
    implementation("dev.nucleusframework:nucleus.notification-common:$nucleusVersion")
    implementation("dev.nucleusframework:nucleus.system-info:$nucleusVersion")
    implementation("dev.nucleusframework:nucleus.darkmode-detector:$nucleusVersion")
    implementation("dev.nucleusframework:nucleus.system-color:$nucleusVersion")
    implementation("dev.nucleusframework:nucleus.taskbar-progress:$nucleusVersion")
    implementation("dev.nucleusframework:nucleus.autolaunch:$nucleusVersion")
    implementation("dev.nucleusframework:nucleus.launcher-windows:$nucleusVersion")
    implementation("dev.nucleusframework:nucleus.launcher-linux:$nucleusVersion")
    implementation("dev.nucleusframework:nucleus.launcher-macos:$nucleusVersion")
    implementation("dev.nucleusframework:nucleus.global-hotkey:$nucleusVersion")
    implementation("dev.nucleusframework:nucleus.energy-manager:$nucleusVersion")
    implementation("dev.nucleusframework:nucleus.media-control:$nucleusVersion")
    implementation("dev.nucleusframework:nucleus.linux-hidpi:$nucleusVersion")
    implementation("dev.nucleusframework:composenativetray-jvm:$nativeTrayVersion")
    // Letta Desktop embeds JCEF and uses Swing/AWT integration, so Nucleus must
    // use its portable JNI-backed AWT window backend rather than Tao.
    runtimeOnly("dev.nucleusframework:nucleus.decorated-window-jni:$nucleusVersion")
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
    testImplementation("org.jetbrains.compose.ui:ui-test:1.11.1")
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
nucleus.application {
    mainClass = "com.letta.mobile.desktop.MainKt"

    // Native Image is intentionally opt-in: the JVM distribution remains the
    // compatibility build for JCEF and Iroh, while release engineers can run
    // `-PnucleusGraalvm=true` to benchmark the native launcher.
    graalvm {
        isEnabled = providers.gradleProperty("nucleusGraalvm").orNull.toBoolean()
        imageName = "letta-desktop"
        javaLanguageVersion = 25
    }

    nativeDistributions {
        if (providers.gradleProperty("nucleusAllFormats").orNull.toBoolean()) {
            targetFormats(*TargetFormat.entries.toTypedArray())
        } else {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
        }
        packageName = "Letta Desktop"
        packageVersion = computeDesktopPackageVersion().get()
        description = "Desktop client foundation for the Letta AI platform."
        copyright = "Copyright (C) 2026 Letta"
        vendor = "Letta"
        artifactName = $$"${name}-${version}-${os}-${arch}.${ext}"
        protocol("Meridian", "meridian")

        publish {
            github {
                enabled = true
                owner = "oculairmedia"
                repo = "letta-mobile"
                channel = ReleaseChannel.Latest
                releaseType = ReleaseType.Release
            }
        }

        windows {
            // Preserve the existing installer identity and discoverability
            // while moving packaging from Compose Desktop to Nucleus.
            menuGroup = "Letta"
            menu = true
            shortcut = true
            perUserInstall = true
            upgradeUuid = "44e25263-67d4-443c-b85c-655a41118add"
            nsis {
                createDesktopShortcut = true
                createStartMenuShortcut = true
            }
            providers.environmentVariable("WINDOWS_SIGNING_CERTIFICATE").orNull?.let { certificate ->
                signing {
                    enabled = true
                    certificateFile.set(file(certificate))
                    certificatePassword = providers.environmentVariable("WINDOWS_SIGNING_PASSWORD").orNull
                    algorithm = SigningAlgorithm.Sha256
                    timestampServer = "http://timestamp.digicert.com"
                }
            }
        }

        macOS {
            bundleID = "com.letta.desktop"
            appCategory = "public.app-category.productivity"
            dockName = "Letta"
            providers.environmentVariable("APPLE_SIGNING_IDENTITY").orNull?.let { signingIdentity ->
                signing {
                    sign.set(true)
                    identity.set(signingIdentity)
                }
            }
            val appleId = providers.environmentVariable("APPLE_NOTARIZATION_ID").orNull
            val applePassword = providers.environmentVariable("APPLE_NOTARIZATION_PASSWORD").orNull
            val appleTeam = providers.environmentVariable("APPLE_TEAM_ID").orNull
            if (appleId != null && applePassword != null && appleTeam != null) {
                notarization {
                    this.appleID.set(appleId)
                    password.set(applePassword)
                    teamID.set(appleTeam)
                }
            }
        }

        linux {
            appCategory = "Utility"
            startupWMClass = "Letta Desktop"
        }
    }
}
