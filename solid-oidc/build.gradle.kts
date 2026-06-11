plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure-JVM OIDC core: WebID→issuer discovery, PKCE, DPoP (java.security
// ES256), dynamic registration, token exchange, secure persistence — all
// behind interfaces. The Android glue (Chrome Custom Tabs redirect agent,
// EncryptedSharedPreferences store) is documented in the README and lives in
// the consuming Android app; nothing here needs the Android SDK, so the whole
// module unit-tests on the JVM with no emulator.
dependencies {
    api(project(":solid-rdf"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
