plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Internal test utilities (a scriptable in-memory HttpClient + an optional CSS
// harness). Not published; consumers don't depend on it.
dependencies {
    api(project(":solid-oidc"))
    api(project(":solid-rdf"))
}
