package io.stategraph.gradle;

import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.Directory;
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
import java.util.List;

/**
 * Gradle plugin that generates the StateGraph typed API for each Java source set of the applying project and
 * wires its compilation to the lifecycle the generated vocabulary needs.
 *
 * <p>Per source set the plugin registers one {@link GenerateGraphApi} task and makes that source set's
 * {@code JavaCompile} depend on it, compile what it generated, pass it the annotation manifest and run the two
 * steps of {@link CompilationLifecycle} around javac. Nothing is resolved while the build is configured: the
 * sources, the classpaths and the toolchain are all providers, and the generator itself only ever runs as a
 * separate process.</p>
 */
public final class StateGraphPlugin implements Plugin<Project> {

    /** Name of the resolvable, non-consumable configuration carrying the generator's runtime classpath. */
    public static final String GENERATOR_CONFIGURATION_NAME = "stategraphGenerator";

    /** Maven group and artifact id of the code generator, applied as the configuration's default dependency. */
    private static final String CODEGEN_COORDINATES = "io.stategraph:stategraph-codegen";

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
            task.getSources().from(sourceSet.getJava().getSourceDirectories()
                    .filter(directory -> !isGenerated(directory, sourcesOutput, processorSourcesOutput)));
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

        ObjectFactory objects = project.getObjects();
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

        compile.configure(task -> {
            task.dependsOn(generate);
            task.source(generate.flatMap(GenerateGraphApi::getSourcesOutput));
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

    /** Whether {@code directory} is one of the two directories this build generates sources into. */
    private static boolean isGenerated(File directory, Provider<Directory> sourcesOutput,
            Provider<Directory> processorSourcesOutput) {
        return directory.equals(sourcesOutput.get().getAsFile())
                || directory.equals(processorSourcesOutput.get().getAsFile());
    }
}
