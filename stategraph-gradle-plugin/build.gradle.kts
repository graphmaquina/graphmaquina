plugins {
    `java-gradle-plugin`
    `maven-publish`
}
group = "io.stategraph"
version = "0.3.0-SNAPSHOT"
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial", "-Werror"))
}
gradlePlugin {
    plugins {
        create("stategraph") {
            id = "io.stategraph"
            implementationClass = "io.stategraph.gradle.StateGraphPlugin"
        }
    }
}
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(gradleTestKit())
    // Only the tests decode the JSON the generator publishes; the plugin itself never loads codegen classes.
    testImplementation("com.google.code.gson:gson:2.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
publishing {
    // The same file repository the sibling projects publish core, codegen and the processor to, so a fixture
    // build can resolve the plugin and its marker the way any consumer resolves them from a repository.
    repositories {
        maven {
            name = "adapterTests"
            url = layout.projectDirectory.dir("../build/adapter-test-repository").asFile.toURI()
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // The published-artifact test resolves `id("io.stategraph") version …` from the file repository, so this
    // build's own plugin jar and its marker must be there before the tests start.
    dependsOn(tasks.named("publishAllPublicationsToAdapterTestsRepository"))
    systemProperty(
        "stategraph.fixtureRepository",
        layout.projectDirectory.dir("../build/adapter-test-repository").asFile.absolutePath
    )
}
