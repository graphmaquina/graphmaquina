plugins {
    id("stategraph.java-conventions")
    `maven-publish`
}

java {
    withSourcesJar()
}

tasks.jar {
    manifest { attributes("Automatic-Module-Name" to "io.stategraph.core") }
}

tasks.test {
    // Single carrier for virtual threads: any pinning by monitor blocks and the test detects it.
    jvmArgs("-Djdk.virtualThreadScheduler.parallelism=1", "-Djdk.virtualThreadScheduler.maxPoolSize=1")
}

dependencies {
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("stategraph-core")
                description.set("State machines declared as graphs: exhaustive N×N matrix, cross-machine cascade, declared watches, async actions")
            }
        }
    }
}
