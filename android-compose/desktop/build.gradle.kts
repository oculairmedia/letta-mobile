import dev.nucleusframework.desktop.application.dsl.TargetFormat
import dev.nucleusframework.desktop.application.dsl.ReleaseChannel
import dev.nucleusframework.desktop.application.dsl.ReleaseType
import dev.nucleusframework.desktop.application.dsl.SigningAlgorithm
import org.gradle.jvm.toolchain.JavaInstallationMetadata
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// WiX 4 still resolves installer sources through Win32 paths. Keep CI's
// packaging workspace short so deeply nested production dependencies remain
// below MAX_PATH; ordinary local builds retain the standard module build dir.
providers.environmentVariable("LETTA_DESKTOP_BUILD_DIR").orNull
    ?.takeIf(String::isNotBlank)
    ?.let { layout.buildDirectory.set(file(it)) }

// Desktop-only library versions stay named here until the project catalog
// grows beyond the Android SDK constants in gradle/libs.versions.toml.
//
// WARNING — the Compose versions below are FLOORS, not the versions that ship.
// The runtime classpath resolves the whole `org.jetbrains.compose` atomic group
// to 1.11.1 by conflict resolution across eight requested versions (1.11.1,
// 1.10.3, 1.10.0, 1.9.3, 1.9.1, 1.9.0, 1.7.0, 1.7.0-beta01). The 1.11.1 comes
// transitively from `dev.nucleusframework:composenativetray-jvm`, so a Nucleus
// bump silently moves the entire Compose runtime under us — that is exactly how
// Jewel's LocalTextContextMenu ABI broke (see the bridge in DesktopJewelTheme).
// Verify with:
//   ./gradlew :desktop:dependencyInsight --configuration runtimeClasspath \
//     --dependency org.jetbrains.compose.runtime:runtime
// Pinning the group deliberately is worth doing, but it must be validated
// against Jewel AND Nucleus together (both are compiled against different
// Compose baselines) — do it as its own change, not as a drive-by bump.
val composeDesktopMaterial3Version = "1.9.0"
val composeDesktopMaterialIconsVersion = "1.7.3"
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
val desktopNodeVersion = "24.13.1"
val desktopLettaCodeVersion = "0.29.12"
val desktopNodeArchiveName = "node-v$desktopNodeVersion-win-x64.zip"
val desktopNodeArchiveSha256 = "fba577c4bb87df04d54dd87bbdaa5a2272f1f99a2acbf9152e1a91b8b5f0b279"
// Desktop packages bundle JetBrains Runtime 25.0.4 (JBR) rather than Temurin.
// JBR is the JetBrains-maintained OpenJDK 25 build that ships the
// AWT/InputMethod bridge Compose Multiplatform uses to surface the OS
// touch-keyboard on text input — Temurin's InputMethod bridge resolves to a
// no-op for non-Swing text components, so the keyboard never pops on touch
// devices. The bundled JCEF runtime used by the avatar/pet window is fetched
// separately via jcefmaven, so we use the vanilla `jbrsdk` (not `jbrsdk_jcef`).
// SHA-512 is published by JetBrains alongside the artifact.
val jbrVersion = "25.0.4"
val jbrBuild = "b508.27"
val jbrPlatformSegment = "windows-x64"
val jbrArchiveName = "jbrsdk-${jbrVersion}-${jbrPlatformSegment}-${jbrBuild}.zip"
val jbrArchiveSha512 = "22e09469aaef1190d4320e0621ea35f7e944a872c38b7c485f817672cbe25461ffb82db151e93d413c1889cf42bc486041e54e10c30a2601aa8170ef19e4ed98"
val jbrExtractDirName = "jbrsdk-${jbrVersion}-${jbrPlatformSegment}-${jbrBuild}"

// Used by the JBR download below and by the existing Node runtime download
// further down — declared here so both task blocks can read it.
val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
    id("dev.nucleusframework")
    // Kotzilla observability — JVM (Desktop) target. The plugin's
    // generated `io.kotzilla.generated.initKotzillaConfig()` is called
    // from Desktop's `main()` via the shared `startKotzillaMonitoring()`
    // wrapper in sharedLogic/jvmMain. Per-platform wiring because
    // sharedLogic is a KMP module and the plugin generates code into
    // commonMain that pulls in JVM/Android-only SDK classes — applying
    // it there breaks Kotlin/Native test targets.
    id("io.kotzilla.kotzilla-plugin")
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
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
val buildDesktopMermaidNative = tasks.register<Exec>("buildDesktopMermaidNative") {
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
val stageDesktopMermaidNative = tasks.register<Sync>("stageDesktopMermaidNative") {
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
    implementation(project(":sharedUI"))
    // letta-mobile-cq2ju: Iroh QUIC transport for desktop. sharedLogic declares
    // computer.iroh:iroh as `implementation` (not `api`), so it is NOT exposed
    // transitively for desktop compilation — declare it directly here. The JAR
    // bundles host-OS native libs (linux/darwin/win, x86-64 + aarch64), so no
    // native packaging is needed.
    implementation(libs.iroh)
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
    // The common NotificationManager delegates to the matching per-OS bridge,
    // so every desktop OS backend must be on the runtime classpath.
    implementation("dev.nucleusframework:nucleus.notification-windows:$nucleusVersion")
    implementation("dev.nucleusframework:nucleus.notification-macos:$nucleusVersion")
    implementation("dev.nucleusframework:nucleus.notification-linux:$nucleusVersion")
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
    //
    // letta-mobile-scedm: `DecoratedWindow`/`TitleBar` (core+awt, compile-time
    // API) plus the JNI native backend (runtime-only — installs a real Win32
    // WndProc subclass on Windows) replace the hand-rolled
    // `undecorated = true` Window + DwmSetWindowAttribute-only chrome. That
    // combination lost Aero Snap, Snap Layouts, DWM min/max/restore
    // animations, and the standard drop shadow because the OS never saw a
    // real WS_CAPTION/WS_THICKFRAME frame. Nucleus's JNI backend keeps a real
    // native frame under custom-drawn chrome instead — the maintained
    // alternative to subclassing GWLP_WNDPROC ourselves via JNA.
    implementation("dev.nucleusframework:nucleus.decorated-window-core:$nucleusVersion")
    implementation("dev.nucleusframework:nucleus.decorated-window-awt:$nucleusVersion")
    // `DecoratedWindow`/`TitleBar` themselves (the public entry points we call
    // from DesktopJewelWindow.kt) are published from this module, not -core —
    // it must be a compile-time dependency, not runtimeOnly.
    implementation("dev.nucleusframework:nucleus.decorated-window-jni:$nucleusVersion")
    implementation("org.jetbrains.jewel:jewel-decorated-window:$jewelVersion")
    implementation("org.jetbrains.compose.material3:material3:$composeDesktopMaterial3Version")
    implementation("org.jetbrains.compose.material:material-icons-extended:$composeDesktopMaterialIconsVersion")
    implementation("org.jetbrains.skiko:skiko-awt:0.9.37.3")
    implementation("io.github.justdeko:kuiver:$kuiverVersion")
    implementation("sh.calvin.autolinktext:autolinktext:$autoLinkTextVersion")
    // Conversation tab strip drag-to-reorder (letta-mobile#1258): same
    // library the mobile dashboard already uses for its pinned-items grid
    // (see app/build.gradle.kts and HomeScreenWidgets.kt's
    // ReorderablePinnedItemsGrid) -- Kotlin Multiplatform, resolves to the
    // JVM/desktop artifact here via Gradle module metadata.
    implementation("sh.calvin.reorderable:reorderable:3.1.0")
    implementation("com.arjunjadeja:texty:$textyVersion")
    implementation("com.kizitonwose.calendar:compose-multiplatform:$calendarVersion")
    implementation(libs.kotlinx.coroutines.swing)
    implementation("me.friwi:jcefmaven:$jcefMavenVersion")
    implementation("net.java.dev.jna:jna-platform:$jnaVersion")
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    // Kotzilla SDK — JVM Compose variant for Desktop's instrumentation.
    // The wrapper in sharedLogic/jvmMain calls initKotzillaConfig() from
    // the generated `io.kotzilla.generated` package (reflective lookup so
    // the wrapper still compiles when the plugin isn't applied, e.g.
    // CI without a developer's local kotzilla.json).
    implementation("io.kotzilla:kotzilla-sdk-compose-jvm:2.3.3")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.compose.ui:ui-test:1.11.1")
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
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

// Realtime lookdev for the ambient agent-status shader: live-editable SkSL,
// uniform sliders, and production AmbientMotion presets over a fake chat
// column — same Skia pipeline as the app, so what you tune is what ships.
tasks.register<JavaExec>("runShaderLookdev") {
    group = "application"
    description = "Runs the ambient-shader lookdev window (live SkSL editing + uniform sliders)."
    mainClass.set("com.letta.mobile.desktop.lookdev.ShaderLookdevMainKt")
    classpath = sourceSets.main.get().runtimeClasspath
    // Variant carousel folder: *.sksl files here are polled live by the window.
    systemProperty("lookdev.shaderDir", layout.projectDirectory.dir("lookdev-shaders").asFile.absolutePath)
}

/**
 * Lowest JDK that can RUN this app: Jewel ships class-file v69 (Java 25).
 * Compilation still targets JVM 21 for the Iroh binding, so this is
 * deliberately not the compile toolchain.
 */
val minimumRuntimeJdk = 25

// Layout for the JBR install that jpackage uses to build the runtime image.
// The downloader mirrors the existing `downloadDesktopNodeRuntime` pattern:
// idempotent PowerShell + SHA-512 verify + atomic rename. The extract task
// unzips into `desktop-jbr-runtime/<jbrExtractDirName>/` so that
// `packagingJavaHome` resolves to a fully-formed JDK with bin/, conf/, lib/.
val desktopJbrArchive = layout.buildDirectory.file("downloads/$jbrArchiveName")
val desktopJbrRoot = layout.buildDirectory.dir("desktop-jbr-runtime")
val desktopJbrHome: Provider<Directory> = desktopJbrRoot.map { it.dir(jbrExtractDirName) }

val desktopDownloadScript = layout.projectDirectory.file("scripts/download-verified.ps1")
val desktopExtractScript = layout.projectDirectory.file("scripts/extract-zip.ps1")
val desktopNpmCiScript = layout.projectDirectory.file("scripts/npm-ci.ps1")
val desktopStageRuntimeScript = layout.projectDirectory.file("scripts/stage-letta-code-runtime.ps1")

val downloadDesktopJbr = tasks.register<Exec>("downloadDesktopJbr") {
    enabled = isWindowsHost
    inputs.property("jbrVersion", jbrVersion)
    inputs.property("jbrBuild", jbrBuild)
    inputs.property("sha512", jbrArchiveSha512)
    inputs.file(desktopDownloadScript)
    outputs.file(desktopJbrArchive)
    // All args are config-time strings so Exec stays configuration-cache safe.
    commandLine(
        "powershell.exe",
        "-NoProfile",
        "-NonInteractive",
        "-File",
        desktopDownloadScript.asFile.absolutePath,
        "-Url",
        "https://cache-redirector.jetbrains.com/intellij-jbr/$jbrArchiveName",
        "-OutFile",
        desktopJbrArchive.get().asFile.absolutePath,
        "-Algorithm",
        "SHA512",
        "-Expected",
        jbrArchiveSha512,
    )
}

val extractDesktopJbr = tasks.register<Exec>("extractDesktopJbr") {
    enabled = isWindowsHost
    dependsOn(downloadDesktopJbr)
    inputs.file(desktopExtractScript)
    inputs.file(desktopJbrArchive)
    outputs.dir(desktopJbrHome)
    commandLine(
        "powershell.exe",
        "-NoProfile",
        "-NonInteractive",
        "-File",
        desktopExtractScript.asFile.absolutePath,
        "-Archive",
        desktopJbrArchive.get().asFile.absolutePath,
        "-ExtractTo",
        desktopJbrRoot.get().asFile.absolutePath,
        "-Target",
        desktopJbrHome.get().asFile.absolutePath,
    )
}

/**
 * Path to the JBR that jpackage uses to build the runtime image.
 *
 * Nucleus's `javaHome` is a plain String (not a Property/Provider), so we
 * resolve the path at configuration time. That is safe: `layout.buildDirectory`
 * yields an absolute path even before `extractDesktopJbr` creates the dir.
 * The earlier regression was an *existence* check that returned null and
 * skipped `javaHome=` entirely — never the path computation itself.
 * Packaging tasks still `dependsOn(extractDesktopJbr)` so the dir exists
 * before jpackage runs.
 */
val packagingJavaHome: String = desktopJbrHome.get().asFile.absolutePath

// RUNTIME NOTE: this module compiles to JVM 21 bytecode (required by the
// transitively-consumed Iroh transport binding, computer.iroh:iroh:1.0.0). The
// Jewel UI dependency ships class-file version 69 (Java 25), so running the app
// (`:desktop:run` or a packaged distribution) requires a JDK 25+ at runtime — an
// older JRE fails at startup with UnsupportedClassVersionError loading
// org.jetbrains.jewel.*. Compilation and unit tests run on JDK 21+.
nucleus.application {
    mainClass = "com.letta.mobile.desktop.MainKt"

    // jpackage builds its bundled runtime image from the JDK specified
    // here, not from JAVA_HOME. Always point at the JBR path (do not gate
    // on directory existence at config time). The previous
    // Temurin/JDK-26 toolchain produced a JVM 21 image that couldn't load
    // Jewel's Java-25 classes; JBR 25.0.4 satisfies the class-file v69
    // minimum and additionally carries the AWT input bridge Compose
    // Multiplatform requires for touch IME on text input (see
    // letta-mobile-jbr for the touch-keyboard investigation; the JBR
    // TLDR is that Temurin's InputMethod bridge is a no-op for non-Swing
    // text components, so the touch keyboard never pops).
    javaHome = packagingJavaHome

    // Windows touch input (see desktop/.../touch/DesktopWindowsTouchInput.kt).
    // AWT translates WM_TOUCH into ordinary MouseEvents and keeps the only
    // "this came from a finger" flag behind sun.awt.AWTAccessor, which is not
    // an exported package. Without this the shim degrades to a no-op (logged
    // once) and touch drag-to-scroll plus the touch keyboard stay dead.
    //
    // sun.awt.windows is a second, separate package (not covered by the
    // sun.awt open above): DesktopWindowsTouchKeyboard reflects onto
    // WToolkit.showTouchKeyboard/hideTouchKeyboard to raise the touch
    // keyboard, since the COM ITipInvocation route is dead on Windows 11
    // (see that file's KDoc for the measured facts). Without this open,
    // DesktopJdkTouchKeyboardAccessor.bindOrNull() returns null and the
    // keyboard falls back to the legacy COM/TabTip path, which does not work
    // on Windows 11 either.
    //
    // Nucleus feeds `jvmArgs` to BOTH the `run` task and jpackage's
    // --java-options, so dev runs and installed builds stay in sync; verify a
    // packaged build with:
    //   findstr add-opens "<install dir>\app\Letta Desktop.cfg"
    jvmArgs(
        "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.awt.windows=ALL-UNNAMED",
    )

    // Native Image is intentionally opt-in: the JVM distribution remains the
    // compatibility build for JCEF and Iroh, while release engineers can run
    // `-PnucleusGraalvm=true` to benchmark the native launcher.
    graalvm {
        isEnabled = providers.gradleProperty("nucleusGraalvm").orNull.toBoolean()
        imageName = "letta-desktop"
        javaLanguageVersion = 25
    }

    nativeDistributions {
        appResourcesRootDir.set(project.layout.buildDirectory.dir("generated/desktop-app-resources"))
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

val desktopRuntimeSourceDir = layout.projectDirectory.dir("runtime")
val desktopNodeArchive = layout.buildDirectory.file("downloads/$desktopNodeArchiveName")
val desktopNodeRuntimeRoot = layout.buildDirectory.dir("desktop-node-runtime")
val desktopNodeExtractDir = desktopNodeRuntimeRoot.map { it.dir("node-v$desktopNodeVersion-win-x64") }
val desktopRuntimeInstallDir = layout.buildDirectory.dir("desktop-letta-code-runtime")
val desktopAppResourcesDir = layout.buildDirectory.dir("generated/desktop-app-resources")
val desktopBundledRuntimeDir = desktopAppResourcesDir.map { it.dir("windows/letta-code-runtime") }

val downloadDesktopNodeRuntime = tasks.register<Exec>("downloadDesktopNodeRuntime") {
    enabled = isWindowsHost
    inputs.property("nodeVersion", desktopNodeVersion)
    inputs.property("sha256", desktopNodeArchiveSha256)
    inputs.file(desktopDownloadScript)
    outputs.file(desktopNodeArchive)
    commandLine(
        "powershell.exe",
        "-NoProfile",
        "-NonInteractive",
        "-File",
        desktopDownloadScript.asFile.absolutePath,
        "-Url",
        "https://nodejs.org/dist/v$desktopNodeVersion/$desktopNodeArchiveName",
        "-OutFile",
        desktopNodeArchive.get().asFile.absolutePath,
        "-Algorithm",
        "SHA256",
        "-Expected",
        desktopNodeArchiveSha256,
    )
}

val extractDesktopNodeRuntime = tasks.register<Exec>("extractDesktopNodeRuntime") {
    enabled = isWindowsHost
    dependsOn(downloadDesktopNodeRuntime)
    inputs.file(desktopExtractScript)
    inputs.file(desktopNodeArchive)
    outputs.dir(desktopNodeExtractDir)
    commandLine(
        "powershell.exe",
        "-NoProfile",
        "-NonInteractive",
        "-File",
        desktopExtractScript.asFile.absolutePath,
        "-Archive",
        desktopNodeArchive.get().asFile.absolutePath,
        "-ExtractTo",
        desktopNodeRuntimeRoot.get().asFile.absolutePath,
        "-Target",
        desktopNodeExtractDir.get().asFile.absolutePath,
    )
}

val prepareDesktopRuntimeInstallDir = tasks.register<Copy>("prepareDesktopRuntimeInstallDir") {
    from(desktopRuntimeSourceDir)
    include("package.json", "package-lock.json", "runtime-manifest.json")
    into(desktopRuntimeInstallDir)
}

val installDesktopLettaCodeRuntime = tasks.register<Exec>("installDesktopLettaCodeRuntime") {
    enabled = isWindowsHost
    dependsOn(extractDesktopNodeRuntime)
    dependsOn(prepareDesktopRuntimeInstallDir)
    inputs.files(
        desktopRuntimeSourceDir.file("package.json"),
        desktopRuntimeSourceDir.file("package-lock.json"),
    )
    inputs.file(desktopNpmCiScript)
    inputs.property("lettaCodeVersion", desktopLettaCodeVersion)
    outputs.dir(desktopRuntimeInstallDir.map { it.dir("node_modules") })
    commandLine(
        "powershell.exe",
        "-NoProfile",
        "-NonInteractive",
        "-File",
        desktopNpmCiScript.asFile.absolutePath,
        "-NpmCmd",
        desktopNodeExtractDir.get().file("npm.cmd").asFile.absolutePath,
        "-WorkDir",
        desktopRuntimeInstallDir.get().asFile.absolutePath,
    )
}

val prepareDesktopLettaCodeRuntime = tasks.register<Exec>("prepareDesktopLettaCodeRuntime") {
    enabled = isWindowsHost
    dependsOn(installDesktopLettaCodeRuntime)
    inputs.file(desktopStageRuntimeScript)
    inputs.file(desktopNodeExtractDir.map { it.file("node.exe") })
    inputs.dir(desktopRuntimeInstallDir)
    outputs.dir(desktopBundledRuntimeDir)
    commandLine(
        "powershell.exe",
        "-NoProfile",
        "-NonInteractive",
        "-File",
        desktopStageRuntimeScript.asFile.absolutePath,
        "-NodeExe",
        desktopNodeExtractDir.get().file("node.exe").asFile.absolutePath,
        "-InstallDir",
        desktopRuntimeInstallDir.get().asFile.absolutePath,
        "-DestDir",
        desktopBundledRuntimeDir.get().asFile.absolutePath,
    )
}

tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(prepareDesktopLettaCodeRuntime)
}

tasks.matching { it.name == "checkRuntime" || it.name == "checkReleaseRuntime" }.configureEach {
    dependsOn(extractDesktopJbr)
}

/**
 * Verify the JBR we're about to package IS actually a JetBrains Runtime.
 * Runs against the source JBR (extractDesktopJbr's output) before jpackage
 * runs, because jpackage strips IMPLEMENTOR/JAVA_VENDOR from the bundled
 * runtime/release — there is no reliable post-package signal that the
 * runtime is JBR vs stock OpenJDK. Treating this as a `doLast` rather
 * than a Gradle config-time check means extractDesktopJbr has already
 * populated the directory by the time we read it.
 *
 * The vendor guarantee matters: a future toolchain change could silently
 * swap the source back to Temurin and re-break touch IME on every install
 * (Temurin's InputMethod bridge is a no-op for non-Swing text components,
 * so the keyboard never pops on Compose Multiplatform text input).
 */
tasks.matching {
    it.name.startsWith("createDistributable") ||
    it.name.startsWith("createReleaseDistributable") ||
    it.name.startsWith("packageDistributionForCurrentOS")
}.configureEach {
    dependsOn(extractDesktopJbr)
    doLast {
        if (!isWindowsHost) return@doLast
        val release = File(desktopJbrHome.get().asFile, "release")
        require(release.isFile) { "Missing JBR release file at $release — extractDesktopJbr did not produce one." }
        val props = release.readLines().associate { line ->
            val key = line.substringBefore('=', missingDelimiterValue = "").trim()
            val value = line.substringAfter('=', missingDelimiterValue = "").trim().trim('"')
            key to value
        }
        val implementor = props["IMPLEMENTOR"].orEmpty()
        val vendor = props["JAVA_VENDOR"].orEmpty()
        val isJbr = implementor.contains("JetBrains", ignoreCase = true) ||
            vendor.contains("JetBrains", ignoreCase = true)
        check(isJbr) {
            "Source JBR vendor is \"$implementor\" / \"$vendor\" but must be a JetBrains Runtime build. " +
                "Compose Multiplatform's AWT input bridge requires JBR for touch IME; a " +
                "non-JBR source will produce a non-functional touch keyboard in the installer. " +
                "Check the [jbrArchiveName] and [jbrArchiveSha512] constants — they pin to a " +
                "specific JBR release and rejecting repointing to a different runtime."
        }
        logger.lifecycle("verifyJbrSource: bundled runtime is JBR ($implementor / $vendor).")
    }
}

/**
 * Fails the build when a packaged distribution bundles a runtime too old to
 * load the app's own classes. `:desktop:run` overrides its launcher and so
 * never exercised the packaged runtime — the JVM-21 image shipped in
 * v0.17.1 was only discovered by installing it. jpackage writes the
 * image's version metadata into `runtime/release` even though it strips
 * the more identifying fields, so a version check is still reliable.
 *
 * Note: this check intentionally does NOT verify JetBrains vendor — see
 * the `verifyJbrSource` doLast above. That guard runs against the
 * pre-package JBR source so the vendor can still be confirmed before
 * jpackage strips it.
 */
fun verifyBundledRuntime(distributableDir: File) {
    val release = distributableDir.walkTopDown()
        .firstOrNull { it.name == "release" && it.parentFile?.name == "runtime" }
        ?: error("No runtime/release under $distributableDir — cannot verify the bundled JVM.")
    val props = release.readLines().associate { line ->
        val key = line.substringBefore('=', missingDelimiterValue = "").trim()
        val value = line.substringAfter('=', missingDelimiterValue = "").trim().trim('"')
        key to value
    }
    val version = props["JAVA_VERSION"].orEmpty()
    require(version.isNotBlank()) { "No JAVA_VERSION in $release — cannot verify the bundled JVM." }
    val feature = version.substringBefore('.').toIntOrNull()
        ?: error("Unparseable JAVA_VERSION \"$version\" in $release.")
    check(feature >= minimumRuntimeJdk) {
        "Bundled runtime is Java $version, but the app needs $minimumRuntimeJdk+ to load its own " +
            "dependencies (Jewel ships class-file v69). The installer would fail at startup with " +
            "\"Failed to launch JVM\". Re-run downloadDesktopJbr and repackage."
    }
    logger.lifecycle("verifyBundledRuntime: bundled runtime is Java $version (>= $minimumRuntimeJdk required).")
}

tasks.matching { it.name.startsWith("createDistributable") || it.name.startsWith("createReleaseDistributable") }
    .configureEach {
        doLast {
            outputs.files.files.filter { it.isDirectory }.forEach(::verifyBundledRuntime)
        }
    }

// Jewel ships Java-25 bytecode (class-file v69), while this module targets
// JVM 21 for the Iroh binding. Gradle otherwise selects the compile toolchain
// for `run`, which fails before the window is created. Run the dev launcher
// against the extracted JBR so dev iteration matches the packaged runtime
// (in particular, JBR's AWT input bridge is the only thing that produces a
// working touch keyboard on Compose Multiplatform).
afterEvaluate {
    tasks.named<JavaExec>("run") {
        if (isWindowsHost) {
            dependsOn(extractDesktopJbr)
            val jbrJava = desktopJbrHome.map { it.file("bin/java.exe") }
            javaLauncher.set(provider {
                val executable = jbrJava.get()
                object : JavaLauncher {
                    override fun getExecutablePath() = executable
                    override fun getMetadata() = object : JavaInstallationMetadata {
                        override fun getLanguageVersion() = JavaLanguageVersion.of(minimumRuntimeJdk)
                        override fun getJavaRuntimeVersion() = jbrVersion
                        override fun getJvmVersion() = jbrVersion
                        override fun getVendor() = "JetBrains"
                        override fun getInstallationPath() = desktopJbrHome.get()
                        override fun isCurrentJvm() = false
                    }
                }
            })
            doFirst {
                val resolved = jbrJava.get().asFile
                check(resolved.isFile) { "JBR java missing at $resolved — extractDesktopJbr did not produce it." }
            }
        }
    }
}

// Packaging tasks depend on extractDesktopJbr via the verifyJbrSource
// configureEach block above (the doLast needs the directory to exist on
// disk to read its release file). All packaging entry points are covered
// by that match — no separate dependency wiring here.
