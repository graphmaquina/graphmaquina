plugins {
    id("graphmaquina.java-conventions")
    `maven-publish`
}

tasks.jar {
    manifest { attributes("Automatic-Module-Name" to "io.github.graphmaquina.codegen") }
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("io/github/graphmaquina/codegen/version.properties") {
        expand("version" to project.version)
    }
}

dependencies {
    implementation(project(":graphmaquina-core"))
    implementation(libs.gson)

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
                name.set("graphmaquina-codegen")
                description.set("Pre-javac generator that resolves typed graph annotations and publishes deterministic compilation manifests")
            }
        }
    }
}
