plugins {
    alias(libs.plugins.kotlin.jvm)
}

// The Kotlin analogue of @solid/reactive-authentication: install an OkHttp
// Authenticator + Interceptor so app code uses a plain OkHttpClient and never
// threads a token by hand. Depends on OkHttp (the de-facto Android/JVM HTTP
// client) and solid-oidc for the Solid-OIDC + DPoP flow.
dependencies {
    api(project(":solid-oidc"))
    api(libs.okhttp)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.okhttp.mockwebserver)
    testRuntimeOnly(libs.junit.platform.launcher)
}
