package io.github.graphmaquina.gradle;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.javadoc.Javadoc;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Gradle plugin that generates the StateGraph typed API for each Java source set that asks for it, and wires
 * that source set's compilation to the lifecycle the generated vocabulary needs.
 *
 * <p>A source set asks for it by declaring {@code graphmaquina-processor} in its annotation processor
 * configuration — {@code annotationProcessor}, {@code testAnnotationProcessor}, and so on — or in a
 * configuration that one extends, as a module or a project dependency: a file dependency on its jar names no
 * coordinates and does not count. That is the one declaration the cycle cannot work without: the processor is
 * what publishes the index of what javac accepted, so a source set compiled without it could never complete a
 * compilation this plugin prepared. Reading the same declaration as the switch means a project never has to
 * repeat itself, and a source set that compiles plain Java — a test source set holding only JUnit tests,
 * typically — is left as the {@code java} plugin configured it: no generated source root, no annotation manifest
 * and no lifecycle around javac, so no compiler warning about a processor that claims none of its annotations.
 * The one thing the compilation of every source set gets is {@code options.isIncremental = false}, which is not
 * a lazy property and therefore cannot wait for an answer that is only known once the build script has been
 * read.</p>
 *
 * <p>The plugin registers one {@link GenerateGraphApi} task per source set and, for a participating one, makes
 * that source set's {@code JavaCompile} compile what it generated, pass it the annotation manifest and run the
 * two steps of {@link CompilationLifecycle} around javac. The generation task is registered whether or not the
 * source set participates, so it is always there to be named and to be listed, and skips itself with the
 * declaration it is waiting for as its reason.</p>
 *
 * <p>The generation parses the original sources javac compiles: the files of every source directory of the source
 * set but the generated ones, as the include and exclude patterns and the filter of the source set's java sources,
 * the include and exclude pattern strings of its compilation task and the default excludes of the build select
 * them, and it runs after every task those directories are built by.
 * A compilation task that compiles other original sources than those, through a filter it is given as a spec or a
 * closure, or a source added to it or put in place of the source set's, is refused before the generator runs, and
 * before the preparation when the generation is up to date. The generation parses the sources in the encoding javac
 * reads them in and at the release javac compiles them at, as the generation task's
 * {@link GenerateGraphApi#getEncoding() encoding} and {@link GenerateGraphApi#getRelease() release} describe.
 * What it generates joins the source set's java sources as a source directory set of its own, which the source set's
 * patterns do not filter, so javac compiles all of it unless a pattern of the compilation task leaves some of it out,
 * which the preparation refuses. The locations the generation task holds are the ones the compilation follows — the
 * source root it writes, and the work directory the manifest, the request and the sealed inventory live in — and so are
 * the launcher and the generator classpath it holds, which run the preparation and the completion too.
 * Each of them is a convention of the task, so a value a build sets for any of them wins whenever the build sets
 * it, and so does one it sets for whether the task takes part or for the compilation's output locations it names.
 * Paths a build adds to the generator classpath join the plugin's default only when they are added after the
 * plugin configured the task, and replace it when they are added before. The class output, the processor's source
 * output and the annotation manifest are derived instead: the first two stay the compiler's own, the manifest
 * follows the work directory, and a build that sets any of them on the generation task is refused.</p>
 *
 * <p>The compilation carrier the generator emits is tool-internal. The completion removes its class from the
 * class output, so the jar never carries it, and the plugin leaves its source out of the sources jar and the
 * API documentation of the source set, when the build has those tasks.</p>
 *
 * <p>Nothing of that is decided while the build is configured. The sources, the classpaths and the toolchain
 * are all providers; the declared dependencies are counted as they are declared and removed rather than
 * resolved, and the answer they give is settled when the task graph is built; and every piece of the
 * compilation's wiring — the manifest argument, the two lifecycle actions, the sealed inventory and the
 * dependency on the generation task — is registered unconditionally and carries an empty value when the source
 * set takes no part. A build script that realizes {@code compileTestJava} before its own {@code dependencies}
 * block therefore gets the same answer as one that does not, instead of a compilation generated for but never
 * prepared, and a processor that a {@code withDependencies} action only adds once javac's processor path is
 * resolved cannot switch the lifecycle on halfway through the build. The generator itself only ever runs as a
 * separate process.</p>
 */
public final class StateGraphPlugin implements Plugin<Project> {

    /** Name of the resolvable, non-consumable configuration carrying the generator's runtime classpath. */
    public static final String GENERATOR_CONFIGURATION_NAME = "graphmaquinaGenerator";

    /** Maven group and artifact id of the code generator, applied as the configuration's default dependency. */
    private static final String CODEGEN_COORDINATES = "io.github.graphmaquina:graphmaquina-codegen";

    /** The resource {@code processResources} filters this plugin's own version into. */
    private static final String VERSION_RESOURCE = "version.properties";

    /**
     * The plugin's own version, used as the default {@code graphmaquina-codegen} version. It is read from a
     * filtered resource rather than from the jar manifest: TestKit's {@code withPluginClasspath()} loads the
     * plugin from {@code build/classes/java/main} and {@code build/resources/main}, so
     * {@code Package.getImplementationVersion()} returns {@code null} there while the resource is present in
     * both a jar and a classes directory.
     *
     * @return the version this plugin was built at
     */
    static String pluginVersion() {
        Properties properties = new Properties();
        try (InputStream stream = StateGraphPlugin.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(VERSION_RESOURCE + " is missing from the plugin classpath");
            }
            properties.load(stream);
        } catch (IOException cause) {
            throw new IllegalStateException("Cannot read " + VERSION_RESOURCE, cause);
        }
        String version = properties.getProperty("version");
        if (version == null || version.isBlank()) {
            throw new IllegalStateException(VERSION_RESOURCE + " declares no version");
        }
        return version;
    }

    /** The file the compilation lifecycle seals once javac succeeded, registered as a compilation output. */
    private static final String SEALED_INVENTORY = "owned-outputs.json";

    /** The manifest the generator writes for the compilation that follows it. */
    private static final String ANNOTATION_MANIFEST = "annotation-manifest.json";

    /** The request every step of one source set's cycle reads. */
    private static final String REQUEST = "request.json";

    /** The package the generator emits the carrier of every compilation into, as a pattern of its sources. */
    private static final String CARRIER_SOURCES = "io/github/graphmaquina/generated/bootstrap/**";

    @Override
    public void apply(Project project) {
        project.getPlugins().withType(JavaPlugin.class, plugin -> configure(project));
    }

    private void configure(Project project) {
        StateGraphExtension extension = project.getExtensions().create("graphmaquina", StateGraphExtension.class);
        extension.getGeneratorVersion().convention(pluginVersion());

        project.getConfigurations().register(GENERATOR_CONFIGURATION_NAME, configuration -> {
            configuration.setCanBeResolved(true);
            configuration.setCanBeConsumed(false);
            configuration.defaultDependencies(dependencies -> dependencies.add(project.getDependencies()
                    .create(CODEGEN_COORDINATES + ":" + extension.getGeneratorVersion().get())));
        });

        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        java.getSourceSets().configureEach(sourceSet -> wire(project, sourceSet));
    }

    /** Registers the generation task of {@code sourceSet} and gives its compilation the lifecycle it needs. */
    private void wire(Project project, SourceSet sourceSet) {
        ProcessorDeclarations declarations = ProcessorDeclarations.of(project, sourceSet);
        Provider<Boolean> participates = project.provider(declarations::takesPart);
        TaskProvider<JavaCompile> compile =
                project.getTasks().named(sourceSet.getCompileJavaTaskName(), JavaCompile.class);
        TaskProvider<GenerateGraphApi> generate =
                registerGeneration(project, sourceSet, declarations, participates, compile);
        addGeneratedSources(project, sourceSet, participates, generate);
        wireLifecycle(project, declarations, participates, compile, generate);
        excludeCarrier(project, sourceSet);
    }

    /**
     * Registers the generation task of {@code sourceSet}, which generates the vocabulary {@code compile} compiles
     * from the original sources it compiles.
     */
    private static TaskProvider<GenerateGraphApi> registerGeneration(Project project, SourceSet sourceSet,
            ProcessorDeclarations declarations, Provider<Boolean> participates, TaskProvider<JavaCompile> compile) {
        // Location only: these belong to the compiler and are read while it has not run yet.
        Provider<Directory> classesOutput =
                compile.flatMap(task -> task.getDestinationDirectory().getLocationOnly());
        Provider<Directory> processorSourcesOutput = compile.flatMap(task ->
                task.getOptions().getGeneratedSourceOutputDirectory().getLocationOnly());
        NamedDomainObjectProvider<Configuration> generatorClasspath =
                project.getConfigurations().named(GENERATOR_CONFIGURATION_NAME);

        return project.getTasks().register(
                sourceSet.getTaskName("generate", "StateGraphApi"), GenerateGraphApi.class, task -> {
            task.setGroup("graphmaquina");
            task.setDescription("Generates the StateGraph API sources for the '" + sourceSet.getName()
                    + "' source set.");
            task.getParticipates().convention(participates);
            task.onlyIf(ProcessorDeclarations.declaresProcessor(sourceSet), new Participation());
            setConventions(task, project, sourceSet, compile);
            readOriginalSources(task, project, sourceSet, compile, declarations);
            task.getApplicationClasspath().from(sourceSet.getCompileClasspath()
                    .filter(entry -> !entry.equals(classesOutput.get().getAsFile())));
            // A convention too: a classpath the build sets replaces it, and paths the build adds once this action
            // has run join it.
            task.getGeneratorClasspath().convention(generatorClasspath);
            deriveLocations(task, compile, classesOutput, processorSourcesOutput);
        });
    }

    /**
     * Gives the generation task {@code task} of {@code sourceSet} the defaults a build may replace: the locations it
     * writes, the project directory, and the JDK, the identity, the release and the encoding of {@code compile}.
     */
    private static void setConventions(GenerateGraphApi task, Project project, SourceSet sourceSet,
            TaskProvider<JavaCompile> compile) {
        // Defaults a build may replace: conventions rather than values, so a value the build sets wins
        // whether it was set before this action ran — a block configuring every generation task ahead of a
        // source set that does not exist yet — or after it.
        task.getSourcesOutput().convention(project.getLayout().getBuildDirectory()
                .dir("generated/sources/graphmaquina/" + sourceSet.getName()));
        task.getWorkDirectory().convention(project.getLayout().getBuildDirectory()
                .dir("graphmaquina/" + sourceSet.getName()));
        task.getRequestFile().convention(task.getWorkDirectory().file(REQUEST));
        task.getProjectDirectory().convention(project.getLayout().getProjectDirectory());
        // The generator parses at the release javac compiles at, which only a JDK of that release or later
        // supports, so it runs on the very JDK javac compiles with: the installation of the compilation's
        // javaCompiler, which follows a javaHome or an executable Gradle forks javac from. It is read through a
        // plain provider, as the release below is.
        task.getJavaLauncher().convention(project.provider(() ->
                CompilerLauncher.of(compile.get().getJavaCompiler().get())));
        task.getCompilationId().convention(project.provider(() -> compilationId(project, sourceSet)));
        // The generator reads the sources in the encoding javac reads them in, and at the release javac compiles
        // at. Both are read through a plain provider: one derived from the compile task would carry that task as
        // its producer, and the generation, which the compilation depends on, would depend on it in turn. Both are
        // derived from options.compilerArgs alone: the compilation's argument providers may need files other
        // tasks write before it runs, and the preparation refuses providers that change either.
        task.getRelease().convention(project.provider(() -> CompilerSettings.release(compile.get(),
                CompilerSettings.declaredArguments(compile.get().getOptions()))));
        task.getEncoding().convention(project.provider(() -> CompilerSettings.encoding(compile.get(),
                CompilerSettings.declaredArguments(compile.get().getOptions()))));
    }

    /**
     * Makes the generation task {@code task} read the original sources of {@code sourceSet}, after every task that
     * writes them, and refuse them when they are not the ones {@code compile} compiles.
     */
    private static void readOriginalSources(GenerateGraphApi task, Project project, SourceSet sourceSet,
            TaskProvider<JavaCompile> compile, ProcessorDeclarations declarations) {
        ObjectFactory objects = project.getObjects();
        task.getSources().from(project.provider(() -> OriginalSources.of(objects, sourceSet, task, compile.get())));
        // Those sources are plain locations, so the tasks that write them are declared here instead: every
        // task the source set's directories are built by, except this one.
        task.dependsOn((Callable<Object>) () ->
                declarations.settle() ? producers(sourceSet, task) : List.of());
        // What javac compiles is what the compile task's own source holds, as all of its filters select it, which
        // can differ from what this task reads; a generation whose sources differ is refused before the
        // generator reads them. The compile task's source carries the dependency on this very task, so it is
        // held by the action alone, never as an input.
        task.doFirst(new RequireCompiledSources(
                objects.fileCollection().from(project.provider(() -> compile.get().getSource())),
                project.provider(() -> compile.get().toString())));
    }

    /**
     * Gives the generation task {@code task} the locations it only names, derived from {@code compile} and from its
     * own work directory, and refuses any a build sets for them.
     */
    private static void deriveLocations(GenerateGraphApi task, TaskProvider<JavaCompile> compile,
            Provider<Directory> classesOutput, Provider<Directory> processorSourcesOutput) {
        // Locations the task only names: javac's own outputs, which a build moves on the compilation, and the
        // manifest the generator always writes into the work directory, whatever this task says.
        derivedOnly(task, "processorSourcesOutput", task.getProcessorSourcesOutput(), processorSourcesOutput,
                "it follows options.generatedSourceOutputDirectory of " + compile.getName()
                        + ", which is where a build moves it");
        derivedOnly(task, "classesOutput", task.getClassesOutput(), classesOutput,
                "it follows the destinationDirectory of " + compile.getName()
                        + ", which is where a build moves it");
        derivedOnly(task, "annotationManifest", task.getAnnotationManifest(),
                task.getWorkDirectory().file(ANNOTATION_MANIFEST),
                "the generator writes it into the workDirectory of this task, which is where a build moves it");
        task.getCompilationOutputLocations().convention(locations(classesOutput, processorSourcesOutput));
    }

    /** Adds what the generation task {@code generate} writes to the java sources of {@code sourceSet}. */
    private static void addGeneratedSources(Project project, SourceSet sourceSet, Provider<Boolean> participates,
            TaskProvider<GenerateGraphApi> generate) {
        ObjectFactory objects = project.getObjects();
        // The generated vocabulary is a source root of the source set, not extra source handed to one task:
        // that is what javadoc, a sources jar and an IDE import all read. It joins the source set's java sources
        // as a source directory set of its own, so the include and exclude patterns a build gives the source set
        // filter the original sources only: javac compiles all of what the generation wrote, the compilation
        // carrier included. The directory is contributed through a collection that is empty until the source
        // set declares the processor, so a source set that takes no part keeps exactly the roots the java plugin
        // gave it, and the dependency on the generation task travels with the directory to every consumer of the
        // source set. It is the location the generation task holds, so a build that moves it there moves the
        // root javac compiles.
        ConfigurableFileCollection produced = objects.fileCollection();
        produced.from(generate.flatMap(GenerateGraphApi::getSourcesOutput));
        produced.builtBy(generate);
        FileCollection none = objects.fileCollection();
        ConfigurableFileCollection generated = objects.fileCollection();
        generated.from(project.provider(() -> Boolean.TRUE.equals(participates.get()) ? produced : none));
        SourceDirectorySet generatedJava = objects.sourceDirectorySet("graphmaquina",
                sourceSet.getName() + " StateGraph source");
        generatedJava.srcDir(generated);
        sourceSet.getJava().source(generatedJava);
    }

    /**
     * Gives the compilation {@code compile} the manifest argument, the two steps of {@link CompilationLifecycle}, the
     * sealed inventory and the dependency on the generation task {@code generate}.
     */
    private static void wireLifecycle(Project project, ProcessorDeclarations declarations,
            Provider<Boolean> participates, TaskProvider<JavaCompile> compile,
            TaskProvider<GenerateGraphApi> generate) {
        ObjectFactory objects = project.getObjects();
        // Every one of these is a file collection that is empty while the source set takes no part, and each is
        // read when the task graph is built or when the compilation runs — never while the build script is
        // still being read. That is what keeps a compilation that realizes early from being generated for
        // without being prepared. Each is read from the generation task, so the compilation follows the
        // locations that task holds.
        FileCollection manifest = whenParticipating(project, participates,
                generate.flatMap(GenerateGraphApi::getAnnotationManifest));
        FileCollection request = whenParticipating(project, participates,
                generate.flatMap(GenerateGraphApi::getRequestFile));
        FileCollection sealedInventory = whenParticipating(project, participates,
                generate.flatMap(GenerateGraphApi::getWorkDirectory)
                        .map((Directory work) -> work.file(SEALED_INVENTORY)));
        FileCollection generatedSources = whenParticipating(project, participates,
                generate.flatMap(GenerateGraphApi::getSourcesOutput));
        // The generator classpath the generation task holds, so the three steps of one compilation run the same
        // generator, just as they share the launcher the task holds.
        FileCollection lifecycleClasspath = whenParticipating(project, participates,
                generate.map(GenerateGraphApi::getGeneratorClasspath));
        // The original sources the generation task holds, which the preparation compares with what javac compiles.
        FileCollection originalSources = whenParticipating(project, participates,
                generate.map(GenerateGraphApi::getSources));

        compile.configure(task -> {
            ManifestArguments manifestArguments = objects.newInstance(ManifestArguments.class);
            manifestArguments.getManifest().from(manifest);
            CompilationLifecycle.PrepareCompilation prepare =
                    objects.newInstance(CompilationLifecycle.PrepareCompilation.class);
            CompilationLifecycle.CompleteCompilation complete =
                    objects.newInstance(CompilationLifecycle.CompleteCompilation.class);
            for (CompilationLifecycle.LifecycleAction action : List.of(prepare, complete)) {
                // The launcher the generation task holds, so the three steps of one compilation share one JDK.
                action.getJavaLauncher().set(generate.flatMap(GenerateGraphApi::getJavaLauncher));
                action.getGeneratorClasspath().from(lifecycleClasspath);
                action.getRequestFile().from(request);
            }
            prepare.getGeneratedSources().from(generatedSources);
            prepare.getGenerationTask().set(generate.getName());
            prepare.readOriginalSourcesFrom(originalSources);
            // Resolving this dependency, which only happens while the task graph is built, settles whether the
            // source set takes part: every later read gets the answer the graph was built with.
            task.dependsOn((Callable<Object>) () -> declarations.settle() ? generate : List.of());
            // The completion step enumerates every class file this compilation produced, which an incremental
            // recompilation of a subset would leave incomplete. Whether this compilation runs that step is only
            // known once the build script has been read, and this switch is not a lazy property, so it is set on
            // the compile task of every source set, whether that source set takes part or not, and on no other
            // compilation of the project.
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
     * Leaves the source of the compilation carrier out of the sources jar and the API documentation of
     * {@code sourceSet}.
     */
    private static void excludeCarrier(Project project, SourceSet sourceSet) {
        // The compilation carrier is tool-internal: the completion removes its class from the class output, so the
        // jar never carries it, and the sources jar and the API documentation of the source set leave its source
        // out, whenever the build has those tasks. That package belongs to this plugin, so a source set that takes
        // no part has nothing there to leave out.
        project.getTasks().withType(Jar.class).configureEach((Jar jar) -> {
            if (jar.getName().equals(sourceSet.getSourcesJarTaskName())) {
                jar.exclude(CARRIER_SOURCES);
            }
        });
        project.getTasks().withType(Javadoc.class).configureEach((Javadoc javadoc) -> {
            if (javadoc.getName().equals(sourceSet.getJavadocTaskName())) {
                javadoc.exclude(CARRIER_SOURCES);
            }
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
     * Gives {@code property} of {@code task} the value this plugin derives for it, and refuses any other whenever
     * a build sets one: a value set before this configuration ran — by a block configuring every generation task
     * ahead of a source set that did not exist yet — is refused here, and one set afterwards is refused by Gradle,
     * since the property then disallows changes.
     *
     * @throws GradleException if a build already set the property
     */
    private static <T> void derivedOnly(Task task, String name, Property<T> property,
            Provider<? extends T> value, String reason) {
        if (property.isPresent()) {
            throw new GradleException("The value for " + task + " property '" + name + "' cannot be changed: "
                    + reason + ".");
        }
        property.set(value);
        property.disallowChanges();
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
     * The identity of one compilation, {@code group:name:sourceSet}, or {@code name:sourceSet} when the project's
     * group is empty. The generation task asks for it only when it needs the value — when it fingerprints its
     * inputs, or when the configuration cache stores it — so the group is the one the build script settled on
     * even if something realized the task before the script set it, and no {@code Project} reaches the task.
     */
    private static String compilationId(Project project, SourceSet sourceSet) {
        String group = String.valueOf(project.getGroup());
        return group.isEmpty()
                ? project.getName() + ':' + sourceSet.getName()
                : group + ':' + project.getName() + ':' + sourceSet.getName();
    }

    /**
     * The tasks that write the original sources of {@code sourceSet}: every task its source directories are
     * built by, except {@code generation}, which only writes the directory it never reads.
     */
    private static Set<Task> producers(SourceSet sourceSet, Task generation) {
        Set<Task> producers = new LinkedHashSet<>(sourceSet.getJava().getSourceDirectories()
                .getBuildDependencies().getDependencies(generation));
        producers.remove(generation);
        return producers;
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

    /**
     * The action the plugin adds ahead of a generation task's own: it refuses, before the generator reads anything, a
     * generation whose original sources are not the ones its compilation compiles, as {@link OriginalSources} compares
     * them. It holds the sources and the name of the compilation as a file collection and a provider, so it captures
     * no project.
     */
    private static final class RequireCompiledSources implements Action<Task> {

        /** The source of the compilation task, which javac compiles. */
        private final FileCollection compiled;

        /** The compilation task, as a refusal names it. */
        private final Provider<String> compilation;

        RequireCompiledSources(FileCollection compiled, Provider<String> compilation) {
            this.compiled = compiled;
            this.compilation = compilation;
        }

        @Override
        public void execute(Task task) {
            GenerateGraphApi generation = (GenerateGraphApi) task;
            OriginalSources.requireSame(generation.getSources().getAsFileTree().getFiles(),
                    OriginalSources.withRoots(compiled.getAsFileTree()),
                    List.of(generation.getSourcesOutput().get().getAsFile(),
                            generation.getProcessorSourcesOutput().get().getAsFile()),
                    generation.getName(), compilation.get());
        }
    }
}
