plugins { id("stategraph.java-conventions") }

tasks.jar {
    manifest { attributes("Automatic-Module-Name" to "io.stategraph.core") }
}

tasks.test {
    // Single carrier for virtual threads: any pinning by monitor blocks and the test detects it.
    jvmArgs("-Djdk.virtualThreadScheduler.parallelism=1", "-Djdk.virtualThreadScheduler.maxPoolSize=1")
}

dependencies {
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
