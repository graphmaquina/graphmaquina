package io.stategraph.gradle;

import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
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
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Gradle plugin that generates the StateGraph typed API for each Java source set that asks for it, and wires
 * that source set's compilation to the lifecycle the generated vocabulary needs.
 *
 * <p>A source set asks for it by declaring {@code stategraph-processor} in its own annotation processor
 * configuration — {@code annotationProcessor}, {@code testAnnotationProcessor}, and so on. That is the one
 * declaration the cycle cannot work without: the processor is what publishes the index of what javac accepted,
 * so a source set compiled without it could never complete a compilation this plugin prepared. Reading the same
 * declaration as the switch means a project never has to repeat itself, and a source set that compiles plain
 * Java — a test source set holding only JUnit tests, typically — is left exactly as the {@code java} plugin
 * configured it: no generated source root, no annotation manifest, no lifecycle around javac, and no compiler
 * warning about a processor that claims none of its annotations.</p>
 *
 * <p>Per participating source set the plugin registers one {@link GenerateGraphApi} task and makes that source
 * set's {@code JavaCompile} depend on it, compile what it generated, pass it the annotation manifest and run the
 * two steps of {@link CompilationLifecycle} around javac. The generation task is registered for every source set
 * whether or not it participates, so it is always there to be named and to be listed, and skips itself with the
 * declaration it is waiting for as its reason.</p>
 *
 * <p>Nothing is resolved while the build is configured: the sources, the classpaths and the toolchain are all
 * providers, the declared dependencies are read lazily rather than resolved, and the generator itself only ever
 * runs as a separate process.</p>
 */
public final class StateGraphPlugin implements Plugin<Project> {

    /** Name of the resolvable, non-consumable configuration carrying the generator's runtime classpath. */
    public static final String GENERATOR_CONFIGURATION_NAME = "stategraphGenerator";

    /** Maven group and artifact id of the code generator, applied as the configuration's default dependency. */
    private static final String CODEGEN_COORDINATES = "io.stategraph:stategraph-codegen";

    /** The group and the artifact of the annotation processor a source set declares to take part. */
    private static final String PROCESSOR_GROUP = "io.stategraph";
    private static final String PROCESSOR_ARTIFACT = "stategraph-processor";

    /**
     * The plugin's own version, used as the default {@code stategraph-codegen} version. Kept as a constant rather
     * than read from a packaged resource because TestKit's {@code withPluginClasspath()} loads plugin classes from
     * a classes directory, not a jar, so {@code Package.getImplementationVersion()} would return {@code null} there.
     */
    private static final String PLUGIN_VERSION = "0.3.0-SNAPSHOT";

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
        StateGraphExtension extension = project.getExtensions().create("stategraph", StateGraphExtension.class);
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
        Provider<Boolean> participates = participates(project, sourceSet);
        TaskProvider<JavaCompile> compile =
                project.getTasks().named(sourceSet.getCompileJavaTaskName(), JavaCompile.class);
        Provider<Directory> sourcesOutput = project.getLayout().getBuildDirectory()
                .dir("generated/sources/stategraph/" + sourceSet.getName());
        Provider<Directory> workDirectory = project.getLayout().getBuildDirectory()
                .dir("stategraph/" + sourceSet.getName());
        // Location only: these belong to the compiler and are read while it has not run yet.
        Provider<Directory> classesOutput =
                compile.flatMap(task -> task.getDestinationDirectory().getLocationOnly());
        Provider<Directory> processorSourcesOutput = compile.flatMap(task ->
                task.getOptions().getGeneratedSourceOutputDirectory().getLocationOnly());
        NamedDomainObjectProvider<Configuration> generatorClasspath =
                project.getConfigurations().named(GENERATOR_CONFIGURATION_NAME);

        TaskProvider<GenerateGraphApi> generate = project.getTasks().register(
                sourceSet.getTaskName("generate", "StateGraphApi"), GenerateGraphApi.class, task -> {
            task.setGroup("stategraph");
            task.setDescription("Generates the StateGraph API sources for the '" + sourceSet.getName()
                    + "' source set.");
            task.onlyIf(declaresProcessor(sourceSet), element -> participates.get());
            task.getSources().from(project.provider(
                    () -> originalSourceDirectories(sourceSet, sourcesOutput, processorSourcesOutput)));
            task.getApplicationClasspath().from(sourceSet.getCompileClasspath()
                    .filter(entry -> !entry.equals(classesOutput.get().getAsFile())));
            task.getGeneratorClasspath().from(generatorClasspath);
            task.getProjectDirectory().set(project.getLayout().getProjectDirectory());
            task.getSourcesOutput().set(sourcesOutput);
            task.getProcessorSourcesOutput().set(processorSourcesOutput);
            task.getClassesOutput().set(classesOutput);
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
        ObjectFactory objects = project.getObjects();
        ConfigurableFileCollection produced = objects.fileCollection();
        produced.from(sourcesOutput);
        produced.builtBy(generate);
        FileCollection none = objects.fileCollection();
        ConfigurableFileCollection generated = objects.fileCollection();
        generated.from(project.provider(() -> participates.get() ? produced : none));
        sourceSet.getJava().srcDir(generated);

        compile.configure(task -> {
            if (!Boolean.TRUE.equals(participates.get())) {
                return;
            }
            ManifestArguments manifestArguments = objects.newInstance(ManifestArguments.class);
            manifestArguments.getManifest().set(generate.flatMap(GenerateGraphApi::getAnnotationManifest));
            CompilationLifecycle.PrepareCompilation prepare =
                    objects.newInstance(CompilationLifecycle.PrepareCompilation.class);
            CompilationLifecycle.CompleteCompilation complete =
                    objects.newInstance(CompilationLifecycle.CompleteCompilation.class);
            for (CompilationLifecycle.LifecycleAction action : List.of(prepare, complete)) {
                action.getJavaLauncher().set(launcher);
                action.getGeneratorClasspath().from(generatorClasspath);
                action.getRequestFile().set(generate.flatMap(GenerateGraphApi::getRequestFile));
            }
            task.dependsOn(generate);
            // The completion step enumerates every class file this compilation produced, which an incremental
            // recompilation of a subset would leave incomplete.
            task.getOptions().setIncremental(false);
            task.getOptions().getCompilerArgumentProviders().add(manifestArguments);
            // Only the sealed inventory, never the whole work directory: the manifest is the generation's own
            // output and re-snapshotting it here would make every build regenerate.
            task.getOutputs().file(workDirectory.map(work -> work.file(SEALED_INVENTORY)))
                    .withPropertyName("stategraphOwnedOutputs");
            task.doFirst(prepare);
            task.doLast(complete);
        });
    }

    /**
     * Whether {@code sourceSet} takes part, read from the dependencies its annotation processor configuration
     * declares. The provider is queried when a task is realized or run, never while the plugin is applied, so
     * the answer is the one the build script settled on; and it reads the declared dependencies rather than
     * resolving the configuration, so asking the question resolves nothing.
     */
    private static Provider<Boolean> participates(Project project, SourceSet sourceSet) {
        Configuration processors = project.getConfigurations()
                .getByName(sourceSet.getAnnotationProcessorConfigurationName());
        return project.provider(() -> {
            for (Dependency dependency : processors.getAllDependencies()) {
                if (!PROCESSOR_ARTIFACT.equals(dependency.getName())) {
                    continue;
                }
                if (dependency instanceof ProjectDependency || PROCESSOR_GROUP.equals(dependency.getGroup())) {
                    return true;
                }
            }
            return false;
        });
    }

    /** The condition a skipped generation task names, which is the declaration it is waiting for. */
    private static String declaresProcessor(SourceSet sourceSet) {
        return "the " + sourceSet.getName() + " source set declares " + PROCESSOR_GROUP + ':'
                + PROCESSOR_ARTIFACT + " in " + sourceSet.getAnnotationProcessorConfigurationName();
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
}
