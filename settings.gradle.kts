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

include("dum-network", "dum-fabric", "dum-paper")
