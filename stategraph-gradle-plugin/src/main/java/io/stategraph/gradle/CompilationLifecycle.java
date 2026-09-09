package io.stategraph.gradle;

import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.process.ExecOperations;

import javax.inject.Inject;
import java.io.File;
import java.util.Set;

/**
 * The two steps a compilation runs around javac, as the actions the {@code JavaCompile} task carries.
 *
 * <p>{@code prepare-compile} runs first and retires everything the previous cycle owned, so a renamed or deleted
 * declaration cannot keep publishing the binaries javac no longer writes; {@code complete-compile} runs last and
 * publishes what javac accepted. Ordering them as a first and a last action of the compilation itself is what
 * makes the contract hold: a compilation Gradle skips as up to date runs neither, and a compilation javac
 * rejects runs only the first, leaving the pending inventory the next preparation consumes and no published
 * index at all.</p>
 *
 * <p>Both actions are named classes holding provider values and the injected process service only. They never
 * capture a {@code Project}, so the compilation task stays serializable and the two steps run the very request
 * the generation task of the same source set wrote. An action of a compilation that takes no part finds no
 * request and does nothing, which is how the wiring can be added to every compilation and still leave the ones
 * that declare no StateGraph processor alone.</p>
 */
public final class CompilationLifecycle {

    private CompilationLifecycle() {
    }

    /**
     * What both steps share: the launcher and classpath the generator runs under, and the request it reads.
     *
     * <p>Instances are created through {@code ObjectFactory.newInstance}, which implements the property getters
     * and injects {@link ExecOperations}.</p>
     */
    public abstract static class LifecycleAction implements Action<Task> {

        private final ExecOperations execOperations;

        /**
         * @param execOperations the service that starts the generator process
         */
        protected LifecycleAction(ExecOperations execOperations) {
            this.execOperations = execOperations;
        }

        /**
         * The launcher the project's Java toolchain selected.
         *
         * @return the launcher of the generator process
         */
        public abstract Property<JavaLauncher> getJavaLauncher();

        /**
         * The runtime classpath of {@code stategraph-codegen}.
         *
         * @return the generator's classpath
         */
        public abstract ConfigurableFileCollection getGeneratorClasspath();

        /**
         * The request the generation task of this source set wrote, or nothing when the source set takes no
         * part: whether it does is only known once the build script has been read, long after this action was
         * added to the compilation.
         *
         * @return the request file
         */
        public abstract ConfigurableFileCollection getRequestFile();

        /**
         * The operation this step runs.
         *
         * @return the generator operation
         */
        protected abstract String operation();

        @Override
        public void execute(Task task) {
            Set<File> requests = getRequestFile().getFiles();
            if (requests.isEmpty()) {
                return;
            }
            CodegenProcess.of(getJavaLauncher().get(), getGeneratorClasspath().getFiles(), execOperations)
                .run(operation(), requests.iterator().next().toPath());
        }
    }

    /** Retires the outputs of the previous cycle and invalidates the published index, before javac runs. */
    public abstract static class PrepareCompilation extends LifecycleAction {

        /**
         * @param execOperations the service that starts the generator process
         */
        @Inject
        public PrepareCompilation(ExecOperations execOperations) {
            super(execOperations);
        }

        @Override
        protected String operation() {
            return "prepare-compile";
        }
    }

    /** Publishes the index and seals the inventory of what javac produced, after it succeeded. */
    public abstract static class CompleteCompilation extends LifecycleAction {

        /**
         * @param execOperations the service that starts the generator process
         */
        @Inject
        public CompleteCompilation(ExecOperations execOperations) {
            super(execOperations);
        }

        @Override
        protected String operation() {
            return "complete-compile";
        }
    }
}
