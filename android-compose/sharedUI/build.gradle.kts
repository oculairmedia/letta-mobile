import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.gitlab.arturbosch.detekt")
    id("org.jetbrains.kotlinx.kover")
}

kover {
    currentProject {
        createVariant("ci") {
            add("jvm")
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/detekt.yml")
    parallel = true
}

compose.resources {
    // letta-mobile-bglj6.1: one generated Res for the shared chat page's strings.
    packageOfResClass = "com.letta.mobile.sharedui.resources"
    publicResClass = false
    generateResClass = always
}

kotlin {
    // Every target, wasm included (letta-mobile-o4ygk.4).
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }

    android {
        namespace = "com.letta.mobile.sharedui"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        // letta-mobile-bglj6.1: package the Compose Multiplatform strings into the APK.
        androidResources {
            enable = true
        }

        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // letta-mobile-o4ygk.4: the web client compiles the same shared UI. The required
    // shared-multiplatform job compiles main and test for wasm; the browser tests are not run yet.
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain {
            dependencies {
                // Domain/transport contracts and projection models stay in
                // :sharedLogic; this module renders them.
                api(project(":sharedLogic"))
                // The mascot's identity model (shape + colour) and presence vocabulary; the picker
                // and the size-tiered avatar composable render them.
                api(project(":avatar:core"))
                api("org.jetbrains.compose.runtime:runtime:1.10.0")
                api("org.jetbrains.compose.foundation:foundation:1.10.0")
                api("org.jetbrains.compose.material3:material3:1.9.0")
                api("org.jetbrains.compose.ui:ui:1.10.0")
                api("org.jetbrains.compose.animation:animation:1.10.0")
                api("com.composables:icons-lucide:1.1.0")
                // Material icon set the lifted desktop composables still use (Lucide is the target; see letta-mobile-nm9te).
                api(libs.compose.desktop.material.icons)
                // Week strip in the schedules agenda view.
                implementation(libs.calendar.compose.multiplatform)
                // A2UI Image widget — coil3.compose.LocalPlatformContext is
                // multiplatform (unlike androidx LocalContext).
                api("io.coil-kt.coil3:coil-compose:3.5.0-beta01")
                // Shared Android/Desktop Markdown paint layer.
                api("com.mikepenz:multiplatform-markdown-renderer-m3:0.41.0")
                api("com.mikepenz:multiplatform-markdown-renderer-code:0.41.0")

                // Block document editor for canvas notes (letta-mobile-4i2z9.7): Notion-style blocks,
                // JSON round trip, undo; Android/JVM/iOS/wasm. MIT.
                implementation("io.github.linreal:cascade-editor:1.9.2")
                // DrawBox canvas editor, vendored (drawbox/VENDORED.md). api: the canvas's public
                // surface takes a DrawBoxController, and hosts and tests build one.
                api(project(":drawbox"))
                // Connector geometry only (letta-mobile-4i2z9.21). Kuiver's EdgePathFactory is a
                // pure function of two points, so it computes where a connector runs while DrawBox
                // keeps owning the element, its selection, undo and eraser. Its graph layout and
                // renderer are deliberately unused here.
                implementation(libs.kuiver)
                // Colour picking (anyColorPicker, Apache-2.0): HSL/RGB/CMYK/LAB/Okhsl pickers with
                // zero-drift conversions, published for android and jvm like everything else here.
                // Replaces three hand-rolled sliders that only spoke HSL and drifted on round trip.
                implementation(libs.colorpicker)
                // Picking images for a canvas: the system photo picker (several at once) on
                // Android, a file dialog on desktop. The same library the hosts already use.
                implementation(libs.filekit.core)
                implementation(libs.filekit.dialogs.compose)
                // letta-mobile-bglj6.1: the shared chat page's strings (Compose Multiplatform resources,
                // so Android and desktop read one copy instead of R.string and literals).
                implementation("org.jetbrains.compose.components:components-resources:1.10.0")
                // The shared chat page's paged canonical timeline (LazyPagingItems over
                // CanonicalTimelinePresentation.settled). KMP: android, jvm and wasm.
                implementation(libs.androidx.paging.compose)
                // letta-mobile-c3np7.3.11: the shared Home page's drag-to-reorder pinned grid - the
                // library both hosts already use for it. KMP: android, jvm and wasm.
                implementation(libs.reorderable)
                // DrawBoxController inherits from androidx.lifecycle.ViewModel; exposed as api so consumers resolve ViewModel hierarchy.
                api(libs.androidx.lifecycle.viewmodel)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        // Skia-backed actuals shared by desktop and web (letta-mobile-o4ygk.4): both render with
        // Skiko, so the SkSL glow shader and the Skia image decode compile once for both.
        val skikoMain by creating {
            dependsOn(commonMain.get())
        }

        jvmMain {
            dependsOn(skikoMain)
        }

        wasmJsMain {
            dependsOn(skikoMain)
        }

        androidMain {
            dependencies {
                // Turning picked photos upright before they go on a canvas.
                implementation(libs.androidx.exifinterface)
                // letta-mobile-bglj6.1: the shared chat page collects its port with the Android lifecycle.
                implementation(libs.androidx.lifecycle.runtime.compose)
                // letta-mobile-y5q9z: Back folds the canvas bubble's open card.
                implementation(libs.androidx.activity.compose)
            }
        }

        jvmTest {
            dependencies {
                // Model-control UI tests and their light/dark render snapshots (letta-mobile-w4q4p.6.1).
                implementation(libs.compose.desktop.ui.test)
                implementation(libs.junit4)
                implementation(libs.kotlinx.coroutines.test)
                implementation(compose.desktop.currentOs)
                // letta-mobile-bglj6.1: Compose UI tests for the shared chat page (runComposeUiTest).
                implementation(libs.compose.desktop.ui.test)
                implementation(kotlin("test"))
                // The phone fixtures (conversation, ports, stand-in mascot) the desktop phone playground shows too.
                implementation(project(":sharedUI-devfixtures"))
            }
        }
    }
}
