plugins {
    id("net.fabricmc.fabric-loom")
}

// A development module that exercises the SDK hooks: world rendering, Fabric events, packets.
// Not for players: deploy it to a test server only.
version = "0.1.0"

base {
    archivesName = "sdktest"
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    compileOnly(project(":dum-fabric"))
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("dum-module.json") { expand(props) }
}
