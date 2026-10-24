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
// can see them: the smoke tests therefore run separately, after their artifacts are published to the
// local repository, with the repository and the release passed in as system properties.
tasks.test {
    exclude("**/PublishedArtifactSmokeTest.class", "**/ReadmeQuickstartTest.class",
        "**/GuideOutcomeFragmentsTest.class", "**/TestingGuideFragmentsTest.class")
}

val publishedArtifactTest = tasks.register<Test>("publishedArtifactTest") {
    group = "verification"
    description = "Compiles and runs a consumer against the published jars and POM alone"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/PublishedArtifactSmokeTest.class", "**/ReadmeQuickstartTest.class",
        "**/GuideOutcomeFragmentsTest.class", "**/TestingGuideFragmentsTest.class")
    dependsOn(tasks.named("testClasses"))
    dependsOn(tasks.named("publishMavenPublicationToAdapterTestsRepository"))
    dependsOn(":graphmaquina-core:publishMavenPublicationToAdapterTestsRepository")
    dependsOn(":graphmaquina-codegen:publishMavenPublicationToAdapterTestsRepository")
    dependsOn(":graphmaquina-processor:publishMavenPublicationToAdapterTestsRepository")
    // PublishedArtifactSmokeTest walks every module a release publishes, so the Maven plugin is staged here too.
    dependsOn(":graphmaquina-maven-plugin:publishMavenPublicationToAdapterTestsRepository")
    dependsOn(gradle.includedBuild("graphmaquina-gradle-plugin")
        .task(":publishAllPublicationsToAdapterTestsRepository"))
    // The quickstart, guide fragment and testing guide tests read their pages from this directory.
    val docs = rootProject.layout.projectDirectory.dir("docs")
    val gradleExecutable = requireNotNull(gradle.gradleHomeDir).resolve(
        if (System.getProperty("os.name").startsWith("Windows")) "bin/gradle.bat" else "bin/gradle"
    )
    systemProperty("docsPath", docs.asFile.absolutePath)
    systemProperty("gradleExecutable", gradleExecutable.absolutePath)
    inputs.files(docs.asFileTree.matching { include("*.md") }).withPropertyName("guidePages")
    inputs.property("gradleVersion", gradle.gradleVersion)
    val repository = rootProject.layout.buildDirectory.dir("adapter-test-repository")
    val release = project.version.toString()
    systemProperty("artifactRepository", repository.get().asFile.absolutePath)
    systemProperty("artifactVersion", release)
    inputs.property("artifactVersion", release)
    // Core/test, the generator, the processor, both plugins and the plugin marker POM, at this release only: the
    // maven-metadata.xml beside each changes on every publication.
    inputs.files(listOf("graphmaquina-core", "graphmaquina-test", "graphmaquina-codegen", "graphmaquina-processor",
        "graphmaquina-maven-plugin", "graphmaquina-gradle-plugin", "io.github.graphmaquina.gradle.plugin").map {
        repository.map { root -> root.dir("io/github/graphmaquina/$it/$release") }
    }).withPropertyName("publishedArtifacts")
}

tasks.check { dependsOn(publishedArtifactTest) }
