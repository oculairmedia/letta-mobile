plugins {
    id("com.letta.mobile.architecture-graph")
    java
}

dependencies {
    implementation(project(":lib"))
    implementation("org.example:zeta:2.0")
    testImplementation("org.example:alpha:1.0")
}
