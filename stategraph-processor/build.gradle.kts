plugins {
    id("stategraph.java-conventions")
    `maven-publish`
}

java {
    withSourcesJar()
}

tasks.jar {
    manifest { attributes("Automatic-Module-Name" to "io.stategraph.processor") }
}

dependencies {
    implementation(project(":stategraph-core"))
    implementation(project(":stategraph-codegen"))
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

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
                name.set("stategraph-processor")
                description.set("Annotation processor that turns @GenerateGraphApi machines and their generated typed vocabulary into graph definitions, facades and builders")
            }
        }
    }
}
