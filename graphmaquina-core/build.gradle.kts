plugins {
    id("graphmaquina.java-conventions")
    `maven-publish`
}

tasks.jar {
    manifest { attributes("Automatic-Module-Name" to "io.github.graphmaquina.core") }
}

tasks.test {
    // Single carrier for virtual threads: any pinning by monitor blocks and the test detects it.
    jvmArgs("-Djdk.virtualThreadScheduler.parallelism=1", "-Djdk.virtualThreadScheduler.maxPoolSize=1")
    // DiagnosticCodeTest reads the diagnostics table and the production sources of every module, so a change to any
    // of them reruns it instead of serving its result from the build cache. The modules are the root's directories
    // with a src/main/java, as the test finds them; nothing else under the root, such as worktrees, is walked.
    val repository = rootProject.layout.projectDirectory
    systemProperty("graphmaquina.repository", repository.asFile.absolutePath)
    inputs.file(repository.file("docs/diagnostics.md")).withPropertyName("diagnosticsTable")
    inputs.files(repository.asFile.listFiles().orEmpty().map { it.resolve("src/main/java") }.filter { it.isDirectory })
        .withPropertyName("productionSources")
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
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
                name.set("graphmaquina-core")
                description.set("State machines declared as graphs of identified transitions: cross-machine cascade,"
                        + " declared watches, sub-machines bound to states, async actions, call logs through"
                        + " System.Logger, a per-call flow of typed values with an optional timed trail")
            }
        }
    }
}
