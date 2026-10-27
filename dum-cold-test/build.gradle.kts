plugins {
    id("net.fabricmc.fabric-loom")
}

// A development Fabric mod for the cold load path: a real mixin, delivered by the server, active after a restart.
// Not for players: deploy it to a test server only.
version = "0.1.0"

base {
    archivesName = "dumcoldtest"
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
}
