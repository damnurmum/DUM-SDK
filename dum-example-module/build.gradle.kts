import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

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
    // ./gradlew :dum-example-module:deploy signs the jar with the key in dumSigningKey (~/.dum/signing.key
    // by default) and puts both into dumModulesDir, the server's plugins/DUM-SDK/modules.
    // The file name has no version, so an older one never stays next to it.
    register("deploy") {
        group = "dum"
        val built = jar.flatMap { it.archiveFile }
        val keyPath = providers.gradleProperty("dumSigningKey").orElse("${System.getProperty("user.home")}/.dum/signing.key")
        val target = providers.gradleProperty("dumModulesDir")
        dependsOn(jar)
        doLast {
            if (!target.isPresent) throw GradleException("Set dumModulesDir in ~/.gradle/gradle.properties to the server's plugins/DUM-SDK/modules")
            val key = KeyFactory.getInstance("Ed25519").generatePrivate(
                PKCS8EncodedKeySpec(Base64.getDecoder().decode(File(keyPath.get()).readText().trim())))
            val bytes = built.get().asFile.readBytes()
            val signature = Signature.getInstance("Ed25519").run {
                initSign(key)
                update(bytes)
                sign()
            }
            val dir = File(target.get()).apply { mkdirs() }
            File(dir, "info.jar").writeBytes(bytes)
            File(dir, "info.jar.sig").writeBytes(signature)
            logger.lifecycle("Signed with ${keyPath.get()} and deployed to $dir")
        }
    }
}
