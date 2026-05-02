plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.letta.mobile.cli"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                // We use JUnit Jupiter but as a *runner* — the actual CLI
                // logic lives in the test source set so it has the full
                // Android-stub classpath available (Log, etc. via
                // Robolectric-style stubs from android.jar).
                it.useJUnitPlatform()
                it.maxHeapSize = "1536m"
                it.jvmArgs("-XX:+UseG1GC", "-XX:MaxMetaspaceSize=384m")
                // Pipe stdout/stderr through so the user can see CLI
                // output while running via `gradle :cli:run`.
                it.testLogging {
                    showStandardStreams = true
                    events("standard_out", "standard_error")
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // We need :core's TimelineSyncLoop, SseParser, MessageApi, models, etc.
    // testImplementation rather than implementation because the CLI code
    // lives in src/test (see rationale above).
    testImplementation(project(":core"))
    // :bot brings in WsBotClient — the exact WebSocket client the
    // Android app uses to talk to lettabot in Client Mode. We reuse it
    // verbatim so the wsstream subcommand drives the same wire path.
    testImplementation(project(":bot"))

    // CLI parsing
    testImplementation("com.github.ajalt.clikt:clikt:4.4.0")

    // Coroutines
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // JSON
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // OkHttp for direct API calls (mirrors what :core uses internally)
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Ktor — same versions as :core / :bot. We use Ktor directly because
    // SseParser takes a Ktor ByteReadChannel.
    testImplementation("io.ktor:ktor-client-core:3.4.2")
    testImplementation("io.ktor:ktor-client-okhttp:3.4.2")
    testImplementation("io.ktor:ktor-client-content-negotiation:3.4.2")
    testImplementation("io.ktor:ktor-serialization-kotlinx-json:3.4.2")

    // JUnit 5 — used as a runner only.
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

// Custom task: `./gradlew :cli:run --args="stream --message hello"`
tasks.register<Test>("run") {
    description = "Run the letta-mobile CLI."
    group = "application"

    val testTask = tasks.named("testDebugUnitTest", Test::class).get()
    testClassesDirs = testTask.testClassesDirs
    classpath = testTask.classpath

    useJUnitPlatform()

    // Args are passed through a system property (Gradle's --args is for
    // application plugin only; we improvise).
    val cliArgs: String = (project.findProperty("cliArgs") as? String) ?: ""
    systemProperty("letta.cli.args", cliArgs)

    // Always re-run; this is interactive.
    outputs.upToDateWhen { false }

    // Show CLI stdout/stderr inline.
    testLogging {
        showStandardStreams = true
        events("standard_out", "standard_error")
    }

    // Only invoke the CLI runner test, not the whole test suite.
    filter {
        includeTestsMatching("com.letta.mobile.cli.CliRunnerTest")
    }
}
