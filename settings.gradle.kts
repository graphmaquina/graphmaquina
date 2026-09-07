rootProject.name = "stategraph"
include("stategraph-core", "stategraph-example")

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories { mavenCentral() }
}
