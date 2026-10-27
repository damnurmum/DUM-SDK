plugins {
    id("net.fabricmc.fabric-loom")
}

base {
    archivesName = "DUM-SDK"
}

repositories {
    maven("https://api.modrinth.com/maven") {
        content { includeGroup("maven.modrinth") }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    implementation(project(":dum-network"))

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // ./gradlew runClient -Psodium, or -Piris (with Sodium): render the way the players have it. Dev client only
    if (hasProperty("sodium") || hasProperty("iris")) localRuntime("maven.modrinth:sodium:mc26.2-0.9.2-fabric")
    if (hasProperty("iris")) localRuntime("maven.modrinth:iris:1.11.4+26.2-fabric")
}

// ./gradlew :dum-fabric:runClientGameTest: a client that joins a running test server by itself and checks
// the handshake, modules and failures (src/gametest). -PtestServer=<address>, localhost by default.
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "dum-sdk-gametest"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
    }
}

loom {
    accessWidenerPath = file("src/main/resources/dum-sdk.classtweaker")

    runs.named("client") {
        // ./gradlew runClient -Pjoin=localhost connects straight to a server
        if (project.hasProperty("join")) {
            programArgs("--quickPlayMultiplayer", project.property("join").toString())
        }
        // ./gradlew runClient -Prelease behaves like a player's client: modules signed with the dev key are refused
        if (project.hasProperty("release")) {
            vmArg("-Ddumsdk.release=true")
        }
    }
    runs.matching { it.name == "clientGameTest" }.configureEach {
        vmArg("-Ddumsdk.testServer=${findProperty("testServer") ?: "localhost"}")
    }
}

tasks {
    test {
        useJUnitPlatform()
    }

    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("fabric.mod.json") { expand(props) }
    }

    jar {
        from(project(":dum-network").extensions.getByType<SourceSetContainer>()["main"].output)
    }
}
