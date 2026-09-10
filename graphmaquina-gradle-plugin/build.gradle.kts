plugins {
    `java-gradle-plugin`
    `maven-publish`
    signing
}
group = "io.github.graphmaquina"
// Same resolution as the root build's convention plugin; this build is separate and cannot see buildSrc,
// so the six lines are repeated here for the same reason the POM metadata below is.
version = providers.gradleProperty("graphmaquinaVersion").orElse(
    providers.exec {
        commandLine("git", "describe", "--tags", "--abbrev=0")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim().removePrefix("v").ifEmpty { "0.0.0-dev" } }
).get()
java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
    // Central rejects a release without a sources and a javadoc jar beside the binary.
    withSourcesJar()
    withJavadocJar()
}
tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial", "-Werror"))
}
gradlePlugin {
    plugins {
        create("graphmaquina") {
            id = "io.github.graphmaquina"
            displayName = "GraphMaquina Gradle plugin"
            implementationClass = "io.github.graphmaquina.gradle.StateGraphPlugin"
        }
    }
}
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(gradleTestKit())
    // Only the tests decode the JSON the generator publishes; the plugin itself never loads codegen classes.
    testImplementation("com.google.code.gson:gson:2.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
signing {
    // Same environment-driven key as the root build's convention plugin; this build cannot see that plugin.
    val key = providers.environmentVariable("SIGNING_KEY").orNull
    val password = providers.environmentVariable("SIGNING_PASSWORD").orElse("").get()
    isRequired = key != null
    if (key != null) {
        useInMemoryPgpKeys(key, password)
        sign(publishing.publications)
    }
}

publishing {
    // This is a separate included build, so it does not see the root build's convention plugin: the
    // POM metadata Central requires is repeated here rather than shared.
    publications.withType<MavenPublication>().configureEach {
        pom {
            name = "GraphMaquina Gradle plugin"
            description = "Gradle plugin that generates the typed graph API before javac and runs the verified compilation lifecycle around it"
            url = "https://github.com/graphmaquina/graphmaquina"
            licenses {
                license {
                    name = "The Apache License, Version 2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                }
            }
            developers {
                developer {
                    id = "0xhug0"
                    name = "Hugo Gonzalez"
                    url = "https://github.com/graphmaquina"
                }
            }
            scm {
                connection = "scm:git:https://github.com/graphmaquina/graphmaquina.git"
                developerConnection = "scm:git:ssh://git@github.com/graphmaquina/graphmaquina.git"
                url = "https://github.com/graphmaquina/graphmaquina"
            }
        }
    }
    // The same file repository the sibling projects publish core, codegen and the processor to, so a fixture
    // build can resolve the plugin and its marker the way any consumer resolves them from a repository.
    repositories {
        maven {
            name = "adapterTests"
            url = layout.projectDirectory.dir("../build/adapter-test-repository").asFile.toURI()
        }
        // The staging directory the sibling modules also publish into, so one bundle carries every artifact.
        maven {
            name = "centralStaging"
            url = layout.projectDirectory.dir("../build/central-staging").asFile.toURI()
        }
    }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    doFirst {
        if (repository.name == "centralStaging") {
            check(!providers.environmentVariable("SIGNING_KEY").orNull.isNullOrBlank()) {
                "Central staging requires SIGNING_KEY; use adapterTests or publishToMavenLocal for unsigned builds"
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // The published-artifact test resolves `id("io.github.graphmaquina") version …` from the file repository, so this
    // build's own plugin jar and its marker must be there before the tests start.
    dependsOn(tasks.named("publishAllPublicationsToAdapterTestsRepository"))
    systemProperty(
        "graphmaquina.fixtureRepository",
        layout.projectDirectory.dir("../build/adapter-test-repository").asFile.absolutePath
    )
}
