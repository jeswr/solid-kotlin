plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

// Shared configuration applied to every Kotlin/JVM subproject. All modules
// target JDK 17 bytecode via the Kotlin toolchain so a single JDK can build
// the whole tree; the Android-coupled modules keep their platform glue behind
// interfaces (see each module's README section) so the pure logic is unit-
// testable on the JVM with no emulator.
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(17)
            explicitApi()
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("passed", "failed", "skipped")
            }
        }
    }
}
