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
    exclude("**/PublishedArtifactSmokeTest.class", "**/ReadmeQuickstartTest.class", "**/GraphOnlySurfaceTest.class",
        "**/GuideOutcomeFragmentsTest.class", "**/TestingGuideFragmentsTest.class")
}

val publishedArtifactTest = tasks.register<Test>("publishedArtifactTest") {
    group = "verification"
    description = "Compiles and runs a consumer against the published jars and POM alone"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/PublishedArtifactSmokeTest.class", "**/ReadmeQuickstartTest.class", "**/GraphOnlySurfaceTest.class",
        "**/GuideOutcomeFragmentsTest.class", "**/TestingGuideFragmentsTest.class")
    dependsOn(tasks.named("testClasses"))
    dependsOn(tasks.named("publishMavenPublicationToAdapterTestsRepository"))
    dependsOn(":graphmaquina-core:publishMavenPublicationToAdapterTestsRepository")
    dependsOn(":graphmaquina-codegen:publishMavenPublicationToAdapterTestsRepository")
    dependsOn(":graphmaquina-processor:publishMavenPublicationToAdapterTestsRepository")
    // GraphOnlySurfaceTest walks every module a release publishes, so the Maven plugin is staged here too.
    dependsOn(":graphmaquina-maven-plugin:publishMavenPublicationToAdapterTestsRepository")
    dependsOn(gradle.includedBuild("graphmaquina-gradle-plugin")
        .task(":publishAllPublicationsToAdapterTestsRepository"))
    val readme = rootProject.layout.projectDirectory.file("docs/guide.md")
    val gradleExecutable = requireNotNull(gradle.gradleHomeDir).resolve(
        if (System.getProperty("os.name").startsWith("Windows")) "bin/gradle.bat" else "bin/gradle"
    )
    systemProperty("readmePath", readme.asFile.absolutePath)
    systemProperty("gradleExecutable", gradleExecutable.absolutePath)
    inputs.file(readme).withPropertyName("readmeQuickstart")
    val testingGuide = rootProject.layout.projectDirectory.file("docs/testing.md")
    systemProperty("testingGuidePath", testingGuide.asFile.absolutePath)
    inputs.file(testingGuide).withPropertyName("testingGuide")
    inputs.property("gradleVersion", gradle.gradleVersion)
    val repository = rootProject.layout.buildDirectory.dir("adapter-test-repository")
    val release = project.version.toString()
    systemProperty("artifactRepository", repository.get().asFile.absolutePath)
    systemProperty("artifactVersion", release)
    inputs.property("artifactVersion", release)
    // Includes core/test, the generator, the processor, both plugins and the plugin marker POM.
    inputs.dir(repository).withPropertyName("publishedArtifacts")
}

tasks.check { dependsOn(publishedArtifactTest) }
