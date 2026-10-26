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
