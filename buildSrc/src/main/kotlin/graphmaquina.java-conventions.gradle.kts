plugins { `java-library` }

group = "io.github.graphmaquina"
version = "0.3.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial", "-Werror"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}

// The metadata Maven Central requires of every published POM. It is identical for all modules, so it is
// declared once here and applied to whatever publications the module creates; only name and description,
// which differ per module, stay in the module's own build script.
pluginManager.withPlugin("maven-publish") {
    // Central rejects a release without a sources and a javadoc jar beside the binary.
    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
        withJavadocJar()
    }
    extensions.configure<PublishingExtension> {
        publications.withType<MavenPublication>().configureEach {
            pom {
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
    }
}
