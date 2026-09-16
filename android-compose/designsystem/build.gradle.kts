plugins {
    id("com.letta.mobile.android.library")
    id("app.cash.paparazzi")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.gitlab.arturbosch.detekt")
    id("org.jetbrains.kotlinx.kover") // version inherited from root
}

kover {
    currentProject {
        createVariant("ci") {
            add("debug")
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/detekt.yml")
    parallel = true
}

android {
    namespace = "com.letta.mobile.designsystem"

    buildFeatures {
        compose = true
    }

    lint {
        // Workaround: NonNullableMutableLiveDataDetector crashes with
        // IncompatibleClassChangeError on newer Kotlin + AGP combinations.
        // This module has no LiveData usage so the check is irrelevant.
        disable += "NullSafeMutableLiveData"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.useJUnitPlatform()
                // Memory caps — see core/build.gradle.kts for rationale.
                it.maxHeapSize = "1024m"
                it.jvmArgs("-XX:+UseG1GC", "-XX:MaxMetaspaceSize=256m")
                it.forkEvery = 100L
            }
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
        }
        getByName("test") {
            kotlin.directories += "src/test/java"
        }
    }
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=kotlin.contracts.ExperimentalContracts",
        )
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(project(":sharedLogic"))
    api(project(":sharedUI"))
    implementation(project(":core:android-data"))

    implementation(libs.coil3.compose)
    implementation(libs.coil3.svg)
    implementation(libs.androidx.compose.material3.chat)
    implementation(libs.androidx.compose.material3.windowsize.chat)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    api(libs.icons.lucide)
    implementation(libs.jindong.core)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.coil3)
    implementation(libs.markdown.renderer.code)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // letta-mobile-rl0d (audio): required for HoldToDictateButton's
    // RECORD_AUDIO permission flow (rememberLauncherForActivityResult,
    // ActivityResultContracts) and ContextCompat.checkSelfPermission.
    // Designsystem stays Hilt-free — VoiceInputViewModel lives in :app.
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.google.material)

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.vintage.engine)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Test tier tasks
tasks.register<Test>("testUnit") {
    description = "Runs unit-tier tests (pure logic, <50ms per test)"
    group = "verification"

    val testTask = tasks.named("testDebugUnitTest", Test::class).get()
    testClassesDirs = testTask.testClassesDirs
    classpath = testTask.classpath

    useJUnitPlatform {
        includeTags("unit")
    }

    systemProperty("kotest.tags.include", "unit")
}

tasks.register<Test>("testIntegration") {
    description = "Runs integration-tier tests (Robolectric, Compose, ViewModels)"
    group = "verification"

    val testTask = tasks.named("testDebugUnitTest", Test::class).get()
    testClassesDirs = testTask.testClassesDirs
    classpath = testTask.classpath

    useJUnitPlatform {
        includeTags("integration")
    }

    systemProperty("kotest.tags.include", "integration")
}

tasks.register<Test>("testScreenshot") {
    description = "Runs screenshot-tier tests (Paparazzi/Roborazzi visual regression)"
    group = "verification"

    val testTask = tasks.named("testDebugUnitTest", Test::class).get()
    testClassesDirs = testTask.testClassesDirs
    classpath = testTask.classpath

    useJUnitPlatform {
        includeTags("screenshot")
    }

    systemProperty("kotest.tags.include", "screenshot")
}
