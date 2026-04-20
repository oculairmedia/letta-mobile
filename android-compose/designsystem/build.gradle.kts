plugins {
    id("com.android.library")
    id("app.cash.paparazzi")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.gitlab.arturbosch.detekt")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/detekt.yml")
    parallel = true
}

android {
    namespace = "com.letta.mobile.designsystem"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

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
            isReturnDefaultValues = true
            all {
                it.useJUnitPlatform()
                // Memory caps — see core/build.gradle.kts for rationale.
                it.maxHeapSize = "1024m"
                it.jvmArgs("-XX:+UseG1GC", "-XX:MaxMetaspaceSize=256m")
                it.setForkEvery(100L)
            }
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
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
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)

    implementation(project(":core"))

    implementation("androidx.compose.material3:material3:1.5.0-alpha17")
    implementation("androidx.compose.material3:material3-window-size-class:1.5.0-alpha17")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    api("com.composables:icons-lucide:1.1.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.38.1")
    implementation("com.mikepenz:multiplatform-markdown-renderer-coil3:0.38.1")
    implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.38.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.17")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.5")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.5")
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
