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
