plugins {
    id("stategraph.java-conventions")
    `maven-publish`
}

java {
    withSourcesJar()
}

tasks.jar {
    manifest { attributes("Automatic-Module-Name" to "io.stategraph.codegen") }
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("io/stategraph/codegen/version.properties") {
        expand("version" to project.version)
    }
}

dependencies {
    implementation(project(":stategraph-core"))
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
                name.set("stategraph-codegen")
                description.set("Pre-javac generator that resolves typed graph annotations and publishes deterministic compilation manifests")
            }
        }
    }
}
