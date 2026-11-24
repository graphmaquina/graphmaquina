package io.github.graphmaquina.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.process.ExecOperations;
import javax.inject.Inject;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Prints the StateGraph export index of every entry of one Java source set's compile classpath that publishes
 * {@code META-INF/graphmaquina/exports.json}: its location, the tool version that wrote it, its machines and its
 * symbols, and each reason {@code SG602} would refuse it when the source set compiles.
 *
 * <p>The task writes the request the generator reads and runs its {@code list-exports} operation under the launcher it
 * holds, by convention the generation task's; the generator prints the report on the build's output. It runs neither
 * the generation nor the compilation of the source set, so it lists an index that the generation would refuse. It
 * declares no output, so it runs every time it is asked for.</p>
 */
public abstract class ListStateGraphExports extends DefaultTask {

    /** The request the task writes for the generator, in the work directory of the source set's generation. */
    private static final String LIST_REQUEST = "list-exports-request.json";

    /**
     * Registers the listing task of {@code sourceSet}, {@code listStateGraphExports} for {@code main} and
     * {@code list<SourceSet>StateGraphExports} for any other, which runs under the launcher and over the generator
     * classpath the generation task {@code generate} holds, and takes part when that task does, without depending on
     * that task.
     *
     * @param project the project the source set belongs to
     * @param sourceSet the source set whose compile classpath the task reports
     * @param generate the generation task of the source set
     */
    static void register(Project project, SourceSet sourceSet, TaskProvider<GenerateGraphApi> generate) {
        project.getTasks().register(sourceSet.getTaskName("list", "StateGraphExports"), ListStateGraphExports.class,
                task -> {
            task.setGroup("graphmaquina");
            task.setDescription("Prints the StateGraph export index of every compile classpath entry of the '"
                    + sourceSet.getName() + "' source set that publishes one.");
            // Plain providers: one derived from the generation task would carry that task as its producer, and the
            // listing, which reports what the generation would refuse, would run the generation first.
            task.getParticipates().convention(project.provider(() -> generate.get().getParticipates().get()));
            task.onlyIf(ProcessorDeclarations.declaresProcessor(sourceSet), new Participation());
            task.getCompileClasspath().convention(sourceSet.getCompileClasspath());
            task.getGeneratorClasspath().convention(project.provider(() -> generate.get().getGeneratorClasspath()));
            task.getJavaLauncher().convention(project.provider(() -> generate.get().getJavaLauncher().get()));
            task.getRequestFile().convention(generate.flatMap(GenerateGraphApi::getWorkDirectory)
                    .map((Directory work) -> work.file(LIST_REQUEST)));
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
     * The classpath whose export indexes the task reports; by convention the source set's compile classpath, the one
     * its generation reads the published indexes of.
     *
     * @return the compile classpath of the source set
     */
    @Classpath
    public abstract ConfigurableFileCollection getCompileClasspath();

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
     * The request this task writes for the generator; by convention the {@code list-exports-request.json} of the
     * generation task's work directory. It is no output of the task, which declares none, so the task never counts as
     * up to date.
     *
     * @return the request file
     */
    @Internal
    public abstract RegularFileProperty getRequestFile();

    /**
     * @return the service that starts the generator process
     */
    @Inject
    protected abstract ExecOperations getExecOperations();

    /** Writes the request and runs the generator's {@code list-exports} operation over it. */
    @TaskAction
    public void list() {
        CodegenProcess process =
                CodegenProcess.of(getJavaLauncher().get(), getGeneratorClasspath().getFiles(), getExecOperations());
        List<String> classpath = new ArrayList<>();
        for (File entry : getCompileClasspath()) {
            classpath.add(entry.getAbsolutePath());
        }
        Path requestFile = getRequestFile().get().getAsFile().toPath();
        CodegenProcess.writeListExports(requestFile, classpath);
        process.run("list-exports", requestFile);
    }

    /**
     * The {@code onlyIf} of a listing task: it reads the participation the task itself carries and captures nothing, so
     * the condition holds no reference to the project that configured it.
     */
    private static final class Participation implements Spec<Task> {

        @Override
        public boolean isSatisfiedBy(Task task) {
            return ((ListStateGraphExports) task).getParticipates().get();
        }
    }
}
