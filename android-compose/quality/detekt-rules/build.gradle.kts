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
    compileOnly("io.gitlab.arturbosch.detekt:detekt-api:1.23.8")
    detektPlugins(files(tasks.jar))

    testImplementation("io.gitlab.arturbosch.detekt:detekt-api:1.23.8")
    testImplementation("io.gitlab.arturbosch.detekt:detekt-test:1.23.8")
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

val changedKotlinFiles = providers.provider {
    val repositoryRoot = rootProject.projectDir.parentFile
    fun git(vararg args: String): Set<String> = providers.exec {
        workingDir(repositoryRoot)
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().lineSequence().filter(String::isNotBlank).toSet()

    val base = providers.environmentVariable("GITHUB_BASE_REF").orNull?.let { "origin/$it" } ?: "origin/main"
    val paths = git("diff", "--name-only", "$base...HEAD") +
        git("diff", "--name-only") + git("ls-files", "--others", "--exclude-standard")
    paths.asSequence()
        .filter { it.startsWith("android-compose/") && it.endsWith(".kt") }
        .filterNot { "/build/" in it || "/generated/" in it || "/quality/detekt-rules/" in it }
        .map(repositoryRoot::resolve)
        .filter(File::isFile)
        .toList()
}

tasks.register<io.gitlab.arturbosch.detekt.Detekt>("guardrailDetekt") {
    description = "Block new high-risk Kotlin policy violations in files changed from origin/main."
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
