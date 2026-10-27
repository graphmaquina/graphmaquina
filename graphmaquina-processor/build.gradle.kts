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

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    // The cross-module scenarios are kept once, as package roots under producer/ and consumer/: the tests compile and
    // run them, and the guides quote them, which CrossModuleGuideTest checks. A change to the scenarios or to a guide
    // reruns the tests.
    val crossModule = layout.projectDirectory.dir("src/test/resources/cross-module")
    val docs = rootProject.layout.projectDirectory.dir("docs")
    systemProperty("graphmaquina.crossModule", crossModule.asFile.absolutePath)
    systemProperty("graphmaquina.docs", docs.asFile.absolutePath)
    inputs.dir(crossModule).withPropertyName("crossModuleScenarios")
    inputs.files(docs.asFileTree.matching { include("*.md") }).withPropertyName("guides")
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
