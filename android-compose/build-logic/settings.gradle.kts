pluginManagement {
    repositories {
        google()
        // Google's read-only mirror of Maven Central comes FIRST, Central second. Gradle only
        // falls through to the next repository on a 404; a 429 or 5xx is a hard failure that
        // ends resolution, so a mirror placed after Central never rescued a rate-limited
        // build (Central kept answering 429 in CI; letta-mobile-gh3gm). Central stays as the
        // fallback for anything not mirrored yet. gradlePluginPortal() is no alternative: it
        // 303-redirects every Central coordinate back to repo.maven.apache.org.
        maven("https://maven-central.storage-download.googleapis.com/maven2") { name = "MavenCentralMirror" }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
    repositories {
        google()
        // Google's read-only mirror of Maven Central comes FIRST, Central second. Gradle only
        // falls through to the next repository on a 404; a 429 or 5xx is a hard failure that
        // ends resolution, so a mirror placed after Central never rescued a rate-limited
        // build (Central kept answering 429 in CI; letta-mobile-gh3gm). Central stays as the
        // fallback for anything not mirrored yet. gradlePluginPortal() is no alternative: it
        // 303-redirects every Central coordinate back to repo.maven.apache.org.
        maven("https://maven-central.storage-download.googleapis.com/maven2") { name = "MavenCentralMirror" }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "letta-build-logic"
