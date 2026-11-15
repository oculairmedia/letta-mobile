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

// Design-system rules over whole directories, ignoring the includes scoping in detekt-guardrails.yml.
// designSystemLintCount is the local counting aid: -PdesignLintPaths takes comma-separated
// directories relative to android-compose/ (default: every UI root) and it never fails.
// designSystemLintGate always scans every UI root and fails on any finding; CI runs it so a clean
// tree stays clean even in files a change did not touch. There is no baseline: it went in at zero.
val designUiRoots = listOf("sharedUI/src", "desktop/src", "app/src", "feature-chat/src", "designsystem/src")
val designLintPaths = providers.gradleProperty("designLintPaths")
    .map { value -> value.split(',').map(String::trim).filter(String::isNotEmpty) }
    .orElse(designUiRoots)

listOf(
    Triple("designSystemLintCount", designLintPaths, false),
    Triple("designSystemLintGate", providers.provider { designUiRoots }, true),
).forEach { (taskName, paths, failOnFindings) ->
    val report = layout.buildDirectory.file("reports/detekt/$taskName.xml")
    val scan = tasks.register<io.gitlab.arturbosch.detekt.Detekt>("${taskName}Scan") {
        description = "Run every design-system rule over whole directories for $taskName."
        group = "verification"
        dependsOn(tasks.jar)
        setSource(paths.map { roots -> roots.map(rootProject::file) })
        include("**/*.kt")
        config.setFrom(file("design-system-count.yml"))
        buildUponDefaultConfig = false
        parallel = true
        jvmTarget = "17"
        ignoreFailures = true
        outputs.upToDateWhen { false }
        reports {
            xml.required.set(true)
            xml.outputLocation.set(report)
            sarif.required.set(false)
            html.required.set(false)
            txt.required.set(false)
            md.required.set(false)
        }
    }
    tasks.register(taskName) {
        description = if (failOnFindings) {
            "Fail on any design-system finding in any UI root."
        } else {
            "Print design-system findings per rule and per file for -PdesignLintPaths (default: all UI roots)."
        }
        group = "verification"
        dependsOn(scan)
        val base = rootProject.projectDir.parentFile
        doLast {
            val xml = report.get().asFile
            check(xml.isFile) { "No report at $xml: the directories held no Kotlin sources." }
            val findings = Regex("""<file name="([^"]+)">(.*?)</file>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml.readText())
                .flatMap { file ->
                    val path = File(file.groupValues[1]).relativeTo(base).invariantSeparatorsPath
                    Regex("""line="(\d+)"[^>]*source="detekt\.([A-Za-z]+)"""").findAll(file.groupValues[2])
                        .map { it.groupValues[2] to "$path:${it.groupValues[1]}" }
                }
                .toList()
            findings.groupBy({ it.first }, { it.second.substringBeforeLast(':') }).toSortedMap().forEach { (rule, files) ->
                println("$rule: ${files.size}")
                files.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.forEach { (path, count) ->
                    println("  $count  $path")
                }
            }
            println("Total: ${findings.size} findings in ${findings.map { it.second.substringBeforeLast(':') }.distinct().size} files")
            if (failOnFindings && findings.isNotEmpty()) {
                findings.forEach { (rule, location) -> println("  $location  [$rule]") }
                throw GradleException(
                    "${findings.size} design-system findings. Fix them (rule messages name what to use); " +
                        "there is no baseline. Rerun with :quality:detekt-rules:designSystemLintCount.",
                )
            }
        }
    }
}
