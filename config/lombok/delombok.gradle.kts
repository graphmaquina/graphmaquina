// The published javadoc jar documents what the binary jar holds, so javadoc reads the main sources as Lombok expands
// them: the accessors and constructors Lombok generates appear like hand-written ones, with the Javadoc of their
// fields. Shared by the root build's convention plugin and the included plugin build, like the Checkstyle script;
// applied to a project that already has the java plugin and Lombok on its annotation processor path. The sources jar
// keeps the sources as written, so they match the line numbers of the binary jar.
val main = the<SourceSetContainer>()["main"]
val delombok = tasks.register<JavaExec>("delombok") {
    description = "Expands the Lombok annotations of the main sources for javadoc"
    val target = layout.buildDirectory.dir("delombok")
    val sources = main.java.sourceDirectories
    val compileClasspath = main.compileClasspath
    inputs.files(sources).withPropertyName("sources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(compileClasspath).withPropertyName("compileClasspath").withNormalizer(ClasspathNormalizer::class)
    outputs.dir(target).withPropertyName("target")
    classpath = configurations[main.annotationProcessorConfigurationName]
    mainClass = "lombok.launch.Main"
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("delombok", "--quiet", "--encoding=UTF-8", "--target=" + target.get().asFile,
            "--classpath=" + compileClasspath.asPath) + sources.filter { it.isDirectory }.map { it.path }
    })
    // A source deleted since the last run must not linger in the documentation.
    doFirst { target.get().asFile.deleteRecursively() }
}
tasks.named<Javadoc>("javadoc") { setSource(delombok) }
