plugins {
    id("org.jetbrains.kotlin.jvm")
    id("io.gitlab.arturbosch.detekt")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.detekt.api)
    detektPlugins(files(tasks.jar))

    testImplementation(libs.detekt.api)
    testImplementation(libs.detekt.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

val changedKotlinFiles = providers.provider {
    val repositoryRoot = rootProject.projectDir.parentFile
    val python = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
    val paths = providers.exec {
        workingDir(repositoryRoot)
        // Track the PR context as an input; the selector also handles an empty value on push events.
        environment("GITHUB_BASE_REF", providers.environmentVariable("GITHUB_BASE_REF").orElse("").get())
        commandLine(python, "scripts/ci/changed-kotlin-code.py")
    }.standardOutput.asText.get().lineSequence().filter(String::isNotBlank).toSet()
    paths.asSequence()
        .filter { it.startsWith("android-compose/") && it.endsWith(".kt") }
        .filterNot { "/build/" in it || "/generated/" in it || "/quality/detekt-rules/" in it }
        // Vendored upstream source (android-compose/drawbox/VENDORED.md) keeps upstream's API and style.
        .filterNot { it.startsWith("android-compose/drawbox/") }
        .map(repositoryRoot::resolve)
        .filter(File::isFile)
        .toList()
}

tasks.register<io.gitlab.arturbosch.detekt.Detekt>("guardrailDetekt") {
    description = "Block high-risk Kotlin policy violations in files with code changes from the PR base."
    group = "verification"
    dependsOn(tasks.jar)
    setSource(changedKotlinFiles)
    config.setFrom(rootProject.file("detekt-guardrails.yml"))
    buildUponDefaultConfig = false
    parallel = true
    jvmTarget = "17"
    ignoreFailures = false
    reports {
        sarif.required.set(true)
        xml.required.set(true)
        html.required.set(true)
        txt.required.set(false)
        md.required.set(false)
    }
}

tasks.check {
    dependsOn("guardrailDetekt")
}
