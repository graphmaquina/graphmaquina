package io.github.graphmaquina.gradle;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencySet;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.tasks.SourceSet;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether one source set takes part, from the StateGraph processor declarations of its annotation processor
 * configuration.
 *
 * <p>Every declared dependency is counted by its coordinates as it is added and uncounted as it is removed,
 * so the answer is read from values and resolves nothing, and a declaration the build removes again no
 * longer counts. While the build is being configured, the answer follows the declarations as they stand:
 * the declaration may be the last line of the build script, and something may ask before it is read. The
 * task graph then settles the answer, once and for the rest of the build: resolving the dependencies of the
 * compilation or of the generation calls {@link #settle()}. A dependency that a {@code defaultDependencies}
 * or {@code withDependencies} action only adds when javac's processor path is resolved, which is after the
 * graph was built, therefore cannot change what the compilation's lifecycle does halfway through.</p>
 */
final class ProcessorDeclarations {

    /** The group and the artifact of the annotation processor a source set declares to take part. */
    private static final String PROCESSOR_GROUP = "io.github.graphmaquina";
    private static final String PROCESSOR_ARTIFACT = "graphmaquina-processor";

    /** The two of them, as the coordinates the participation switch is looked up by. */
    private static final String PROCESSOR_COORDINATES = PROCESSOR_GROUP + ':' + PROCESSOR_ARTIFACT;

    /** The coordinates of every declared dependency, once per declaration. */
    private final List<String> declared = new ArrayList<>();

    /** The settled answer, or {@code null} while the task graph has not asked yet. */
    private Boolean settled;

    private ProcessorDeclarations() {
    }

    /**
     * The StateGraph processor declarations of the annotation processor configuration of {@code sourceSet}, those
     * it inherits from the configurations it extends included, counted as the build declares and removes
     * dependencies there.
     */
    static ProcessorDeclarations of(Project project, SourceSet sourceSet) {
        ProcessorDeclarations declarations = new ProcessorDeclarations();
        DependencySet dependencies = project.getConfigurations()
                .getByName(sourceSet.getAnnotationProcessorConfigurationName()).getAllDependencies();
        dependencies.all(declarations::added);
        dependencies.whenObjectRemoved(declarations::removed);
        return declarations;
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
    static String declaresProcessor(SourceSet sourceSet) {
        return "the " + sourceSet.getName() + " source set declares " + PROCESSOR_COORDINATES + " in "
                + sourceSet.getAnnotationProcessorConfigurationName();
    }

    synchronized void added(Dependency dependency) {
        declared.add(coordinates(dependency));
    }

    synchronized void removed(Dependency dependency) {
        declared.remove(coordinates(dependency));
    }

    /** Whether the source set takes part: the settled answer, or the declarations as they stand. */
    synchronized boolean takesPart() {
        return settled != null ? settled : declared.contains(PROCESSOR_COORDINATES);
    }

    /** Settles the answer unless the task graph already settled it, and returns it. */
    synchronized boolean settle() {
        if (settled == null) {
            settled = declared.contains(PROCESSOR_COORDINATES);
        }
        return settled;
    }
}
