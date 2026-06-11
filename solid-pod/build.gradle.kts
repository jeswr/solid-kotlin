plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":solid-rdf"))
    api(project(":solid-oidc"))
    api(project(":solid-object"))
    testImplementation(project(":solid-test-support"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
