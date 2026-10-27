plugins {
    id("net.fabricmc.fabric-loom")
}

// A client module, not a Fabric mod: it has its own version and is loaded by DUM-SDK at runtime
version = "0.4.0"

base {
    archivesName = "info"
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    compileOnly(project(":dum-fabric"))
}

tasks {
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("dum-module.json") { expand(props) }
    }
}
