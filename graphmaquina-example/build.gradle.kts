plugins {
    id("graphmaquina.java-conventions")
    id("io.github.graphmaquina")
}

dependencies {
    implementation(project(":graphmaquina-core"))
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    // Lombok first: the processor reads the accessors Lombok generates on Order and Item.
    annotationProcessor(project(":graphmaquina-processor"))
    add("graphmaquinaGenerator", project(":graphmaquina-codegen"))

    testImplementation(project(":graphmaquina-test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

// Goldens and contracts are compared from the class path and rewritten into the source tree, so update mode
// needs the source root resolved at configuration time — a test worker has no `project` to ask. An update run
// writes files rather than producing task outputs, so it must never be skipped as UP-TO-DATE nor served from
// the build cache.
val updateGoldens = providers.systemProperty("graphmaquina.goldens.update")
    .map { it.toBooleanStrict() }.orElse(false)
tasks.test {
    systemProperty("graphmaquina.goldens.update", updateGoldens.get().toString())
    systemProperty("graphmaquina.goldens.root",
        layout.projectDirectory.dir("src/test/resources").asFile.absolutePath)
    outputs.upToDateWhen { !updateGoldens.get() }
    outputs.doNotCacheIf("Golden update writes source resources") { updateGoldens.get() }
}
