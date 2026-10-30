repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${property("paper_version")}")
    compileOnly("ru.dum:DUM-Core")
    implementation(project(":dum-network"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.slf4j:slf4j-api:2.0.17")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks {
    processResources {
        filteringCharset = "UTF-8"
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("paper-plugin.yml") { expand(props) }
    }

    jar {
        archiveFileName = "DUM-SDK-Paper-${project.version}.jar"
        from(project(":dum-network").extensions.getByType<SourceSetContainer>()["main"].output)
    }
}
