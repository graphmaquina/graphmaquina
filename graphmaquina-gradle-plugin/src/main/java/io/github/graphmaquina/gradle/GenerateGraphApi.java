package io.github.graphmaquina.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.process.ExecOperations;

import javax.inject.Inject;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates the StateGraph typed vocabulary of one Java source set, before its compiler runs.
 *
 * <p>The task writes the request the generator reads and runs {@code generate} over it under the launcher it
 * holds, by convention the project's Java toolchain. What it produces is the generated source directory javac
 * then compiles and the annotation manifest javac passes to the processor; everything else the request names —
 * the class output, the processor's source output and the shared work directory — belongs to the compilation,
 * not to this task, and is therefore {@link Internal internal} here. The sealed inventory the compilation
 * lifecycle writes into that same work directory is registered as an output of {@code JavaCompile}, so no
 * completion step can invalidate this task's own snapshot and turn every build into a regeneration.</p>
 *
 * <p>Application dependencies are tracked as a {@link Classpath} rather than a compile classpath because the
 * generator reads the {@code META-INF/graphmaquina/exports.json} each of them publishes: a dependency whose only
 * change is that resource still changes what this task must generate.</p>
 */
public abstract class GenerateGraphApi extends DefaultTask {

    /**
     * The original sources of the source set: its source directories other than the generated ones, each named
     * whole, or file by file as the source set's include and exclude patterns select them when it has any,
     * which is what javac compiles of them. They are what the generator parses, so the task is out of date
     * whenever any of them changes, and it runs after every task those directories are built by.
     *
     * @return the original sources of this source set
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSources();

    /**
     * The compile classpath of the source set, without its own class output.
     *
     * @return the classpath the generator resolves headers and published indexes against
     */
    @Classpath
    public abstract ConfigurableFileCollection getApplicationClasspath();

    /**
     * The runtime classpath of {@code graphmaquina-codegen}, resolved from the {@code graphmaquinaGenerator}
     * configuration and never mixed into the application's own classpath. The preparation and the completion
     * around the compilation of this source set run the generator over it as well.
     *
     * <p>The plugin gives that configuration as the convention of this collection, so a classpath a build sets
     * replaces it whenever the build sets it. Paths a build adds join it when they are added after the plugin
     * configured this task, and replace it when they are added before, as by a block that configures every
     * generation task ahead of a source set that does not exist yet.</p>
     *
     * @return the generator's classpath
     */
    @Classpath
    public abstract ConfigurableFileCollection getGeneratorClasspath();

    /**
     * The directory the compilation belongs to, which the generator reports diagnostics relative to; by
     * convention the project directory.
     *
     * @return the project directory
     */
    @Internal
    public abstract DirectoryProperty getProjectDirectory();

    /**
     * Where the generated vocabulary is written. The plugin adds this directory to the java sources of the
     * source set as a source directory set of its own, which the include and exclude patterns of the source set
     * do not filter, so the compiler of the source set compiles all of what is written here, whatever location
     * this property holds. By convention it is {@code build/generated/sources/graphmaquina/<sourceSet>}; a value a
     * build sets wins whenever the build sets it.
     *
     * @return the generated source directory
     */
    @OutputDirectory
    public abstract DirectoryProperty getSourcesOutput();

    /**
     * Where the annotation processor writes its own sources. It is an output of {@code JavaCompile}, not of
     * this task, which only names it in the request; the property follows the compilation's
     * {@code options.generatedSourceOutputDirectory} and cannot be set here: a build that sets it is refused,
     * whether it does so before or after the plugin configured this task.
     *
     * @return the annotation processor's source output directory
     */
    @Internal
    public abstract DirectoryProperty getProcessorSourcesOutput();

    /**
     * Where javac writes the classes of this compilation. It is an output of {@code JavaCompile}, not of this
     * task, which only names it in the request; the property follows the compilation's
     * {@code destinationDirectory} and cannot be set here: a build that sets it is refused, whether it does so
     * before or after the plugin configured this task.
     *
     * @return the class output directory
     */
    @Internal
    public abstract DirectoryProperty getClassesOutput();

    /**
     * The directory the manifests and the inventories of this compilation live in. Only the files this task
     * writes there are its outputs; the directory itself is shared with the compilation lifecycle. Moving it
     * moves the annotation manifest, the request unless it is set elsewhere, and the sealed inventory the
     * compilation declares as an output. By convention it is {@code build/graphmaquina/<sourceSet>}; a value a
     * build sets wins whenever the build sets it.
     *
     * @return the work directory of this compilation
     */
    @Internal
    public abstract DirectoryProperty getWorkDirectory();

    /**
     * The two roots of the compilation this task only names in the request: the class output and the
     * processor's source output, as one value holding both absolute paths.
     *
     * <p>Both are {@link Internal internal} as directories, since they are outputs of {@code JavaCompile} and
     * not of this task, which leaves their locations untracked. Naming them here is what makes a build that
     * moves the compiler's output write the request again instead of leaving the previous paths in it. The plugin
     * sets this value as a convention, so a value a build sets wins whenever the build sets it.</p>
     *
     * @return the class output and the processor's source output, as absolute paths
     */
    @Input
    public abstract Property<String> getCompilationOutputLocations();

    /**
     * Whether the source set this task belongs to declares the StateGraph processor, which is the condition of
     * this task's {@code onlyIf}. It is an input, so a source set that starts declaring it generates instead of
     * staying up to date on a run that was skipped. The plugin sets it as a convention, so a value a build sets
     * wins whenever the build sets it; the compilation's own wiring still follows the declarations.
     *
     * @return whether the source set takes part
     */
    @Input
    public abstract Property<Boolean> getParticipates();

    /**
     * The identity of the compilation, by convention spelled {@code group:name:sourceSet}, or
     * {@code name:sourceSet} when the project's group is empty. It never carries a path or a timestamp, so the
     * same sources generate the same manifest wherever the build runs. Its convention reads the project's group
     * when the value is needed, not when the task is realized, so it is the group the build script settled on.
     *
     * @return the compilation identity
     */
    @Input
    public abstract Property<String> getCompilationId();

    /**
     * The Java release the generator parses the original sources at and resolves the JDK types they name against.
     *
     * <p>By convention it is the compilation's {@code options.release}, which javac compiles at. When that is not
     * set, it is the language version of the compilation's toolchain. Gradle then passes javac {@code -source} and
     * {@code -target} from the compilation's source and target compatibility, which default to that version, and
     * no {@code --release}, so javac resolves JDK types against the whole API of the toolchain's JDK: the API of
     * that release. A source compatibility set lower makes javac parse at that lower level, where it accepts no
     * syntax the generator does not parse.</p>
     *
     * @return the release of the compilation
     */
    @Input
    @Optional
    public abstract Property<Integer> getRelease();

    /**
     * The encoding the compilation of this source set reads its sources in, which the generator reads the original
     * sources in and writes the generated ones in. By convention it is the compilation's {@code options.encoding}.
     * Without one the generator reads UTF-8, while javac reads the default charset of the JVM that runs it, which is
     * UTF-8 from Java 18 on unless {@code file.encoding} names another.
     *
     * @return the name of the source encoding of the compilation
     */
    @Input
    @Optional
    public abstract Property<String> getEncoding();

    /**
     * The launcher the generator runs under, by convention the one the project's Java toolchain selected; it is
     * not the JVM of the Gradle daemon unless the toolchain resolves to the very same installation. The
     * preparation and the completion around the compilation of this source set run under it as well.
     *
     * @return the launcher of the generator process
     */
    @Nested
    public abstract Property<JavaLauncher> getJavaLauncher();

    /**
     * The manifest the generator writes for the compilation that follows, which javac passes to the processor.
     * The generator always writes it into the work directory, so the property follows that directory and cannot
     * be set apart from it: a build that sets it is refused, whether it does so before or after the plugin
     * configured this task.
     *
     * @return the annotation manifest
     */
    @OutputFile
    public abstract RegularFileProperty getAnnotationManifest();

    /**
     * The request this task writes and every step of the compilation lifecycle reads back; by convention it is
     * the {@code request.json} of the work directory.
     *
     * @return the request file
     */
    @OutputFile
    public abstract RegularFileProperty getRequestFile();

    /**
     * @return the service that starts the generator process
     */
    @Inject
    protected abstract ExecOperations getExecOperations();

    /** Writes the request of this source set and runs the generator's {@code generate} operation over it. */
    @TaskAction
    public void generate() {
        Path requestFile = getRequestFile().get().getAsFile().toPath();
        CodegenProcess.write(requestFile, new CodegenProcess.Request(
            absolute(getProjectDirectory()),
            absolutePaths(getSources()),
            absolutePaths(getApplicationClasspath()),
            absolute(getSourcesOutput()),
            absolute(getProcessorSourcesOutput()),
            absolute(getClassesOutput()),
            absolute(getWorkDirectory()),
            getCompilationId().get(),
            getRelease().getOrNull(),
            getEncoding().getOrNull()));
        CodegenProcess.of(getJavaLauncher().get(), getGeneratorClasspath().getFiles(), getExecOperations())
            .run("generate", requestFile);
    }

    private static String absolute(DirectoryProperty directory) {
        return directory.get().getAsFile().getAbsolutePath();
    }

    private static List<String> absolutePaths(ConfigurableFileCollection files) {
        List<String> paths = new ArrayList<>();
        for (File file : files) {
            paths.add(file.getAbsolutePath());
        }
        return paths;
    }
}
