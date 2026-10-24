plugins { `kotlin-dsl` }
repositories { mavenCentral() }

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
    // The link tests run the lychee on the PATH and are skipped without it, so its version is an input of the tests:
    // installing, upgrading or removing lychee runs them again instead of reusing an earlier result.
    inputs.property("lychee", provider {
        try {
            ProcessBuilder("lychee", "--version").start().inputStream.bufferedReader().readText().trim()
        } catch (absent: java.io.IOException) {
            "absent"
        }
    })
}
// Gradle 9 builds only this jar for the main build, so the tests of the build logic run as its finalizer.
tasks.jar { finalizedBy(tasks.test) }
