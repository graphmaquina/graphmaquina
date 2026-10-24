plugins { `java-library` }

group = "io.github.graphmaquina"
// The version is never written into the repository: a release passes -PgraphmaquinaVersion and development
// derives it from git describe: the tag itself on a tagged commit, and on any other the most recent tag, the
// commits since it and the commit, such as 2.0.0-12-g03952fd, so a development build never takes the
// coordinates of a published release. A checkout without .git or without tags builds as 0.0.0-dev.
version = providers.gradleProperty("graphmaquinaVersion").orElse(
    providers.exec {
        commandLine("git", "describe", "--tags")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim().removePrefix("v").ifEmpty { "0.0.0-dev" } }
).get()

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

// Lombok is tooling for this repository's own sources, main and test alike: compile-only and run as an annotation
// processor, so it never reaches a published POM, Gradle module or runtime classpath, and a consumer never needs it.
val lombok = the<VersionCatalogsExtension>().named("libs").findLibrary("lombok").get()
dependencies {
    compileOnly(lombok)
    annotationProcessor(lombok)
    testCompileOnly(lombok)
    testAnnotationProcessor(lombok)
}

// Size and complexity checks run before every Java compilation; the script is shared with the included plugin build.
apply(from = rootDir.resolve("config/checkstyle/checkstyle.gradle.kts"))
// The documentation checks run before every Java compilation too; the root project registers the task.
tasks.withType<JavaCompile>().configureEach { dependsOn(":checkDocs") }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
    // Lombok claims only its own annotations, so the processing lint would report every other one, JUnit's and
    // Gradle's alike, as unclaimed; that one category is off.
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-Werror"))
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
    // Everything a release needs beyond the binary: the two extra jars, the signatures, and a staging
    // directory laid out as a Maven repository so the whole set can be zipped into a Central bundle.
    apply(plugin = "signing")
    tasks.withType<PublishToMavenRepository>().configureEach {
        doFirst {
            if (repository.name == "centralStaging") {
                check(!providers.environmentVariable("SIGNING_KEY").orNull.isNullOrBlank()) {
                    "Central staging requires SIGNING_KEY; use adapterTests or publishToMavenLocal for unsigned builds"
                }
            }
        }
    }
    extensions.configure<PublishingExtension> {
        repositories {
            maven {
                name = "centralStaging"
                url = rootProject.layout.buildDirectory.dir("central-staging").get().asFile.toURI()
            }
        }
    }
    extensions.configure<SigningExtension> {
        // The key is read from the environment, never from a keyring, so the same command works on a
        // developer machine and in CI. With no key set the build still runs; it just signs nothing.
        val key = providers.environmentVariable("SIGNING_KEY").orNull
        val password = providers.environmentVariable("SIGNING_PASSWORD").orElse("").get()
        isRequired = key != null
        if (key != null) {
            useInMemoryPgpKeys(key, password)
            sign(extensions.getByType<PublishingExtension>().publications)
        }
    }
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
