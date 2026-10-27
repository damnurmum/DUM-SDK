import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

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
        // ./gradlew runClient -PupdateTest=<url of a release JSON> runs the self-update against that fake release
        if (project.hasProperty("updateTest")) {
            vmArgs("-Ddumsdk.updateTest=true", "-Ddumsdk.updateUrl=${project.property("updateTest")}")
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

    // ./gradlew :dum-fabric:releaseSdk, on the PC with the release key: builds the mod, signs it, makes sure the key
    // is the one in Signing.RELEASE_KEY and publishes GitHub release v<version> with DUM-SDK.jar and its .sig (gh CLI).
    // Players' mods pick it up on their next game start. Push the code first: the release tags main.
    register("releaseSdk") {
        group = "dum"
        val built = jar.flatMap { it.archiveFile }
        val keyPath = providers.gradleProperty("dumSigningKey").orElse("${System.getProperty("user.home")}/.dum/signing.key")
        val signingSource = rootProject.file("dum-network/src/main/java/ru/dum/sdk/network/Signing.java")
        val version = project.version.toString()
        val out = layout.buildDirectory.dir("release")
        dependsOn(jar)
        doLast {
            val releaseKey = Regex("RELEASE_KEY = \"([^\"]*)\"").find(signingSource.readText())!!.groupValues[1]
            if (releaseKey.isEmpty()) throw GradleException("Signing.RELEASE_KEY is empty: run generateSigningKey and put the public key there first")
            val key = KeyFactory.getInstance("Ed25519").generatePrivate(
                PKCS8EncodedKeySpec(Base64.getDecoder().decode(File(keyPath.get()).readText().trim())))
            val bytes = built.get().asFile.readBytes()
            val signature = Signature.getInstance("Ed25519").run {
                initSign(key)
                update(bytes)
                sign()
            }
            // The mods out there check releases with RELEASE_KEY: anything else would be refused by all of them
            val matches = Signature.getInstance("Ed25519").run {
                initVerify(KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(releaseKey))))
                update(bytes)
                verify(signature)
            }
            if (!matches) throw GradleException("${keyPath.get()} is not the release key from Signing.RELEASE_KEY")
            val dir = out.get().asFile.apply { mkdirs() }
            File(dir, "DUM-SDK.jar").writeBytes(bytes)
            File(dir, "DUM-SDK.jar.sig").writeBytes(signature)
            val gh = ProcessBuilder("gh", "release", "create", "v$version", "DUM-SDK.jar", "DUM-SDK.jar.sig",
                "--title", "DUM-SDK $version", "--notes", "DUM-SDK $version").directory(dir).inheritIO().start()
            if (gh.waitFor() != 0) throw GradleException("gh release create failed")
        }
    }
}
