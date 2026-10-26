plugins {
    id("net.fabricmc.fabric-loom")
}

base {
    archivesName = "DUM-SDK"
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    implementation(project(":dum-network"))
}

loom {
    runs.named("client") {
        // ./gradlew runClient -Pjoin=localhost connects straight to a server
        if (project.hasProperty("join")) {
            programArgs("--quickPlayMultiplayer", project.property("join").toString())
        }
    }
}

tasks {
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("fabric.mod.json") { expand(props) }
    }

    jar {
        from(project(":dum-network").extensions.getByType<SourceSetContainer>()["main"].output)
    }
}
