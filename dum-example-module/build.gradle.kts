plugins {
    id("net.fabricmc.fabric-loom")
}

// A client module, not a Fabric mod: it has its own version and is loaded by DUM-SDK at runtime
version = "0.2.0"

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

    // ./gradlew :dum-example-module:install puts the module into the dev client (dum-fabric/run)
    // One file name for every version, so an older one never stays next to it
    register<Copy>("install") {
        from(jar)
        into(rootProject.file("dum-fabric/run/dum-sdk/modules"))
        rename { "info.jar" }
    }
}
