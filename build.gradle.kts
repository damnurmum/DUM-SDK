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

// Client module projects. ./gradlew :<project>:deploy signs the module jar with the key in dumSigningKey
// (~/.dum/signing.key by default) and puts both into dumModulesDir, the server's plugins/DUM-SDK/modules.
// The file name has no version, so an older one never stays next to it.
configure(listOf(project(":dum-example-module"), project(":dum-test-module"))) {
    tasks.register("deploy") {
        group = "dum"
        val jar = tasks.named<Jar>("jar")
        val built = jar.flatMap { it.archiveFile }
        val name = jar.flatMap { it.archiveBaseName }
        val keyPath = providers.gradleProperty("dumSigningKey").orElse("${System.getProperty("user.home")}/.dum/signing.key")
        val target = providers.gradleProperty("dumModulesDir")
        dependsOn(jar)
        doLast {
            if (!target.isPresent) throw GradleException("Set dumModulesDir in ~/.gradle/gradle.properties to the server's plugins/DUM-SDK/modules")
            val key = java.security.KeyFactory.getInstance("Ed25519").generatePrivate(
                java.security.spec.PKCS8EncodedKeySpec(java.util.Base64.getDecoder().decode(File(keyPath.get()).readText().trim())))
            val bytes = built.get().asFile.readBytes()
            val signature = java.security.Signature.getInstance("Ed25519").run {
                initSign(key)
                update(bytes)
                sign()
            }
            val dir = File(target.get()).apply { mkdirs() }
            File(dir, "${name.get()}.jar").writeBytes(bytes)
            File(dir, "${name.get()}.jar.sig").writeBytes(signature)
            logger.lifecycle("Signed with ${keyPath.get()} and deployed to $dir")
        }
    }
}
