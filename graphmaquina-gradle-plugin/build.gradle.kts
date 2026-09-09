plugins {
    `java-gradle-plugin`
    `maven-publish`
}
group = "io.github.graphmaquina"
version = "0.3.0-SNAPSHOT"
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
publishing {
    // This is a separate included build, so it does not see the root build's convention plugin: the
    // POM metadata Central requires is repeated here rather than shared.
    publications.withType<MavenPublication>().configureEach {
        pom {
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
