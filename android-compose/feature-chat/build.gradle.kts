plugins {
    id("com.letta.mobile.android.library")
    id("io.github.takahirom.roborazzi")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.allopen")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
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

allOpen {
    annotation("javax.inject.Singleton")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/detekt.yml")
    parallel = true
}

android {
    namespace = "com.letta.mobile.feature.chat"

    buildFeatures {
        compose = true
    }

    lint {
        disable += "NullSafeMutableLiveData"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.useJUnitPlatform()
                // Memory caps follow the app/core test-worker defaults.
                it.maxHeapSize = "1536m"
                it.jvmArgs("-XX:+UseG1GC", "-XX:MaxMetaspaceSize=384m")
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
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=kotlin.contracts.ExperimentalContracts",
        )
    }
}

dependencies {
    // Trial against this app's existing Compose stack; do not import the library's newer BOM.
    implementation("ir.farsroidx:compose-overscroll:1.0.0") {
        isTransitive = false
    }
    implementation(project(":core:android-data"))
    implementation(project(":sharedLogic"))
    implementation(project(":sharedUI"))
    testImplementation(project(":core:testutil"))
    implementation(project(":designsystem"))
    implementation(libs.filekit.core)
    implementation(libs.filekit.dialogs.compose)
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.windowsize)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.molecule.runtime)
    implementation(libs.coil3.compose)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.paging.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.mockk)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.datastore.preferences)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit.ktx)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.vintage.engine)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

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
    description = "Runs screenshot-tier tests (Roborazzi visual regression)"
    group = "verification"

    val testTask = tasks.named("testDebugUnitTest", Test::class).get()
    testClassesDirs = testTask.testClassesDirs
    classpath = testTask.classpath

    useJUnitPlatform {
        includeTags("screenshot")
    }

    systemProperty("kotest.tags.include", "screenshot")
}

// Hard cut S9 (letta-mobile-ckmub.2.2): the shared chat rows' golden screenshots (formerly the S1
// legacy vs shared parity harness). Renders every row type through the shared timeline, compares
// it with src/test/snapshots/rows/<theme>/<row>.png and writes golden/shared/diff PNGs and
// report.md (row type -> pixel diff %) to build/outputs/roborazzi/row-parity. Fails when a row
// moves more than 2% from its golden. Skipped by the regular testDebugUnitTest run (it needs
// letta.rowParity=true, set here). Record goldens with -Pletta.rowGolden.record=true.
//   ./gradlew --no-daemon :feature-chat:rowGoldenReport
val rowParityDir = layout.buildDirectory.dir("outputs/roborazzi/row-parity")
val rowGoldenDir = layout.projectDirectory.dir("src/test/snapshots/rows")
val recordRowGoldens = providers.gradleProperty("letta.rowGolden.record").orElse("false")
tasks.register<Test>("rowGoldenReport") {
    description = "Renders the shared chat rows and reports the pixel diff from their goldens per row type"
    group = "verification"

    val testTask = tasks.named("testDebugUnitTest", Test::class).get()
    testClassesDirs = testTask.testClassesDirs
    classpath = testTask.classpath
    maxHeapSize = testTask.maxHeapSize
    jvmArgs(testTask.jvmArgs ?: emptyList<String>())

    useJUnitPlatform()
    filter { includeTestsMatching("com.letta.mobile.feature.chat.parity.RowParityScreenshotTest") }
    systemProperty("letta.rowParity", "true")
    systemProperty("letta.rowParity.dir", rowParityDir.get().asFile.absolutePath)
    systemProperty("letta.rowGolden.dir", rowGoldenDir.asFile.absolutePath)
    systemProperty("letta.rowGolden.record", recordRowGoldens.get())
    outputs.dir(rowParityDir)
    outputs.upToDateWhen { false }

    val report = rowParityDir.map { it.file("report.md").asFile }
    doLast {
        val file = report.get()
        if (file.exists()) println(file.readText()) else println("row goldens: no report at $file")
    }
}
