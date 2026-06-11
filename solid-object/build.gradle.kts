plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":solid-rdf"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
