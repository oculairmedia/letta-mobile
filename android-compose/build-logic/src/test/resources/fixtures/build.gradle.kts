plugins {
    id("com.letta.mobile.architecture-graph")
    java
}

val kspJvm by configurations.creating
val notKsp by configurations.creating

dependencies {
    implementation(project(":lib"))
    implementation("org.example:zeta:2.0")
    testImplementation("org.example:alpha:1.0")
    kspJvm("org.example:processor:3.0")
    notKsp("org.example:ignored:1.0")
    // Project edges are kept from every configuration, so the boundary gate
    // sees an edge however it is declared.
    notKsp(project(":lib"))
}
