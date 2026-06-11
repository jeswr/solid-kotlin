pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Auto-provision the JDK 17 toolchain the modules target, so the whole tree
// builds with whatever JDK happens to run Gradle.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "solid-kotlin"

include(":solid-rdf")
include(":solid-object")
include(":solid-oidc")
include(":solid-reactive-auth")
include(":solid-pod")
include(":solid-test-support")
