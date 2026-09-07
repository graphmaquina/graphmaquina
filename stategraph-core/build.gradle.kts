plugins { id("stategraph.java-conventions") }

tasks.jar {
    manifest { attributes("Automatic-Module-Name" to "io.stategraph.core") }
}

tasks.test {
    // Un solo carrier para hilos virtuales: cualquier pinning por monitor bloquea y el test lo delata.
    jvmArgs("-Djdk.virtualThreadScheduler.parallelism=1", "-Djdk.virtualThreadScheduler.maxPoolSize=1")
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
