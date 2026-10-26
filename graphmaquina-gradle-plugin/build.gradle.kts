plugins {
    `java-gradle-plugin`
    `maven-publish`
    signing
}
// This build cannot see buildSrc, so it applies the shared Checkstyle and delombok scripts directly.
apply(from = "../config/checkstyle/checkstyle.gradle.kts")
apply(from = "../config/lombok/delombok.gradle.kts")
group = "io.github.graphmaquina"
// Same resolution as the root build's convention plugin; this build is separate and cannot see buildSrc,
// so the six lines are repeated here for the same reason the POM metadata below is.
version = providers.gradleProperty("graphmaquinaVersion").orElse(
    providers.exec {
        commandLine("git", "describe", "--tags")
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
    // Same lint as the convention plugin: Lombok claims only its own annotations, so processing is off.
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-Werror"))
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
// The plugin reports the version it was built at; `filter` replaces that one token and nothing else.
tasks.processResources {
    inputs.property("graphmaquinaVersion", project.version.toString())
    // Read now: the filter runs while the task copies, when the configuration cache leaves it no project to ask.
    val release = project.version.toString()
    filesMatching("io/github/graphmaquina/gradle/version.properties") {
        filter { line -> line.replace("@GRAPHMAQUINA_VERSION@", release) }
    }
}
// The siblings that only the root build publishes, as it stages them in the adapter-test repository at this build's
// version: the fixtures resolve all of them, and the tests compile against core's jar.
val siblings = listOf("graphmaquina-core", "graphmaquina-codegen", "graphmaquina-processor")
val stagedRelease = project.version.toString()
val stagedRoot = layout.projectDirectory.dir("../build/adapter-test-repository/io/github/graphmaquina")
val stagedJars = siblings.associateWith { stagedRoot.file("$it/$stagedRelease/$it-$stagedRelease.jar").asFile }

// From the root build, the task depends on the publications of `modules`; the provider defers the lookup until the
// root's projects are configured, after this build. Alone, with -p, there is no root build and they must already be
// staged.
fun Task.dependsOnPublicationOf(modules: List<String>) {
    gradle.parent?.let { root ->
        dependsOn(provider {
            modules.map {
                root.rootProject.project(":$it").tasks.named("publishMavenPublicationToAdapterTestsRepository").get()
            }
        })
    }
}

// The task refuses to start while the jar of any of `modules` is not staged, and says what stages it.
fun Task.requireStaged(modules: List<String>) {
    // Locals for the action: the configuration cache cannot store an action that holds this script.
    val jars = modules.associateWith { stagedJars.getValue(it) }
    val release = stagedRelease
    doFirst {
        val missing = jars.filterValues { !it.isFile }.keys
        check(missing.isEmpty()) {
            "${missing.joinToString()} $release not staged in build/adapter-test-repository; run ./gradlew " +
                modules.joinToString(" ") { ":$it:publishMavenPublicationToAdapterTestsRepository" } + " first"
        }
    }
}

dependencies {
    // Lombok is tooling for this build's own sources: compile-only, so the published plugin never depends on it.
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(gradleTestKit())
    // Only the tests decode the JSON the generator publishes; the plugin itself never loads codegen classes.
    testImplementation(libs.gson)
    // Only the tests name diagnostic codes through core's DiagnosticCode, from the jar the sibling build stages for
    // the fixtures; the published plugin depends on no library class.
    testImplementation(files(stagedJars.getValue("graphmaquina-core")))
    testRuntimeOnly(libs.junit.launcher)
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
    // Both read here, as in the convention plugin, into locals: the configuration cache can store neither an
    // action that asks the project nor one that holds this script.
    val signingKey = providers.environmentVariable("SIGNING_KEY")
    val staging = name.endsWith("ToCentralStagingRepository")
    doFirst {
        if (staging) {
            check(!signingKey.orNull.isNullOrBlank()) {
                "Central staging requires SIGNING_KEY; use adapterTests or publishToMavenLocal for unsigned builds"
            }
        }
    }
}

tasks.compileTestJava {
    // The tests compile against the staged core jar, so the root build stages it first.
    dependsOnPublicationOf(listOf("graphmaquina-core"))
    requireStaged(listOf("graphmaquina-core"))
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
    // The fixture publishes and resolves every StateGraph artifact at the version this build resolved.
    systemProperty("graphmaquina.version", project.version.toString())
    // The fixture projects build with the Lombok and JUnit versions of this build's own catalog.
    systemProperty("graphmaquina.lombokVersion", libs.versions.lombok.get())
    systemProperty("graphmaquina.junitVersion", libs.versions.junit.get())
    // The fixtures also resolve core, the generator and the processor, which only the root build publishes.
    dependsOnPublicationOf(siblings)
    // Their files at this version, so a change to any of them within one commit runs the tests again. Not the module
    // directories: their maven-metadata.xml changes on every publication.
    inputs.files(siblings.map { stagedRoot.dir("$it/$stagedRelease") }).withPropertyName("siblingArtifacts")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    requireStaged(siblings)
}
