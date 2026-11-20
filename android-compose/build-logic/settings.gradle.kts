pluginManagement {
    repositories {
        google()
        mavenCentral()
        // Fallback only (declared after mavenCentral(), so it is asked only when Central
        // answers 404/429/5xx): Google's read-only mirror of Maven Central. Central
        // returned transient 404s and 429s for artifacts that exist (letta-mobile-gh3gm);
        // gradlePluginPortal() is no alternative, it 303-redirects every Central coordinate
        // back to repo.maven.apache.org.
        maven("https://maven-central.storage-download.googleapis.com/maven2") { name = "MavenCentralMirror" }
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
        mavenCentral()
        // Fallback only (declared after mavenCentral(), so it is asked only when Central
        // answers 404/429/5xx): Google's read-only mirror of Maven Central. Central
        // returned transient 404s and 429s for artifacts that exist (letta-mobile-gh3gm);
        // gradlePluginPortal() is no alternative, it 303-redirects every Central coordinate
        // back to repo.maven.apache.org.
        maven("https://maven-central.storage-download.googleapis.com/maven2") { name = "MavenCentralMirror" }
        gradlePluginPortal()
    }
}

rootProject.name = "letta-build-logic"
