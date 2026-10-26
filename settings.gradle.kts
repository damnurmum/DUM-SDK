pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
    }
    plugins {
        id("net.fabricmc.fabric-loom") version providers.gradleProperty("loom_version").get()
    }
}

plugins {
    // Downloads JDK 25 when the machine does not have it
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "DUM-SDK"

include("dum-network", "dum-fabric", "dum-example-module")

// DUM-Core is not public. The server plugin is built only where dumCorePath
// (set in ~/.gradle/gradle.properties) points at a DUM-Core checkout.
val dumCorePath = providers.gradleProperty("dumCorePath").orNull
if (dumCorePath != null) {
    includeBuild(dumCorePath)
    include("dum-paper")
} else {
    println("dumCorePath is not set, skipping dum-paper")
}
