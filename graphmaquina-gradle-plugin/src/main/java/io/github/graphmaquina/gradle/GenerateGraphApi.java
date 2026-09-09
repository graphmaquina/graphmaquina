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
 * <p>The task writes the request the generator reads and runs {@code generate} over it under the project's Java
 * toolchain. What it produces is the generated source directory javac then compiles and the annotation manifest
 * javac passes to the processor; everything else the request names — the class output, the processor's source
 * output and the shared work directory — belongs to the compilation, not to this task, and is therefore
 * {@link Internal internal} here. The sealed inventory the compilation lifecycle writes into that same work
 * directory is registered as an output of {@code JavaCompile}, so no completion step can invalidate this task's
 * own snapshot and turn every build into a regeneration.</p>
 *
 * <p>Application dependencies are tracked as a {@link Classpath} rather than a compile classpath because the
 * generator reads the {@code META-INF/graphmaquina/exports.json} each of them publishes: a dependency whose only
 * change is that resource still changes what this task must generate.</p>
 */
public abstract class GenerateGraphApi extends DefaultTask {

    /**
     * The original source roots of the source set, without the generated ones. Their contents are what the
     * generator parses, so the task is out of date whenever any declaration under them changes.
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
     * configuration and never mixed into the application's own classpath.
     *
     * @return the generator's classpath
     */
    @Classpath
    public abstract ConfigurableFileCollection getGeneratorClasspath();

    /**
     * The directory the compilation belongs to, which the generator reports diagnostics relative to.
     *
     * @return the project directory
     */
    @Internal
    public abstract DirectoryProperty getProjectDirectory();

    /**
     * Where the generated vocabulary is written; the compiler of this source set compiles it as extra source.
     *
     * @return the generated source directory
     */
    @OutputDirectory
    public abstract DirectoryProperty getSourcesOutput();

    /**
     * Where the annotation processor writes its own sources. It is an output of {@code JavaCompile}, not of
     * this task, which only names it in the request.
     *
     * @return the annotation processor's source output directory
     */
    @Internal
    public abstract DirectoryProperty getProcessorSourcesOutput();

    /**
     * Where javac writes the classes of this compilation. It is an output of {@code JavaCompile}, not of this
     * task, which only names it in the request.
     *
     * @return the class output directory
     */
    @Internal
    public abstract DirectoryProperty getClassesOutput();

    /**
     * The directory the manifests and the inventories of this compilation live in. Only the files this task
     * writes there are its outputs; the directory itself is shared with the compilation lifecycle.
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
     * moves the compiler's output write the request again instead of leaving the previous paths in it.</p>
     *
     * @return the class output and the processor's source output, as absolute paths
     */
    @Input
    public abstract Property<String> getCompilationOutputLocations();

    /**
     * Whether the source set this task belongs to declares the StateGraph processor, which is the condition of
     * this task's {@code onlyIf}. It is an input, so a source set that starts declaring it generates instead of
     * staying up to date on a run that was skipped.
     *
     * @return whether the source set takes part
     */
    @Input
    public abstract Property<Boolean> getParticipates();

    /**
     * The identity of the compilation, spelled {@code group:name:sourceSet}, or {@code name:sourceSet} when the
     * project declares no group. It never carries a path or a timestamp, so the same sources generate the same
     * manifest wherever the build runs.
     *
     * @return the compilation identity
     */
    @Input
    public abstract Property<String> getCompilationId();

    /**
     * The launcher the project's Java toolchain selected, which the generator runs under; it is not the JVM of
     * the Gradle daemon unless the toolchain resolves to the very same installation.
     *
     * @return the launcher of the generator process
     */
    @Nested
    public abstract Property<JavaLauncher> getJavaLauncher();

    /**
     * The manifest the generator writes for the compilation that follows, which javac passes to the processor.
     *
     * @return the annotation manifest
     */
    @OutputFile
    public abstract RegularFileProperty getAnnotationManifest();

    /**
     * The request this task writes and every step of the compilation lifecycle reads back.
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
            getCompilationId().get()));
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
