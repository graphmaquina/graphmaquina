plugins { id("stategraph.java-conventions") }

dependencies {
    implementation(project(":stategraph-core"))
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
