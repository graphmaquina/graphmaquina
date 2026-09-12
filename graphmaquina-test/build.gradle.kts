plugins {
    id("graphmaquina.java-conventions")
    `maven-publish`
}
dependencies {
    api(project(":graphmaquina-core"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
tasks.jar {
    manifest { attributes("Automatic-Module-Name" to "io.github.graphmaquina.test") }
}
publishing {
    repositories {
        maven {
            name = "adapterTests"
            url = rootProject.layout.buildDirectory.dir("adapter-test-repository").get().asFile.toURI()
        }
    }
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("graphmaquina-test")
                description.set("Typed assertions, probes and graph snapshots for testing GraphMaquina machines")
            }
        }
    }
}

// The published jar and POM are what a consumer actually gets, and nothing the normal test task runs
// can see them: the smoke test therefore runs on its own, after the two artifacts are published to the
// local repository, with the repository and the release passed in as system properties.
tasks.test { exclude("**/PublishedArtifactSmokeTest.class") }

val publishedArtifactTest = tasks.register<Test>("publishedArtifactTest") {
    group = "verification"
    description = "Compiles and runs a consumer against the published jars and POM alone"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/PublishedArtifactSmokeTest.class")
    dependsOn(tasks.named("testClasses"))
    dependsOn(tasks.named("publishMavenPublicationToAdapterTestsRepository"))
    dependsOn(":graphmaquina-core:publishMavenPublicationToAdapterTestsRepository")
    val repository = rootProject.layout.buildDirectory.dir("adapter-test-repository")
    val release = project.version.toString()
    systemProperty("artifactRepository", repository.get().asFile.absolutePath)
    systemProperty("artifactVersion", release)
    inputs.property("artifactVersion", release)
    inputs.files(repository.map { directory ->
        listOf("graphmaquina-core", "graphmaquina-test").flatMap { artifact ->
            listOf(".jar", ".pom", "-sources.jar", "-javadoc.jar").map { suffix ->
                directory.file("io/github/graphmaquina/$artifact/$release/$artifact-$release$suffix").asFile
            }
        }
    }).withPropertyName("publishedArtifacts")
}

tasks.check { dependsOn(publishedArtifactTest) }
