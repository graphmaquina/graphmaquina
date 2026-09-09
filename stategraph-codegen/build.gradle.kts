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

dependencies {
    implementation(project(":stategraph-core"))
    implementation(libs.gson)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

publishing {
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
