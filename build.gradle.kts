// The Central Portal takes one zip whose entries are Maven repository paths. Every module publishes into
// build/central-staging with `publishAllPublicationsToCentralStagingRepository`; this packs that directory.
tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Packs build/central-staging into the zip the Central Portal upload endpoint accepts"
    from(layout.buildDirectory.dir("central-staging"))
    // Maven-metadata files describe a repository, not a release, and Central rejects a bundle carrying them.
    exclude("**/maven-metadata*")
    archiveFileName = "graphmaquina-central-bundle.zip"
    destinationDirectory = layout.buildDirectory
}
