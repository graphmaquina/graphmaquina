plugins {
    id("graphmaquina.java-conventions")
    `maven-publish`
}

tasks.jar {
    manifest { attributes("Automatic-Module-Name" to "io.github.graphmaquina.processor") }
}

dependencies {
    implementation(project(":graphmaquina-core"))
    implementation(project(":graphmaquina-codegen"))
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
                name.set("graphmaquina-processor")
                description.set("Annotation processor that turns @GenerateGraphApi machines and their generated typed vocabulary into graph definitions, facades and builders")
            }
        }
    }
}
