package io.github.graphmaquina.gradle;

import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Gradle plugin that generates the StateGraph typed API for each Java source set that asks for it, and wires
 * that source set's compilation to the lifecycle the generated vocabulary needs.
 *
 * <p>A source set asks for it by declaring {@code graphmaquina-processor} in its own annotation processor
 * configuration — {@code annotationProcessor}, {@code testAnnotationProcessor}, and so on. That is the one
 * declaration the cycle cannot work without: the processor is what publishes the index of what javac accepted,
 * so a source set compiled without it could never complete a compilation this plugin prepared. Reading the same
 * declaration as the switch means a project never has to repeat itself, and a source set that compiles plain
 * Java — a test source set holding only JUnit tests, typically — is left as the {@code java} plugin configured
 * it: no generated source root, no annotation manifest and no lifecycle around javac, so no compiler warning
 * about a processor that claims none of its annotations. The one thing every compilation of the project gets is
 * {@code options.isIncremental = false}, which is not a lazy property and therefore cannot wait for an answer
 * that is only known once the build script has been read.</p>
 *
 * <p>Per participating source set the plugin registers one {@link GenerateGraphApi} task and makes that source
 * set's {@code JavaCompile} compile what it generated, pass it the annotation manifest and run the two steps of
 * {@link CompilationLifecycle} around javac. The generation task is registered for every source set whether or
 * not it participates, so it is always there to be named and to be listed, and skips itself with the
 * declaration it is waiting for as its reason.</p>
 *
 * <p>Nothing of that is decided while the build is configured. The sources, the classpaths and the toolchain
 * are all providers; the declared dependencies are collected as they are declared rather than resolved; and
 * every piece of the compilation's wiring — the manifest argument, the two lifecycle actions, the sealed
 * inventory and the dependency on the generation task — is registered unconditionally and carries an empty
 * value when the source set takes no part. A build script that realizes {@code compileTestJava} before its own
 * {@code dependencies} block therefore gets the same answer as one that does not, instead of a compilation
 * generated for but never prepared. The generator itself only ever runs as a separate process.</p>
 */
public final class StateGraphPlugin implements Plugin<Project> {

    /** Name of the resolvable, non-consumable configuration carrying the generator's runtime classpath. */
    public static final String GENERATOR_CONFIGURATION_NAME = "graphmaquinaGenerator";

    /** Maven group and artifact id of the code generator, applied as the configuration's default dependency. */
    private static final String CODEGEN_COORDINATES = "io.github.graphmaquina:graphmaquina-codegen";

    /** The group and the artifact of the annotation processor a source set declares to take part. */
    private static final String PROCESSOR_GROUP = "io.github.graphmaquina";
    private static final String PROCESSOR_ARTIFACT = "graphmaquina-processor";

    /** The two of them, as the coordinates the participation switch is looked up by. */
    private static final String PROCESSOR_COORDINATES = PROCESSOR_GROUP + ':' + PROCESSOR_ARTIFACT;

    /**
     * The plugin's own version, used as the default {@code graphmaquina-codegen} version. Kept as a constant rather
     * than read from a packaged resource because TestKit's {@code withPluginClasspath()} loads plugin classes from
     * a classes directory, not a jar, so {@code Package.getImplementationVersion()} would return {@code null} there.
     */
    private static final String PLUGIN_VERSION = "1.0.1-SNAPSHOT";

    /** The file the compilation lifecycle seals once javac succeeded, registered as a compilation output. */
    private static final String SEALED_INVENTORY = "owned-outputs.json";

    /** The manifest the generator writes for the compilation that follows it. */
    private static final String ANNOTATION_MANIFEST = "annotation-manifest.json";

    /** The request every step of one source set's cycle reads. */
    private static final String REQUEST = "request.json";

    @Override
    public void apply(Project project) {
        project.getPlugins().withType(JavaPlugin.class, plugin -> configure(project));
    }

    private void configure(Project project) {
        StateGraphExtension extension = project.getExtensions().create("graphmaquina", StateGraphExtension.class);
        extension.getGeneratorVersion().convention(PLUGIN_VERSION);

        project.getConfigurations().register(GENERATOR_CONFIGURATION_NAME, configuration -> {
            configuration.setCanBeResolved(true);
            configuration.setCanBeConsumed(false);
            configuration.defaultDependencies(dependencies -> dependencies.add(project.getDependencies()
                    .create(CODEGEN_COORDINATES + ":" + extension.getGeneratorVersion().get())));
        });

        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        JavaToolchainService toolchains = project.getExtensions().getByType(JavaToolchainService.class);
        Provider<JavaLauncher> launcher = toolchains.launcherFor(java.getToolchain());
        java.getSourceSets().configureEach(sourceSet -> wire(project, sourceSet, launcher));
    }

    /** Registers the generation task of {@code sourceSet} and gives its compilation the lifecycle it needs. */
    private void wire(Project project, SourceSet sourceSet, Provider<JavaLauncher> launcher) {
        ObjectFactory objects = project.getObjects();
        Provider<Boolean> participates = participates(project, sourceSet);
        TaskProvider<JavaCompile> compile =
                project.getTasks().named(sourceSet.getCompileJavaTaskName(), JavaCompile.class);
        Provider<Directory> sourcesOutput = project.getLayout().getBuildDirectory()
                .dir("generated/sources/graphmaquina/" + sourceSet.getName());
        Provider<Directory> workDirectory = project.getLayout().getBuildDirectory()
                .dir("graphmaquina/" + sourceSet.getName());
        // Location only: these belong to the compiler and are read while it has not run yet.
        Provider<Directory> classesOutput =
                compile.flatMap(task -> task.getDestinationDirectory().getLocationOnly());
        Provider<Directory> processorSourcesOutput = compile.flatMap(task ->
                task.getOptions().getGeneratedSourceOutputDirectory().getLocationOnly());
        NamedDomainObjectProvider<Configuration> generatorClasspath =
                project.getConfigurations().named(GENERATOR_CONFIGURATION_NAME);

        TaskProvider<GenerateGraphApi> generate = project.getTasks().register(
                sourceSet.getTaskName("generate", "StateGraphApi"), GenerateGraphApi.class, task -> {
            task.setGroup("graphmaquina");
            task.setDescription("Generates the StateGraph API sources for the '" + sourceSet.getName()
                    + "' source set.");
            task.getParticipates().set(participates);
            task.onlyIf(declaresProcessor(sourceSet), new Participation());
            task.getSources().from(project.provider(
                    () -> originalSourceDirectories(sourceSet, sourcesOutput, processorSourcesOutput)));
            task.getApplicationClasspath().from(sourceSet.getCompileClasspath()
                    .filter(entry -> !entry.equals(classesOutput.get().getAsFile())));
            task.getGeneratorClasspath().from(generatorClasspath);
            task.getProjectDirectory().set(project.getLayout().getProjectDirectory());
            task.getSourcesOutput().set(sourcesOutput);
            task.getProcessorSourcesOutput().set(processorSourcesOutput);
            task.getClassesOutput().set(classesOutput);
            task.getCompilationOutputLocations().set(locations(classesOutput, processorSourcesOutput));
            task.getWorkDirectory().set(workDirectory);
            task.getCompilationId().set(compilationId(project, sourceSet));
            task.getJavaLauncher().set(launcher);
            task.getAnnotationManifest().set(workDirectory.map(work -> work.file(ANNOTATION_MANIFEST)));
            task.getRequestFile().set(workDirectory.map(work -> work.file(REQUEST)));
        });

        // The generated vocabulary is a source root of the source set, not extra source handed to one task:
        // that is what javadoc, a sources jar and an IDE import all read. The directory is contributed through
        // a collection that is empty until the source set declares the processor, so a source set that takes no
        // part keeps exactly the roots the java plugin gave it, and the dependency on the generation task
        // travels with the directory to every consumer of the source set.
        ConfigurableFileCollection produced = objects.fileCollection();
        produced.from(sourcesOutput);
        produced.builtBy(generate);
        FileCollection none = objects.fileCollection();
        ConfigurableFileCollection generated = objects.fileCollection();
        generated.from(project.provider(() -> Boolean.TRUE.equals(participates.get()) ? produced : none));
        sourceSet.getJava().srcDir(generated);

        // Every one of these is a file collection that is empty while the source set takes no part, and each is
        // read when the task graph is built or when the compilation runs — never while the build script is
        // still being read. That is what keeps a compilation that realizes early from being generated for
        // without being prepared.
        FileCollection manifest = whenParticipating(project, participates,
                generate.flatMap(GenerateGraphApi::getAnnotationManifest));
        FileCollection request = whenParticipating(project, participates,
                generate.flatMap(GenerateGraphApi::getRequestFile));
        FileCollection sealedInventory = whenParticipating(project, participates,
                workDirectory.map(work -> work.file(SEALED_INVENTORY)));
        FileCollection lifecycleClasspath = whenParticipating(project, participates, generatorClasspath);

        compile.configure(task -> {
            ManifestArguments manifestArguments = objects.newInstance(ManifestArguments.class);
            manifestArguments.getManifest().from(manifest);
            CompilationLifecycle.PrepareCompilation prepare =
                    objects.newInstance(CompilationLifecycle.PrepareCompilation.class);
            CompilationLifecycle.CompleteCompilation complete =
                    objects.newInstance(CompilationLifecycle.CompleteCompilation.class);
            for (CompilationLifecycle.LifecycleAction action : List.of(prepare, complete)) {
                action.getJavaLauncher().set(launcher);
                action.getGeneratorClasspath().from(lifecycleClasspath);
                action.getRequestFile().from(request);
            }
            task.dependsOn((Callable<Object>) () ->
                    Boolean.TRUE.equals(participates.get()) ? generate : List.of());
            // The completion step enumerates every class file this compilation produced, which an incremental
            // recompilation of a subset would leave incomplete. Whether this compilation runs that step is only
            // known once the build script has been read, and this switch is not a lazy property, so it is set
            // for every compilation of a project that applies the plugin.
            task.getOptions().setIncremental(false);
            task.getOptions().getCompilerArgumentProviders().add(manifestArguments);
            // Only the sealed inventory, never the whole work directory: the manifest is the generation's own
            // output and re-snapshotting it here would make every build regenerate.
            task.getOutputs().files(sealedInventory).withPropertyName("graphmaquinaOwnedOutputs");
            task.doFirst(prepare);
            task.doLast(complete);
        });
    }

    /**
     * A collection holding what {@code source} names while {@code participates} is true, and nothing otherwise.
     *
     * <p>The condition is a provider, so it is asked when the collection is resolved — while the task graph is
     * built, or when the task runs — and never while the plugin is applied. An empty collection carries no
     * dependency on the task that would have produced the file either, which is what keeps a generation out of
     * the graph of a source set that takes no part, and it resolves nothing it wraps, so a project where no
     * source set takes part never resolves the generator's classpath.</p>
     */
    private static FileCollection whenParticipating(Project project, Provider<Boolean> participates,
            Object source) {
        ObjectFactory objects = project.getObjects();
        FileCollection present = objects.fileCollection().from(source);
        FileCollection none = objects.fileCollection();
        ConfigurableFileCollection conditional = objects.fileCollection();
        conditional.from(project.provider(() -> Boolean.TRUE.equals(participates.get()) ? present : none));
        return conditional;
    }

    /**
     * Whether {@code sourceSet} takes part, read from the dependencies its annotation processor configuration
     * declares.
     *
     * <p>The coordinates are collected as the build script declares them, one plain {@code group:name} string
     * per dependency, so the answer is a value and not a live view of a configuration: it is complete once the
     * build script has been read, it resolves nothing, and it is the same answer whenever it is asked. The
     * provider is queried when a task runs or when the task graph is built, never while the plugin is applied,
     * because the declaration it reads may be the last line of the build script.</p>
     */
    private static Provider<Boolean> participates(Project project, SourceSet sourceSet) {
        Configuration processors = project.getConfigurations()
                .getByName(sourceSet.getAnnotationProcessorConfigurationName());
        SetProperty<String> declared = project.getObjects().setProperty(String.class);
        processors.getAllDependencies().all(dependency -> declared.add(coordinates(dependency)));
        return declared.map(coordinates -> coordinates.contains(PROCESSOR_COORDINATES));
    }

    /**
     * How one declared dependency is named for that check: {@code group:name}, except that a project dependency
     * answers with the StateGraph group, since a project named {@code graphmaquina-processor} is this very
     * processor built from source, whatever group the build including it declares.
     */
    private static String coordinates(Dependency dependency) {
        String group = dependency instanceof ProjectDependency ? PROCESSOR_GROUP : dependency.getGroup();
        return group + ':' + dependency.getName();
    }

    /** The condition a skipped generation task names, which is the declaration it is waiting for. */
    private static String declaresProcessor(SourceSet sourceSet) {
        return "the " + sourceSet.getName() + " source set declares " + PROCESSOR_COORDINATES + " in "
                + sourceSet.getAnnotationProcessorConfigurationName();
    }

    /**
     * The two output roots of the compilation the request names, as one value. They are locations of the
     * compiler's own outputs rather than of this task's, so a build that moves either of them must still write
     * the request again; naming them here is what makes that a change of an input.
     */
    private static Provider<String> locations(Provider<Directory> classesOutput,
            Provider<Directory> processorSourcesOutput) {
        return classesOutput.flatMap(classes -> processorSourcesOutput.map(processorSources ->
                classes.getAsFile().getAbsolutePath() + File.pathSeparator
                        + processorSources.getAsFile().getAbsolutePath()));
    }

    /**
     * The identity of one compilation, {@code group:name:sourceSet}, or {@code name:sourceSet} when the project
     * declares no group. It is read here, while the task is being configured, so the value is the one the build
     * script settled on and no {@code Project} reaches the task.
     */
    private static String compilationId(Project project, SourceSet sourceSet) {
        String group = String.valueOf(project.getGroup());
        return group.isEmpty()
                ? project.getName() + ':' + sourceSet.getName()
                : group + ':' + project.getName() + ':' + sourceSet.getName();
    }

    /**
     * The original source directories of {@code sourceSet}: everything it declares except the two directories
     * this build generates into. The result is a plain set of files rather than a view over the source set, so
     * that the generation task's own input never inherits the dependency the source set now carries on that
     * very task, which would be a cycle.
     */
    private static Set<File> originalSourceDirectories(SourceSet sourceSet, Provider<Directory> sourcesOutput,
            Provider<Directory> processorSourcesOutput) {
        Set<File> directories = new LinkedHashSet<>(sourceSet.getJava().getSrcDirs());
        directories.remove(sourcesOutput.get().getAsFile());
        directories.remove(processorSourcesOutput.get().getAsFile());
        return directories;
    }

    /**
     * The {@code onlyIf} of a generation task: it reads the participation the task itself carries and captures
     * nothing, so the condition holds no reference to the project that configured it.
     */
    private static final class Participation implements Spec<Task> {

        @Override
        public boolean isSatisfiedBy(Task task) {
            return ((GenerateGraphApi) task).getParticipates().get();
        }
    }
}
