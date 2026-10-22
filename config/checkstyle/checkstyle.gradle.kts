// Size and complexity wall shared by the root build's convention plugin and the included Gradle plugin build:
// each main and test source set is checked against its own profile (main.xml, test.xml, next to this script)
// before javac runs, on the hand-written sources only. Applied with apply(from = ...) to a project that already
// has the java plugin. The empty classpath keeps Checkstyle independent of compiled classes, which is what lets
// compileJava depend on it without a circular dependency, and the explicit source directory keeps generated
// sources out of the check and out of its task dependencies.
val rules: File = buildscript.sourceFile!!.parentFile
apply<CheckstylePlugin>()
configure<CheckstyleExtension> { toolVersion = "14.3.0" }
the<SourceSetContainer>().configureEach {
    val set: SourceSet = this
    if (set.name == "main" || set.name == "test") {
        val check = tasks.named<Checkstyle>(set.getTaskName("checkstyle", null)) {
            configFile = rules.resolve("${set.name}.xml")
            classpath = files()
            setSource(layout.projectDirectory.dir("src/${set.name}/java"))
            include("**/*.java")
        }
        tasks.named(set.compileJavaTaskName) { dependsOn(check) }
    }
}
