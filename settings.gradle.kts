pluginManagement { includeBuild("graphmaquina-gradle-plugin") }

rootProject.name = "graphmaquina"
include("graphmaquina-core", "graphmaquina-processor", "graphmaquina-codegen", "graphmaquina-example",
        "graphmaquina-maven-plugin")

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories { mavenCentral() }
}
