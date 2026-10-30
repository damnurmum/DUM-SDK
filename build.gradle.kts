import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.HexFormat

subprojects {
    apply(plugin = "java")
    group = "ru.dum.sdk"

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(25)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    }
}

// ./gradlew generateSigningKey creates the Ed25519 key that signs client modules. Run it once, on the machine
// that will sign. The private key goes to ~/.dum/signing.key (or -Pkey=<file>) and never into this repo;
// the printed public key goes into Signing.RELEASE_KEY in dum-network.
tasks.register("generateSigningKey") {
    group = "dum"
    val keyPath = providers.gradleProperty("key").orElse("${System.getProperty("user.home")}/.dum/signing.key")
    doLast {
        val file = File(keyPath.get())
        if (file.exists()) throw GradleException("$file already exists. Delete it yourself if you really want a new key")
        val pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        file.parentFile.mkdirs()
        file.writeText(java.util.Base64.getEncoder().encodeToString(pair.private.encoded))
        // Readable by the owner only (no effect on Windows, where the user folder is private anyway)
        file.setReadable(false, false)
        file.setReadable(true, true)
        logger.lifecycle("Private key: $file")
        logger.lifecycle("Public key:  ${java.util.Base64.getEncoder().encodeToString(pair.public.encoded)}")
    }
}

// Prepares the same signed artifacts locally and on Actions. Publication is handled only by release.yml.
tasks.register("prepareRelease") {
    group = "dum"
    description = "Builds, tests and prepares signed client/Paper release artifacts without publishing."
    dependsOn(":dum-network:build", ":dum-fabric:build", ":dum-paper:build")
    val releaseVersion = project.version.toString()
    val signingKey = providers.environmentVariable("DUM_SIGNING_KEY")
    val keyPath = providers.gradleProperty("dumSigningKey").orElse("${System.getProperty("user.home")}/.dum/signing.key")
    val output = layout.buildDirectory.dir("release")
    doLast {
        if (!Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""").matches(releaseVersion)) {
            throw GradleException("Release version must be a stable X.Y.Z version")
        }
        val notes = file(".github/release-notes/$releaseVersion.md")
        if (!notes.isFile || notes.readText().isBlank()) throw GradleException("Missing release notes: $notes")
        val encodedKey = signingKey.orNull?.takeIf { it.isNotBlank() }
            ?: File(keyPath.get()).takeIf { it.isFile }?.readText()
            ?: throw GradleException("Set DUM_SIGNING_KEY or provide -PdumSigningKey=<private key file>")
        val source = file("dum-network/src/main/java/ru/dum/sdk/network/Signing.java").readText()
        val publicKey = Regex("""RELEASE_KEY = "([^"]+)"""").find(source)?.groupValues?.get(1)
            ?: throw GradleException("Signing.RELEASE_KEY is missing")
        val factory = KeyFactory.getInstance("Ed25519")
        val key = factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(encodedKey.trim())))
        val client = file("dum-fabric/build/libs/DUM-SDK-$releaseVersion.jar").readBytes()
        val paper = file("dum-paper/build/libs/DUM-SDK-Paper-$releaseVersion.jar").readBytes()
        val signature = Signature.getInstance("Ed25519").run {
            initSign(key)
            update(client)
            sign()
        }
        val matches = Signature.getInstance("Ed25519").run {
            initVerify(factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey))))
            update(client)
            verify(signature)
        }
        if (!matches) throw GradleException("Signing key does not match Signing.RELEASE_KEY; artifacts not prepared")
        val dir = output.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        val artifacts = linkedMapOf(
            "DUM-SDK.jar" to client,
            "DUM-SDK.jar.sig" to signature,
            "DUM-SDK-Paper-$releaseVersion.jar" to paper
        )
        artifacts.forEach { (name, bytes) -> File(dir, name).writeBytes(bytes) }
        File(dir, "SHA256SUMS.txt").writeText(artifacts.entries.joinToString("") { (name, bytes) ->
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + "  " + name + "\n"
        })
        logger.lifecycle("Prepared verified release artifacts in $dir; notes: $notes")
    }
}
