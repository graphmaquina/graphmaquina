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

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
