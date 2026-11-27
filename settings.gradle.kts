pluginManagement { includeBuild("graphmaquina-gradle-plugin") }

rootProject.name = "graphmaquina"
include("graphmaquina-core", "graphmaquina-processor", "graphmaquina-codegen", "graphmaquina-example",
        "graphmaquina-maven-plugin", "graphmaquina-test")
// The Rust kernels need cargo and the toolchain native/rust-toolchain.toml pins, so they join the build only on request.
if (providers.gradleProperty("graphmaquinaRust").isPresent) {
    include("graphmaquina-rust")
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories { mavenCentral() }
}
