plugins { id("stategraph.java-conventions") }

dependencies {
    implementation(project(":stategraph-core"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
