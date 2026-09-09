plugins {
    id("stategraph.java-conventions")
    id("io.stategraph")
}

dependencies {
    implementation(project(":stategraph-core"))
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    // Lombok first: the processor reads the accessors Lombok generates on Order and Item.
    annotationProcessor(project(":stategraph-processor"))
    add("stategraphGenerator", project(":stategraph-codegen"))
    // The test source set has its own generation cycle, so it needs the processor that completes it.
    testAnnotationProcessor(project(":stategraph-processor"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

// The tests declare no graph, so the processor the source set's cycle needs claims none of JUnit's annotations.
tasks.named<JavaCompile>("compileTestJava") {
    options.compilerArgs.add("-Xlint:-processing")
}
