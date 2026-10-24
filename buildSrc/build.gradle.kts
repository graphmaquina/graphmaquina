plugins { `kotlin-dsl` }
repositories { mavenCentral() }

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test { useJUnitPlatform() }
// Gradle 9 builds only this jar for the main build, so the tests of the build logic run as its finalizer.
tasks.jar { finalizedBy(tasks.test) }
