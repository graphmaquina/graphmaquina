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

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
