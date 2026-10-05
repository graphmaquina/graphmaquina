package io.github.graphmaquina.gradle;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.compile.CompileOptions;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.process.ExecOperations;

import javax.inject.Inject;
import javax.lang.model.SourceVersion;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The two steps a compilation runs around javac, as the actions the {@code JavaCompile} task carries.
 *
 * <p>{@code prepare-compile} runs first and retires everything the previous cycle owned, so a renamed or deleted
 * declaration cannot keep publishing the binaries javac no longer writes; {@code complete-compile} runs last and
 * publishes what javac accepted. Ordering them as a first and a last action of the compilation itself is what
 * makes the contract hold: a compilation Gradle skips as up to date runs neither, nor does one whose outputs,
 * the sealed inventory included, Gradle restores from the build cache, and a compilation javac rejects runs only
 * the first, leaving the pending inventory the next preparation consumes and no published index at all.</p>
 *
 * <p>That last guarantee needs a compilation that fails when javac rejects it and that writes classes when it
 * succeeds, and one that compiles what the generation wrote; the completion also needs javac to run the StateGraph
 * annotation processor, which writes the provisional index the completion publishes. The preparation therefore
 * refuses, before it retires anything, {@code options.failOnError = false}, {@code -proc:only} or {@code -proc:none}
 * as the last {@code -proc:} option javac is given, a last {@code -processor} list that leaves out the StateGraph
 * processor or holds a name that is not a valid qualified class name, and a compilation whose sources no longer
 * include the generated ones. A well-formed name of a class that is not on the processor path, such as a misspelt
 * one, is not refused: the compilation fails on it after the preparation, like any failed compilation, and the next
 * preparation reads the pending inventory that compilation left to clean up after it.</p>
 *
 * <p>Both actions are named classes holding provider values and the injected process service only. They never
 * capture a {@code Project}, so the compilation task stays serializable and the two steps run the very request
 * the generation task of the same source set wrote. An action of a compilation that takes no part finds no
 * request and does nothing, which is how the wiring can be added to every compilation and still leave the ones
 * that declare no StateGraph processor alone. An action of a compilation that takes part, but whose request was
 * never written, fails and says so.</p>
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
         * The launcher the generation task of this source set runs the generator under, by convention the one the
         * project's Java toolchain selected, so every step of one compilation runs on the same JDK.
         *
         * @return the launcher of the generator process
         */
        public abstract Property<JavaLauncher> getJavaLauncher();

        /**
         * The classpath the generation task of this source set runs the generator over, by convention the
         * {@code graphmaquinaGenerator} configuration, so every step of one compilation runs the same generator.
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

        /**
         * Checks, before the generator runs, that this step can do its work for {@code compilation}; the default
         * checks nothing.
         *
         * @param compilation the compilation this step belongs to
         * @throws GradleException if this step must not run for it
         */
        protected void verify(Task compilation) {
        }

        /**
         * Runs this step for a compilation that takes part, and does nothing for one that does not.
         *
         * @param task the compilation this step belongs to
         * @throws GradleException if the request of a compilation that takes part was never written, if
         *         {@link #verify} refuses the compilation, if {@link CodegenProcess#of} refuses the launcher, or
         *         if the generator fails
         */
        @Override
        public void execute(Task task) {
            Set<File> requests = getRequestFile().getFiles();
            if (requests.isEmpty()) {
                return;
            }
            Path request = requests.iterator().next().toPath();
            if (!Files.isRegularFile(request)) {
                throw new GradleException("the StateGraph request " + request + " does not exist, so '"
                        + operation() + "' cannot run for " + task + ": the generation task of its source set"
                        + " writes it, and it has not run");
            }
            verify(task);
            CodegenProcess.of(getJavaLauncher().get(), getGeneratorClasspath().getFiles(), execOperations)
                .run(operation(), request);
        }
    }

    /**
     * Retires the outputs of the previous cycle and invalidates the published index, before javac runs, once it
     * has checked that javac will compile what the generation wrote, run the StateGraph annotation processor, and
     * either fail or write classes.
     */
    public abstract static class PrepareCompilation extends LifecycleAction {

        /** The StateGraph annotation processor, as a {@code -processor} list names it. */
        private static final String PROCESSOR = "io.github.graphmaquina.processor.StateGraphProcessor";

        /**
         * @param execOperations the service that starts the generator process
         */
        @Inject
        public PrepareCompilation(ExecOperations execOperations) {
            super(execOperations);
        }

        /**
         * The directory the generation task of this source set writes the vocabulary into, or nothing when the
         * source set takes no part.
         *
         * @return the generated source directory
         */
        public abstract ConfigurableFileCollection getGeneratedSources();

        /**
         * The name of the generation task of this source set, which a refusal names.
         *
         * @return the generation task's name
         */
        public abstract Property<String> getGenerationTask();

        @Override
        protected String operation() {
            return "prepare-compile";
        }

        /**
         * Refuses a compilation that could end without the classes the completion step publishes, one that would
         * run without the StateGraph annotation processor or with a processor name that is not a valid qualified
         * class name, and one whose sources do not include what the generation wrote.
         *
         * <p>javac takes the last {@code -proc:} option it is given. Gradle 9.2.1's Java compiler, in process or in
         * a forked compiler daemon, loads the annotation processors itself: it takes the last {@code -processor}
         * list, splits it on every comma, keeping empty names, runs only the processors the list names, takes each
         * name exactly as written and fails on the first one it cannot load. A javac Gradle starts from the command
         * line, as it does when {@code options.fork} is set with {@code forkOptions.javaHome} or
         * {@code forkOptions.executable}, reads the list itself and drops the empty names at its end, though a list
         * without a comma stays one name, even when it is empty; this check refuses every empty name whichever javac
         * runs. A well-formed name of a class that is not on the processor path, such as a misspelt one, passes this
         * check, and the compilation fails on it.</p>
         *
         * @param compilation the {@code JavaCompile} task this step belongs to
         * @throws GradleException if the compilation sets {@code options.failOnError = false}, passes
         *         {@code -proc:only} or {@code -proc:none} as its last {@code -proc:} option, passes a last
         *         {@code -processor} list without the StateGraph processor or with a name that is not a valid
         *         qualified class name, or does not compile a source of the generated directory
         */
        @Override
        protected void verify(Task compilation) {
            JavaCompile javaCompile = (JavaCompile) compilation;
            CompileOptions options = javaCompile.getOptions();
            if (!options.isFailOnError()) {
                throw new GradleException("the StateGraph compilation of " + compilation + " cannot run with"
                        + " options.failOnError = false: javac could reject its sources while the task still"
                        + " succeeds, and the completion step would then publish classes javac never wrote."
                        + " Remove options.failOnError = false from " + compilation + ".");
            }
            List<String> arguments = options.getAllCompilerArgs();
            String processing = "";
            for (String argument : arguments) {
                if (argument.startsWith("-proc:")) {
                    processing = argument;
                }
            }
            if (processing.equals("-proc:only")) {
                throw new GradleException("the StateGraph compilation of " + compilation + " cannot run with"
                        + " -proc:only: javac would run the annotation processors and write no class, so the"
                        + " completion step would find nothing to publish. Remove -proc:only from the compiler"
                        + " arguments of " + compilation + ".");
            }
            if (processing.equals("-proc:none")) {
                throw new GradleException("the StateGraph compilation of " + compilation + " cannot run with"
                        + " -proc:none: javac would run no annotation processor, so the StateGraph processor would"
                        + " write no index for the completion step to publish. Remove -proc:none from the compiler"
                        + " arguments of " + compilation + ".");
            }
            int option = arguments.lastIndexOf("-processor");
            if (option >= 0 && option + 1 < arguments.size()) {
                String list = arguments.get(option + 1);
                String[] names = list.split(",", -1);
                for (String name : names) {
                    if (!SourceVersion.isName(name)) {
                        throw new GradleException("the StateGraph compilation of " + compilation + " cannot run"
                                + " with -processor '" + list + "', whose name '" + name + "' is not a valid"
                                + " qualified class name. Gradle's Java compiler, in process or in its compiler"
                                + " daemon, splits that list on every comma, keeping empty names, and takes each name"
                                + " exactly as written, spaces included, so it would fail to load a processor of that"
                                + " name. Correct or remove that name in the compiler arguments of " + compilation
                                + ".");
                    }
                }
                if (!Arrays.asList(names).contains(PROCESSOR)) {
                    throw new GradleException("the StateGraph compilation of " + compilation + " cannot run with"
                            + " -processor '" + list + "', which leaves out " + PROCESSOR + ": javac would run only"
                            + " the annotation processors that list names, so the StateGraph processor would write no"
                            + " index for the completion step to publish. Add " + PROCESSOR + " to that list, after"
                            + " the processors of Lombok when the project uses Lombok, or remove -processor and its"
                            + " list from the compiler arguments of " + compilation + ".");
                }
            }
            for (File directory : getGeneratedSources().getFiles()) {
                List<File> generatedSources = javaSources(directory);
                Set<File> compiled = generatedSources.isEmpty() ? Set.of() : javaCompile.getSource().getFiles();
                for (File generated : generatedSources) {
                    if (!compiled.contains(generated)) {
                        throw new GradleException(notCompiled(compilation, generated, directory,
                                getGenerationTask().get()));
                    }
                }
            }
        }

        /**
         * Why {@code compilation} does not compile {@code generated}, and the ways back: adding directories
         * instead of replacing them, or nesting the generated directory in the source set again, given for both
         * build script languages with fully qualified names so either can be pasted as it is. A source or a
         * pattern set on the compilation itself filters the generated directory whatever the source set holds, so
         * neither line undoes it: that setting has to be removed from the task.
         */
        private static String notCompiled(Task compilation, File generated, File directory, String generation) {
            String kotlin = "java.source(objects.sourceDirectorySet(\"graphmaquina\", \"StateGraph source\")"
                    + ".srcDir(tasks.named(\"" + generation + "\","
                    + " io.github.graphmaquina.gradle.GenerateGraphApi::class.java).flatMap { it.sourcesOutput }))";
            String groovy = "java.source(objects.sourceDirectorySet('graphmaquina', 'StateGraph source')"
                    + ".srcDir(tasks.named('" + generation + "',"
                    + " io.github.graphmaquina.gradle.GenerateGraphApi).flatMap { it.sourcesOutput }))";
            return compilation + " does not compile " + generated + ", which " + generation + " generated into "
                    + directory + ". The StateGraph plugin adds that directory to the java sources of the source"
                    + " set as a source directory set of its own, which the include and exclude patterns of the"
                    + " source set do not filter: replacing the source set's java directories with"
                    + " java.setSrcDirs(...) or java.srcDirs = [...] drops it, and so does a source or a pattern set"
                    + " on " + compilation + " itself. Add directories with java.srcDir(...) instead of replacing"
                    + " them, or add the generated directory again after the replacement, inside the source set's"
                    + " block:" + System.lineSeparator()
                    + "    Kotlin DSL: " + kotlin + System.lineSeparator()
                    + "    Groovy DSL: " + groovy + System.lineSeparator()
                    + "Neither line undoes a source or a pattern set on " + compilation + " itself, which filters"
                    + " the generated directory whatever the source set holds: when that is what leaves the file"
                    + " out, remove that setting from " + compilation + ".";
        }

        /** The Java sources under {@code directory}, in a stable order; none when it does not exist. */
        private static List<File> javaSources(File directory) {
            if (!directory.isDirectory()) {
                return List.of();
            }
            try (Stream<Path> walk = Files.walk(directory.toPath())) {
                return walk.filter(Files::isRegularFile)
                        .filter((Path file) -> file.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .map(Path::toFile)
                        .toList();
            } catch (IOException failure) {
                throw new UncheckedIOException("cannot list the generated sources under " + directory, failure);
            }
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
