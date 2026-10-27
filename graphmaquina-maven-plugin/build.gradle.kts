plugins {
    id("graphmaquina.java-conventions")
    `maven-publish`
}

/**
 * The `mvn` launcher the integration tests drive: the one `MAVEN_HOME` or `M2_HOME` names, or the first one on
 * the `PATH`. Resolved while the build is configured, so a machine without Maven fails with the plain name and
 * the process error rather than with a Gradle configuration failure.
 */
fun mavenExecutable(): String {
    for (home in listOfNotNull(System.getenv("MAVEN_HOME"), System.getenv("M2_HOME"))) {
        val candidate = File(home, "bin/mvn")
        if (candidate.isFile) {
            return candidate.absolutePath
        }
    }
    for (entry in (System.getenv("PATH") ?: "").split(File.pathSeparator)) {
        val candidate = File(entry, "mvn")
        if (entry.isNotEmpty() && candidate.isFile) {
            return candidate.absolutePath
        }
    }
    return "mvn"
}

tasks.jar {
    manifest { attributes("Automatic-Module-Name" to "io.github.graphmaquina.maven") }
}

// The descriptor names the version of the plugin and of the generator it starts; `filter` replaces that token
// and nothing else, so Maven's own ${project}, ${session} and ${plugin.artifacts} expressions reach it intact.
tasks.processResources {
    inputs.property("graphmaquinaVersion", project.version.toString())
    // Read now: the filter runs while the task copies, when the configuration cache leaves it no project to ask.
    val release = project.version.toString()
    filesMatching("META-INF/maven/plugin.xml") {
        filter { line -> line.replace("@GRAPHMAQUINA_VERSION@", release) }
    }
}

dependencies {
    // Maven supplies its own API and core at execution time; neither may reach the published POM.
    compileOnly(libs.maven.plugin.api)
    compileOnly(libs.maven.core)
    // The generator is a dependency of the plugin, never of the project it runs for: it is a separate process
    // started over the plugin's own artifacts, and its classes are never loaded into this plugin's realm.
    runtimeOnly(project(":graphmaquina-codegen"))

    // Only the tests name diagnostic codes through core's DiagnosticCode; the plugin itself never loads library
    // classes.
    testImplementation(project(":graphmaquina-core"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // Only the tests decode the JSON the generator publishes; the plugin itself never loads codegen classes.
    testImplementation(libs.gson)
    testRuntimeOnly(libs.junit.launcher)
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
                packaging = "maven-plugin"
                name.set("graphmaquina-maven-plugin")
                description.set("Maven plugin that generates the StateGraph typed API and runs the verified"
                        + " compilation lifecycle around javac")
            }
        }
    }
}

val clearFixtureReleases = tasks.register<Delete>("clearFixtureReleases") {
    // Retain external dependencies but discard cached GraphMaquina artifacts so each test run consumes
    // this checkout's freshly staged publications, whether the development GAV is a release or snapshot.
    delete(layout.buildDirectory.dir("maven-fixture-repo/io/github/graphmaquina"))
}

tasks.test {
    // The integration tests run a real Maven build against the file repository, so every StateGraph artifact
    // that build resolves — this plugin included — must be published before they start.
    dependsOn(
        clearFixtureReleases,
        tasks.named("publishMavenPublicationToAdapterTestsRepository"),
        ":graphmaquina-core:publishMavenPublicationToAdapterTestsRepository",
        ":graphmaquina-codegen:publishMavenPublicationToAdapterTestsRepository",
        ":graphmaquina-processor:publishMavenPublicationToAdapterTestsRepository"
    )
    // Their files at this version only: the maven-metadata.xml beside them changes on every publication.
    val release = project.version.toString()
    inputs.files(listOf("graphmaquina-core", "graphmaquina-codegen", "graphmaquina-processor",
        "graphmaquina-maven-plugin").map {
        rootProject.layout.buildDirectory.dir("adapter-test-repository/io/github/graphmaquina/$it/$release")
    }).withPropertyName("fixtureReleaseArtifacts")
    systemProperty(
        "graphmaquina.fixtureRepository",
        rootProject.layout.buildDirectory.dir("adapter-test-repository").get().asFile.absolutePath
    )
    // The fixture publishes and resolves every StateGraph artifact at the version this build resolved.
    systemProperty("graphmaquina.version", project.version.toString())
    // One local repository for every fixture build, inside this module's build directory: the developer's
    // ~/.m2 is never read or written by these tests.
    systemProperty(
        "graphmaquina.mavenLocalRepository",
        layout.buildDirectory.dir("maven-fixture-repo").get().asFile.absolutePath
    )
    systemProperty("graphmaquina.mavenExecutable", mavenExecutable())
    // The cross-module scenarios, kept once in the processor's test resources as the package roots producer/ and
    // consumer/: the fixtures build them as a reactor and as a consumer of the producer's installed jar, so a change
    // to them reruns the tests.
    val crossModule = layout.projectDirectory.dir("../graphmaquina-processor/src/test/resources/cross-module")
    systemProperty("graphmaquina.crossModule", crossModule.asFile.absolutePath)
    inputs.dir(crossModule).withPropertyName("crossModuleScenarios")
}
