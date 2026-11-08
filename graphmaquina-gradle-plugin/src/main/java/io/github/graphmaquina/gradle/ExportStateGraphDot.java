package io.github.graphmaquina.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.process.ExecOperations;

import javax.inject.Inject;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the Graphviz DOT of every StateGraph machine one Java source set declares, once its compilation completed:
 * one file per machine, named after the qualified name of its facade followed by {@code .dot}, holding what
 * {@code GraphvizExporter.render(GraphView.of(<Machine>Runtime.structure()))} returns.
 *
 * <p>The task writes the request the generator reads and runs its {@code export-dot} operation under the launcher it
 * holds, by convention the generation task's. The generator loads the machines over the source set's runtime
 * classpath, which carries the dependency on the compilation, and draws them with the {@code graphmaquina-core} that
 * classpath holds; no class of the application is loaded into the build's own JVM. The output directory belongs to
 * the export: the {@code .dot} files directly in it are removed before the diagrams are written, so a machine renamed
 * or removed leaves no diagram behind, and nothing else in it is touched.</p>
 */
public abstract class ExportStateGraphDot extends DefaultTask {

    /** The directory, under {@code build/graphmaquina/<sourceSet>}, the diagrams are written into by convention. */
    private static final String DIAGRAMS = "dot";

    /** The request the task writes for the generator, beside the directory of its diagrams. */
    private static final String EXPORT_REQUEST = "export-request.json";

    /**
     * Registers the export task of {@code sourceSet}, {@code exportStateGraphDot} for {@code main} and
     * {@code export<SourceSet>StateGraphDot} for any other, which draws the machines {@code compile} compiled under the
     * launcher and over the generator classpath the generation task {@code generate} holds, and takes part when that
     * task does.
     *
     * @param project the project the source set belongs to
     * @param sourceSet the source set whose machines the task draws
     * @param compile the compilation of the source set
     * @param generate the generation task of the source set
     */
    static void register(Project project, SourceSet sourceSet, TaskProvider<JavaCompile> compile,
            TaskProvider<GenerateGraphApi> generate) {
        project.getTasks().register(sourceSet.getTaskName("export", "StateGraphDot"), ExportStateGraphDot.class,
                task -> {
            task.setGroup("graphmaquina");
            task.setDescription("Writes the Graphviz DOT of every StateGraph machine of the '" + sourceSet.getName()
                    + "' source set.");
            task.getParticipates().convention(generate.flatMap(GenerateGraphApi::getParticipates));
            task.onlyIf(ProcessorDeclarations.declaresProcessor(sourceSet), new Participation());
            task.getRuntimeClasspath().convention(sourceSet.getRuntimeClasspath());
            task.getClassesOutput().convention(compile.flatMap(JavaCompile::getDestinationDirectory));
            task.getGeneratorClasspath().convention(generate.map(GenerateGraphApi::getGeneratorClasspath));
            task.getJavaLauncher().convention(generate.flatMap(GenerateGraphApi::getJavaLauncher));
            task.getOutputDirectory().convention(project.getLayout().getBuildDirectory()
                    .dir("graphmaquina/" + sourceSet.getName() + "/" + DIAGRAMS));
            task.getRequestFile().convention(project.getLayout().getBuildDirectory()
                    .file("graphmaquina/" + sourceSet.getName() + "/" + EXPORT_REQUEST));
        });
    }

    /**
     * Whether the source set declares the StateGraph annotation processor; by convention the generation task's answer.
     * The task skips itself when it does not.
     *
     * @return whether the source set takes part
     */
    @Input
    public abstract Property<Boolean> getParticipates();

    /**
     * The runtime classpath the machines are loaded from; by convention the source set's, which holds its class
     * output and {@code graphmaquina-core}.
     *
     * @return the runtime classpath of the source set
     */
    @Classpath
    public abstract ConfigurableFileCollection getRuntimeClasspath();

    /**
     * The class output of the source set's compilation, which holds the index of the machines it published; it follows
     * the {@code destinationDirectory} of the compile task and is part of the runtime classpath, which tracks it.
     *
     * @return the class output directory
     */
    @Internal
    public abstract DirectoryProperty getClassesOutput();

    /**
     * The runtime classpath of {@code graphmaquina-codegen}; by convention the generation task's.
     *
     * @return the generator classpath
     */
    @Classpath
    public abstract ConfigurableFileCollection getGeneratorClasspath();

    /**
     * The launcher the generator runs under; by convention the generation task's.
     *
     * @return the launcher of the generator process
     */
    @Nested
    public abstract Property<JavaLauncher> getJavaLauncher();

    /**
     * The directory the diagrams are written into; by convention {@code build/graphmaquina/<sourceSet>/dot}.
     *
     * @return the output directory
     */
    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    /**
     * The request this task writes for the generator; by convention
     * {@code build/graphmaquina/<sourceSet>/export-request.json}.
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

    /** Writes the request and runs the generator's {@code export-dot} operation over it. */
    @TaskAction
    public void export() {
        CodegenProcess process =
                CodegenProcess.of(getJavaLauncher().get(), getGeneratorClasspath().getFiles(), getExecOperations());
        List<String> classpath = new ArrayList<>();
        for (File entry : getRuntimeClasspath()) {
            classpath.add(entry.getAbsolutePath());
        }
        Path requestFile = getRequestFile().get().getAsFile().toPath();
        CodegenProcess.writeExport(requestFile, new CodegenProcess.ExportRequest(
                getClassesOutput().get().getAsFile().getAbsolutePath(), classpath,
                getOutputDirectory().get().getAsFile().getAbsolutePath()));
        process.run("export-dot", requestFile);
    }

    /**
     * The {@code onlyIf} of an export task: it reads the participation the task itself carries and captures nothing, so
     * the condition holds no reference to the project that configured it.
     */
    private static final class Participation implements Spec<Task> {

        @Override
        public boolean isSatisfiedBy(Task task) {
            return ((ExportStateGraphDot) task).getParticipates().get();
        }
    }
}
