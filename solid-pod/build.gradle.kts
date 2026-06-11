plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":solid-rdf"))
    api(project(":solid-oidc"))
    api(project(":solid-object"))
    testImplementation(project(":solid-test-support"))
    // The optional CSS integration suite (gated behind SOLID_KOTLIN_CSS_TESTS)
    // drives a real OkHttp transport via the reactive-auth adapter.
    testImplementation(project(":solid-reactive-auth"))
    testImplementation(libs.okhttp)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
