package io.github.graphmaquina.gradle;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * succeeds, one that compiles what the generation wrote and the very original sources the generation read, at the
 * release and in the encoding the generation was given, and one that reads the sources annotation processors generate
 * in the charset javac writes them in; the completion also needs javac to run the StateGraph annotation processor,
 * which writes the provisional index the completion publishes, and to keep the {@code SourceFile} attribute of every
 * class file, through which the completion links a class file to the source that declared it. The preparation
 * therefore refuses, before it retires anything:</p>
 * <ul>
 *   <li>{@code options.failOnError = false};</li>
 *   <li>{@code -proc:only} or {@code -proc:none} as the last {@code -proc:} compiler argument;</li>
 *   <li>an {@code options.annotationProcessorPath} that is empty or null, for which Gradle gives javac a
 *       {@code -proc:none} of its own, and with which Gradle's own Java compiler, in process or in its compiler daemon,
 *       runs no annotation processor whatever {@code -proc:} compiler argument follows; only a javac Gradle starts from
 *       the command line is left to run with it, when a {@code -proc:} compiler argument follows;</li>
 *   <li>a last {@code -processor} list among the compiler arguments that leaves out the StateGraph processor or holds
 *       a name that is not a valid qualified class name;</li>
 *   <li>a debug setting that leaves the {@code SourceFile} attribute out;</li>
 *   <li>a last {@code -encoding} compiler argument that names another charset than {@code options.encoding}, when this
 *       JVM supports both and Gradle's own Java compiler runs javac in process or in its compiler daemon;</li>
 *   <li>argument providers that change the release or the encoding javac takes from the ones the generation was
 *       given;</li>
 *   <li>a compilation whose original sources are not those the generation read, and one whose sources no longer
 *       include the generated ones.</li>
 * </ul>
 * <p>It reads the compiler arguments and those settings only. A processor path that holds no StateGraph processor, a
 * well-formed name of a class that is not on the processor path, such as a misspelt one, and a {@code -g:} option
 * javac rejects are not refused: such a compilation fails after the preparation, in javac or in the completion, like
 * any failed compilation, and the next preparation reads the pending inventory that compilation left to clean up after
 * it. Nor is an empty or null processor path beside a later {@code -proc:full} compiler argument when Gradle starts
 * javac from the command line: that javac takes the argument, which comes after Gradle's own {@code -proc:none}, and
 * looks for annotation processors on the class path.</p>
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
     * The statement of a Kotlin DSL build script that adds the directory the generation task {@code generation} writes
     * into back to the java sources of a source set, from inside that source set's block. The class it names is fully
     * qualified, and the rest comes from the build script and that block, so it can be pasted into the block as it
     * is.
     */
    static String kotlinGeneratedSourcesLine(String generation) {
        return "java.source(objects.sourceDirectorySet(\"graphmaquina\", \"StateGraph source\")"
                + ".srcDir(tasks.named(\"" + generation + "\","
                + " io.github.graphmaquina.gradle.GenerateGraphApi::class.java).flatMap { it.sourcesOutput }))";
    }

    /** The same statement in the Groovy DSL. */
    static String groovyGeneratedSourcesLine(String generation) {
        return "java.source(objects.sourceDirectorySet('graphmaquina', 'StateGraph source')"
                + ".srcDir(tasks.named('" + generation + "',"
                + " io.github.graphmaquina.gradle.GenerateGraphApi).flatMap { it.sourcesOutput }))";
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
         * The launcher the generation task of this source set runs the generator under, by convention the one of the
         * JDK javac compiles with, so every step of one compilation runs on the same JDK.
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
     * Retires the outputs of the previous cycle and invalidates the published index, before javac runs, once it has
     * checked the compilation's settings and sources: that a compilation javac rejects fails, that javac writes
     * classes and keeps their {@code SourceFile} attribute, that no {@code -proc:} or {@code -processor} compiler
     * argument, nor an empty or null processor path, keeps the StateGraph annotation processor from running, that
     * javac reads the sources annotation processors generate in the charset it writes them in, that the argument
     * providers change neither the release nor the encoding the generation was given, and that javac compiles what the
     * generation wrote and the original sources the generation read, and no others.
     */
    public abstract static class PrepareCompilation extends LifecycleAction {

        /** The StateGraph annotation processor, as a {@code -processor} list names it. */
        private static final String PROCESSOR = "io.github.graphmaquina.processor.StateGraphProcessor";

        /** The kinds of debugging information a {@code -g:} list javac accepts names. */
        private static final Set<String> DEBUG_INFORMATION = Set.of("lines", "vars", "source");

        /**
         * The original sources the generation task of this source set reads, empty when the source set takes no
         * part; {@code null} when no one gave them, which leaves them unchecked.
         */
        private FileCollection originalSources;

        /**
         * @param execOperations the service that starts the generator process
         */
        @Inject
        public PrepareCompilation(ExecOperations execOperations) {
            super(execOperations);
        }

        /**
         * Gives this step the original sources the generation task of this source set reads, which it compares with
         * the ones javac compiles.
         *
         * @param sources those sources, or an empty collection when the source set takes no part
         */
        void readOriginalSourcesFrom(FileCollection sources) {
            this.originalSources = sources;
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
         * class name, one that would read the sources annotation processors generate in another charset than javac
         * writes them in, one whose argument providers change the release or the encoding the generation was given,
         * one whose class files would lack the {@code SourceFile} attribute the completion step reads, one whose
         * original sources are not those the generation read, and one whose sources do not include what the
         * generation wrote.
         *
         * <p>javac takes the last {@code -proc:} option it is given. Gradle 9.2.1's Java compiler, in process or in
         * a forked compiler daemon, loads the annotation processors itself: it takes the last {@code -processor}
         * list, splits it on every comma, keeping empty names, runs only the processors the list names, takes each
         * name exactly as written and fails on the first one it cannot load. A javac Gradle starts from the command
         * line, as it does when {@code options.fork} is set with {@code forkOptions.javaHome} or
         * {@code forkOptions.executable}, reads the list itself and drops the empty names at its end, though a list
         * without a comma stays one name, even when it is empty; this check refuses every empty name whichever javac
         * runs. A well-formed name of a class that is not on the processor path, such as a misspelt one, passes this
         * check, and the compilation fails on it. Gradle gives javac a {@code -proc:none} of its own, ahead of the
         * compiler arguments, when {@code options.annotationProcessorPath} is empty or null, so that is refused too. A
         * later {@code -proc:full} changes nothing for Gradle's own Java compiler, in process or in its compiler
         * daemon, which hands javac the processors of that path alone, so it is refused there as well; only a javac
         * Gradle starts from the command line takes that argument and looks for processors on the class path, and that
         * compilation is left to run.</p>
         *
         * <p>Gradle's own Java compiler, in process or in its compiler daemon, reads the sources in
         * {@code options.encoding}, while javac writes the sources annotation processors generate in the last
         * {@code -encoding} it is given. That last {@code -encoding} is refused there when it names another charset
         * than {@code options.encoding} and this JVM supports both; an alias of the same charset passes. A javac Gradle
         * starts from the command line reads and writes in the last {@code -encoding}, so nothing is refused for
         * it.</p>
         *
         * <p>The release and the encoding the generation was given are derived from {@code options.compilerArgs} and
         * the compilation's options; those javac takes, from all the compiler arguments, the argument providers'
         * included, which run here. The two must give the same release, and the same charset, a request without an
         * encoding being read in UTF-8; a provider that repeats either passes, and so does a last {@code --release}
         * javac refuses, on which the compilation fails in javac.</p>
         *
         * <p>javac leaves the {@code SourceFile} attribute out of every class file once it is given {@code -g:none} or
         * a {@code -g:} list and no {@code -g:} list it is given names {@code source}. Gradle gives it
         * {@code -g:none} for {@code options.debug = false} and a {@code -g:} list for
         * {@code options.debugOptions.debugLevel}, ahead of the compiler arguments; a {@code -g:} option javac
         * rejects passes this check, and the compilation fails on it. The original sources javac compiles are the
         * Java sources of the compilation task's source outside the generated directory and the processor's source
         * output, and they must be exactly the ones the generation task of this source set reads; that is checked
         * before the generated sources are, so a source put in place of the source set's is reported the way the
         * generation reports it.</p>
         *
         * @param compilation the {@code JavaCompile} task this step belongs to
         * @throws GradleException if the compilation sets {@code options.failOnError = false}, passes
         *         {@code -proc:only} or {@code -proc:none} as its last {@code -proc:} compiler argument, has an
         *         {@code options.annotationProcessorPath} that is empty or null, unless javac runs from the command
         *         line with a {@code -proc:} compiler argument after Gradle's own {@code -proc:none}, passes a last
         *         {@code -processor} list without the StateGraph processor or with a name that is not a valid
         *         qualified class name, runs in process or in the compiler daemon with a last {@code -encoding}
         *         compiler argument that names another charset than {@code options.encoding}, when this JVM supports
         *         both, has argument providers that change the release or the encoding the generation was given, has a
         *         debug setting that leaves the {@code SourceFile} attribute out, compiles other original sources than
         *         those the generation task reads, or does not compile a source of the generated directory
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
            FileCollection processorPath = options.getAnnotationProcessorPath();
            boolean noProcessorPath = processorPath == null || processorPath.isEmpty();
            if (noProcessorPath && processing.isEmpty()) {
                throw new GradleException(emptyProcessorPath(compilation, "Gradle then gives javac -proc:none"));
            }
            if (noProcessorPath && !CompilerSettings.commandLine(options)) {
                throw new GradleException(emptyProcessorPath(compilation, "Gradle's Java compiler, in process or in"
                        + " its compiler daemon, runs only the annotation processors of"
                        + " options.annotationProcessorPath, whatever the compiler argument " + processing + " asks"));
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
            String declaredEncoding = options.getEncoding();
            Optional<String> lastEncoding = CompilerSettings.lastValue(arguments, "-encoding", false);
            if (declaredEncoding != null && lastEncoding.isPresent() && !CompilerSettings.commandLine(options)
                    && CompilerSettings.supported(declaredEncoding) && CompilerSettings.supported(lastEncoding.get())
                    && !CompilerSettings.sameCharset(declaredEncoding, lastEncoding.get())) {
                throw new GradleException("the StateGraph compilation of " + compilation + " cannot run with"
                        + " options.encoding = \"" + declaredEncoding + "\" and the compiler argument -encoding "
                        + lastEncoding.get() + ": Gradle's Java compiler, in process or in its compiler daemon, reads"
                        + " the sources in options.encoding, while javac writes the sources an annotation processor"
                        + " generates in the last -encoding it is given, so the sources annotation processors write,"
                        + " the StateGraph processor's among them, would be read back in another charset than they were"
                        + " written in, which garbles the characters the two charsets encode differently or fails the"
                        + " compilation. Remove that -encoding from the compiler arguments"
                        + " of " + compilation + ", or set options.encoding to the charset it names.");
            }
            List<String> declared = CompilerSettings.declaredArguments(options);
            Integer release = CompilerSettings.release(javaCompile, arguments);
            Integer declaredRelease = CompilerSettings.release(javaCompile, declared);
            if (!CompilerSettings.refusesLastRelease(arguments) && !Objects.equals(release, declaredRelease)) {
                String origin;
                if (CompilerSettings.releaseArgument(declared).isPresent()) {
                    origin = "the one options.compilerArgs give and the generation reads";
                } else if (options.getRelease().isPresent()) {
                    origin = "the one options.release gives and the generation reads";
                } else {
                    origin = "the language version of the compilation's toolchain, which the generation reads when"
                            + " options.release and options.compilerArgs give none";
                }
                throw new GradleException(changedByProviders(compilation, "make javac compile at release " + release
                        + " instead of release " + declaredRelease + ", " + origin, "--release", "options.release"));
            }
            String encoding = CompilerSettings.encoding(javaCompile, arguments);
            String generationEncoding = CompilerSettings.encoding(javaCompile, declared);
            if (!CompilerSettings.sameEncoding(generationEncoding, encoding)) {
                throw new GradleException(changedByProviders(compilation, "make javac read the sources in " + encoding
                        + " instead of " + (generationEncoding == null
                                ? "UTF-8, which the generator reads when options.encoding and options.compilerArgs"
                                        + " name no encoding"
                                : generationEncoding + ", the one options.encoding and options.compilerArgs give and"
                                        + " the generation reads"), "-encoding", "options.encoding"));
            }
            List<String> debugSettings = sourceFileDroppedBy(options, arguments);
            if (!debugSettings.isEmpty()) {
                throw new GradleException("the StateGraph compilation of " + compilation + " cannot run with "
                        + String.join(" and ", debugSettings) + ": javac then leaves the SourceFile attribute out of"
                        + " every class file, and the completion step links each class file to the source that"
                        + " declared it through that attribute, so it would fail with SG602. Remove "
                        + (debugSettings.size() == 1 ? "that setting" : "those settings") + " from " + compilation
                        + ", or add a -g: list that names source, such as -g:source,lines,vars, to its compiler"
                        + " arguments.");
            }
            Map<File, File> compiled = OriginalSources.withRoots(javaCompile.getSource());
            if (originalSources != null) {
                List<File> generatedDirectories = new ArrayList<>(getGeneratedSources().getFiles());
                File processorSources = options.getGeneratedSourceOutputDirectory().getAsFile().getOrNull();
                if (processorSources != null) {
                    generatedDirectories.add(processorSources);
                }
                OriginalSources.requireSame(originalSources.getAsFileTree().getFiles(), compiled,
                        generatedDirectories, getGenerationTask().get(), compilation.toString());
            }
            for (File directory : getGeneratedSources().getFiles()) {
                for (File generated : javaSources(directory)) {
                    if (!compiled.containsKey(generated)) {
                        throw new GradleException(notCompiled(compilation, generated, directory,
                                getGenerationTask().get()));
                    }
                }
            }
        }

        /**
         * Why {@code compilation} cannot run with an empty or null processor path, for {@code reason}, and the way
         * out.
         */
        private static String emptyProcessorPath(Task compilation, String reason) {
            return "the StateGraph compilation of " + compilation + " cannot run with an"
                    + " options.annotationProcessorPath that is empty or null: " + reason + ", so javac would run no"
                    + " annotation processor, and the"
                    + " StateGraph processor would write no index for the completion step to publish. Remove the"
                    + " setting that empties options.annotationProcessorPath from " + compilation + ", so that it holds"
                    + " the annotation processor path of the source set again.";
        }

        /**
         * Why {@code compilation} cannot run with the compiler arguments its argument providers give, which
         * {@code change} what javac takes, and how to give {@code option} instead, through {@code options.compilerArgs}
         * or {@code property}.
         */
        private static String changedByProviders(Task compilation, String change, String option, String property) {
            return "the StateGraph compilation of " + compilation + " cannot run with the compiler arguments its"
                    + " argument providers give: they " + change + ". The generation never runs the argument providers"
                    + " of a compilation, which may need files other tasks write before the compilation runs. Give "
                    + option + " in options.compilerArgs, or set " + property + ", instead of giving it in an argument"
                    + " provider.";
        }

        /**
         * The settings of {@code options} that make javac leave the {@code SourceFile} attribute out of every class
         * file, as a refusal names them; none when javac keeps that attribute, or when it rejects one of the
         * {@code -g:} options it is given.
         *
         * <p>javac leaves the attribute out once it is given {@code -g:none} or a {@code -g:} list and no {@code -g:}
         * list it is given names {@code source}, wherever those options stand: a plain {@code -g} changes nothing, and
         * {@code -g:none} does not take back a {@code source} another list names. Gradle 9.2.1 gives javac its own
         * such option ahead of the compiler arguments: {@code -g:none} for {@code options.debug = false}, else
         * {@code -g:} and the trimmed {@code options.debugOptions.debugLevel} when that is set, else {@code -g}. javac
         * accepts a {@code -g:} list whose words, split at every run of commas, are each one of {@code lines},
         * {@code vars} and {@code source}; it rejects any other {@code -g:} option but {@code -g:none}, and the
         * compilation then fails in javac.</p>
         */
        private static List<String> sourceFileDroppedBy(CompileOptions options, List<String> arguments) {
            List<String> given = new ArrayList<>();
            List<String> settings = new ArrayList<>();
            String level = options.getDebugOptions().getDebugLevel();
            if (!options.isDebug()) {
                given.add("-g:none");
                settings.add("options.debug = false");
            } else if (level != null) {
                given.add("-g:" + level.trim());
                settings.add("options.debugOptions.debugLevel = \"" + level + "\"");
            }
            for (String argument : arguments) {
                if (argument.startsWith("-g:")) {
                    given.add(argument);
                    settings.add("the compiler argument " + argument);
                }
            }
            boolean keepsSourceFile = given.isEmpty();
            for (String option : given) {
                if (option.equals("-g:none")) {
                    continue;
                }
                List<String> kinds = Arrays.asList(option.substring("-g:".length()).split(",+"));
                if (!DEBUG_INFORMATION.containsAll(kinds)) {
                    return List.of();
                }
                keepsSourceFile = keepsSourceFile || kinds.contains("source");
            }
            return keepsSourceFile ? List.of() : List.copyOf(settings);
        }

        /**
         * Why {@code compilation} does not compile {@code generated}, and the ways back: adding directories
         * instead of replacing them, or nesting the generated directory in the source set again, given for both
         * build script languages with fully qualified names so either can be pasted as it is. A source or a
         * pattern set on the compilation itself filters the generated directory whatever the source set holds, so
         * neither line undoes it: that setting has to be removed from the task.
         */
        private static String notCompiled(Task compilation, File generated, File directory, String generation) {
            String kotlin = kotlinGeneratedSourcesLine(generation);
            String groovy = groovyGeneratedSourcesLine(generation);
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
