plugins {
    id("org.jetbrains.kotlin.jvm")
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
    testImplementation(libs.konsist)
    testImplementation(libs.archunit)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    dependsOn(":core:ids:jvmMainClasses", ":sharedLogic:jvmMainClasses")
    useJUnitPlatform()
    systemProperty("architecture.projectRoot", rootProject.projectDir.parentFile.absolutePath)
}

// Production scans run in architectureTest; both tasks run in the required CI `test` job.
val productionScanTests = listOf("*RepositoryArchitectureTest", "*SharedLayeringTest")

tasks.test {
    filter {
        productionScanTests.forEach { excludeTestsMatching(it) }
    }
}

val architectureTest = tasks.register<Test>("architectureTest") {
    description = "Run Kotlin source and JVM bytecode architecture checks (hard fail)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
        productionScanTests.forEach { includeTestsMatching(it) }
    }
    // The scans read source and baselines straight off disk; declare them so an up-to-date or
    // cached result is never reused after a baseline or scanned source changes.
    inputs.files(
        fileTree(rootProject.projectDir) {
            include("*/src/**/*.kt", "*/*/src/**/*.kt")
            exclude("**/build/**")
        },
    ).withPropertyName("scannedSources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files("package-cycle-baseline.txt", "sharedlogic-ui-package-baseline.txt")
        .withPropertyName("layeringBaselines").withPathSensitivity(PathSensitivity.RELATIVE)
    reports.junitXml.required.set(true)
    reports.html.required.set(true)
}

// JsonInstanceRatchetTest (letta-mobile-by4vm.20) reads production sources and its baseline off
// disk; declare them so an up-to-date `test` result is never reused after either changes.
tasks.test {
    inputs.files(
        fileTree(rootProject.projectDir) {
            include("**/src/**/*.kt")
            exclude("**/build/**")
        },
    ).withPropertyName("jsonRatchetSources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("json-instances-baseline.txt")
        .withPropertyName("jsonRatchetBaseline").withPathSensitivity(PathSensitivity.RELATIVE)
}
