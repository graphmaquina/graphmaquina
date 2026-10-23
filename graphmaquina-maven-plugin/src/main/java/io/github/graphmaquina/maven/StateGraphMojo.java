package io.github.graphmaquina.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.DefaultLifecycles;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What every StateGraph goal shares: the request of one compilation, the Java executable the generator runs
 * under, the classpath it runs over and the process that carries it.
 *
 * <p>The generator is never loaded into the plugin's realm. It runs as a separate process over the artifacts
 * this plugin itself declares — {@code graphmaquina-codegen} and what that pulls in — never over the dependencies
 * of the project being built, and its command line is fixed: {@code <operation> --request <file>}. Status
 * {@code 0} means the operation completed; any other status fails the build with what the generator reported on
 * its error stream, because a build that continued would compile against the vocabulary of a previous run.</p>
 *
 * <p>The request is serialized by the small encoder below rather than through the generator's own codec: this
 * plugin depends on the documented JSON shape, not on the {@code graphmaquina-codegen} classes. Strings are
 * written as UTF-8 text with only the escapes JSON requires — a quote, a backslash and every character below
 * {@code U+0020} — so a path carrying spaces, quotes, backslashes or non-ASCII characters round-trips exactly.</p>
 *
 * <p>Every directory is derived from the project model — its source roots, or the {@code <compileSourceRoots>} a
 * compiler execution declares in their place, its build directory and its class output directories — so a project
 * that moved them is generated for where it actually put them. A compiler execution that writes its classes
 * anywhere but the class output of the project is refused by the generation, since the completion reads them
 * there. The parameters are injected by name from the plugin descriptor; this class declares no annotations of
 * its own.</p>
 */
public abstract class StateGraphMojo extends AbstractMojo {

    /** The generator's entry point, as its command-line contract names it. */
    private static final String MAIN_CLASS = "io.github.graphmaquina.codegen.CodegenMain";

    /** The lowest Java feature release the generator and the compilation it prepares are supported on. */
    private static final int MINIMUM_LANGUAGE_VERSION = 21;

    /** The name of the request file every step of one compilation reads, inside that compilation's directory. */
    private static final String REQUEST = "request.json";

    /** The directory, under the build directory, that holds the manifests and inventories of a compilation. */
    private static final String WORK = "graphmaquina";

    /**
     * The names the default excludes of Ant 1.10 name outright, which the generator's walk of a whole root leaves out
     * beside the names {@code ._*}, {@code *~}, {@code #*#}, {@code .#*} and {@code %*%}: the files and directories
     * of version control systems, and the {@code .DS_Store} of macOS.
     */
    private static final Set<String> WALK_EXCLUDED_NAMES = Set.of("CVS", ".cvsignore", "SCCS", "vssver.scc", ".svn",
        ".git", ".gitattributes", ".gitignore", ".gitmodules", ".hg", ".hgignore", ".hgsub", ".hgsubstate", ".hgtags",
        ".bzr", ".bzrignore", ".DS_Store");

    /**
     * The directories the generator's walk of a whole root leaves out that the scanner of the compiler leaves out with
     * everything inside them as well, since an exclude ending in {@code /**} names each: those of the version control
     * systems both lists name. Nothing below one is read on either side.
     */
    private static final Set<String> PRUNED_ALIKE = Set.of("CVS", "SCCS", ".svn", ".bzr", ".hg", ".git");

    /** The project this compilation belongs to; injected from {@code ${project}}. */
    private MavenProject project;

    /** The session this build runs in; injected from {@code ${session}} and read by the toolchain manager. */
    private MavenSession session;

    /** The artifacts of this plugin; injected from {@code ${plugin.artifacts}} and never the project's own. */
    private List<Artifact> pluginArtifacts;

    /** An explicit {@code java} executable for the generator; injected from {@code ${graphmaquina.javaExecutable}}. */
    private String javaExecutable;

    /** An explicit compilation identity; injected from {@code ${graphmaquina.compilationId}}. */
    private String compilationId;

    /**
     * The toolchains of the build, injected as a descriptor requirement: it both reports the {@code jdk}
     * toolchain the build selected and resolves the one a compiler execution requires for itself.
     */
    private ToolchainManager toolchainManager;

    /**
     * The lifecycles of the build, injected as a descriptor requirement: an execution of the compiler runs between the
     * preparation and the completion only when it is bound to a phase of the default one.
     */
    private DefaultLifecycles lifecycles;

    /**
     * The project this goal runs for.
     *
     * @return the project being built
     */
    protected final MavenProject project() {
        return project;
    }

    /**
     * Whether this build compiles nothing for {@code execution}, in which case the calling goal does nothing and
     * says so at info level. That is a project packaged as {@code pom}, which binds no compiler at all — the
     * parent of a multi-module build runs the executions its modules inherit from it — a compilation for which the
     * default lifecycle runs no execution of the compiler's goal, as when {@code default-compile} is bound to the phase
     * {@code none}, or to a phase of another lifecycle such as {@code pre-site}, and nothing else compiles the main
     * sources, and a compilation the build switched off the way maven-compiler-plugin skips its own goal: by
     * {@code maven.main.skip} or {@code <skipMain>} for the main one, by {@code maven.test.skip} or {@code <skip>}
     * for the test one. Nothing is generated, retired or published then, so the outputs of the previous cycle stay
     * exactly as the compiler leaves them.
     *
     * @param execution the compiler execution the calling goal belongs to
     * @return {@code true} if the calling goal must return without doing anything
     */
    final boolean skips(CompilerExecution execution) {
        String reason;
        if ("pom".equals(project.getPackaging())) {
            reason = "the project is packaged as pom, which compiles no Java";
        } else if (CompilerConfiguration.idle(project, execution, lifecycles).isPresent()) {
            reason = CompilerConfiguration.idle(project, execution, lifecycles).get();
        } else {
            CompilerConfiguration compiler = compiler(execution);
            Optional<String> skip = compiler.skip();
            if (skip.isEmpty()) {
                return false;
            }
            reason = skip.get() + " switches the " + compiler.id() + " compilation off";
        }
        getLog().info("StateGraph skips " + compilationId(execution.sourceSet()) + ": " + reason);
        return true;
    }

    /**
     * The request of the {@code main} compilation: the original sources of the project — the source roots javac
     * compiles, or the files of them the includes and excludes of the compiler select — its compile classpath
     * without its own class output, the directories this plugin and the compiler write into, and the release and
     * the encoding the compiler reads the sources with.
     *
     * @return the request the generator reads for the main source set
     * @throws MojoExecutionException if Maven has not resolved the compile classpath of the project, or a source
     *     root, or a directory below it, fails while it is being listed
     */
    protected final Request mainRequest() throws MojoExecutionException {
        Path build = buildDirectory();
        Path sourcesOutput = generatedSources(CompilerExecution.MAIN);
        Path processorSourcesOutput = build.resolve("generated-sources").resolve("annotations");
        Path classesOutput = absolute(project.getBuild().getOutputDirectory());
        CompilerConfiguration compiler = compiler(CompilerExecution.MAIN);
        return new Request(
            project.getBasedir().getAbsolutePath(),
            originalSources(CompilerExecution.MAIN,
                compiler.compileSourceRoots().orElse(project.getCompileSourceRoots()), sourcesOutput,
                processorSourcesOutput),
            applicationClasspath(compileClasspathElements(), classesOutput),
            sourcesOutput.toString(),
            processorSourcesOutput.toString(),
            classesOutput.toString(),
            build.resolve(WORK).resolve("main").toString(),
            compilationId("main"),
            compiler.release().orElse(null),
            compiler.encoding(toolchainManager, session).orElse(null));
    }

    /**
     * The request of the {@code test} compilation. Its classpath keeps the main class output — the test
     * compilation resolves against it — and drops only the directory this compilation writes itself.
     *
     * @return the request the generator reads for the test source set
     * @throws MojoExecutionException if Maven has not resolved the test classpath of the project, or a source
     *     root, or a directory below it, fails while it is being listed
     */
    protected final Request testRequest() throws MojoExecutionException {
        Path build = buildDirectory();
        Path sourcesOutput = generatedSources(CompilerExecution.TEST);
        Path processorSourcesOutput = build.resolve("generated-test-sources").resolve("test-annotations");
        Path classesOutput = absolute(project.getBuild().getTestOutputDirectory());
        CompilerConfiguration compiler = compiler(CompilerExecution.TEST);
        return new Request(
            project.getBasedir().getAbsolutePath(),
            originalSources(CompilerExecution.TEST,
                compiler.compileSourceRoots().orElse(project.getTestCompileSourceRoots()), sourcesOutput,
                processorSourcesOutput),
            applicationClasspath(testClasspathElements(), classesOutput),
            sourcesOutput.toString(),
            processorSourcesOutput.toString(),
            classesOutput.toString(),
            build.resolve(WORK).resolve("test").toString(),
            compilationId("test"),
            compiler.release().orElse(null),
            compiler.encoding(toolchainManager, session).orElse(null));
    }

    /**
     * Refuses to generate for a compilation the compiler is not going to run the way this request describes: in two
     * running executions of its goal that both compile the generated vocabulary, before the preparation or after the
     * completion of this compilation, under another JDK, under a release the generated vocabulary does not compile
     * with, with annotation processing off, without the annotation processor on the processor path or in the
     * {@code -processor} list javac takes, with a processor module path, with the annotation processor only on the
     * class path of a javac that would not run it, without writing its classes or past a failure of javac, writing its
     * classes without the {@code SourceFile} attribute or anywhere but the class output of the project, over source
     * roots that leave out the directory the generator writes into, over only part of the module, with includes or
     * excludes that leave out the carrier of the compilation, over another annotation manifest, writing the sources of
     * the annotation processor somewhere this plugin does not own, or, inside Maven, writing them in another charset
     * than it reads them back in. Nothing has been written when this fails.
     *
     * @param execution the compiler execution this generation belongs to
     * @param request the request the generator is about to run over
     * @throws MojoExecutionException naming both sides of the disagreement and the configuration that fixes it
     */
    protected final void verifyCompiler(CompilerExecution execution, Request request)
            throws MojoExecutionException {
        CompilerConfiguration compiler = compiler(execution);
        compiler.requireOneCompilingExecution(request);
        compiler.requireCompilerBetweenPreparationAndCompletion(project, lifecycles);
        CompilerConfiguration.Javac javac = compiler.requireAgreementWith(javaExecutable(), toolchainManager,
            session);
        compiler.requireAnnotationProcessor(project, javac);
        compiler.requireCompiledClasses();
        compiler.requireSourceFileAttribute();
        compiler.requireClassesOutput(request, javac);
        compiler.requireGeneratedSourcesCompiled(request);
        compiler.requireWholeModuleCompilation();
        compiler.requireCarrierSelected(request);
        compiler.requireManifestArgument(request, javac);
        compiler.requireProcessorSourcesOutput(request, javac);
        compiler.requireOneSourceEncoding(javac);
    }

    /**
     * Retires everything the previous cycle of this compilation owned and leaves the compiler no choice but to
     * run again.
     *
     * <p>The newest output of the previous compilation is read before the retirement, because the retirement may
     * remove it; the carrier the generation of this cycle emitted is dated past it afterwards, for the reason
     * {@code CarrierTimestamp} gives. A goal whose compilation the build switched off never calls this: see
     * {@code skips}.</p>
     *
     * @param request the request of this compilation, as the calling goal builds it from the project model; it is
     *     written again before the generator reads it
     * @throws MojoExecutionException if {@link #run running} the generator fails, or the carrier of this
     *     generation cannot be found or dated
     */
    protected final void prepare(Request request) throws MojoExecutionException {
        Path sourcesOutput = Path.of(request.sourcesOutput());
        try {
            FileTime newest = CarrierTimestamp.newestOutput(Path.of(request.classesOutput()));
            run("prepare-compile", request);
            if (CarrierTimestamp.refresh(sourcesOutput, newest).isEmpty()) {
                throw new MojoExecutionException("there is no compilation carrier of " + request.compilationId()
                    + " under " + sourcesOutput + ", although every generation emits one there: javac would compile"
                    + " this compilation without it, and the completion, which needs the class of the carrier, would"
                    + " fail. Run the generation of this compilation again.");
            }
        } catch (IOException failure) {
            throw new MojoExecutionException("cannot date the compilation carrier of "
                + request.compilationId() + " under " + sourcesOutput + " past the outputs of the previous"
                + " compilation", failure);
        }
    }

    /**
     * Publishes what javac accepted and seals the inventory of this compilation.
     *
     * <p>Maven runs this after the compiler goal bound to the same phase and stops the build at the first
     * failure, so a compilation javac rejected never reaches this step: the generation refuses
     * {@code failOnError=false} and {@code proc=only}, the settings that would let a compilation without
     * classes through. A compilation javac never ran at all — one whose sources the compiler found up to date —
     * leaves no provisional index behind, and completing without one would be completing a compilation that
     * did not happen: when no pending inventory is left either, the step says so and does nothing. A pending
     * inventory without a provisional index means the previous outputs were retired and the annotation processor
     * published nothing in their place: the step fails.</p>
     *
     * @param request the request of this compilation, as the calling goal builds it from the project model; it is
     *     written again before the generator reads it
     * @throws MojoExecutionException if {@link #run running} the generator fails, or if a pending inventory is
     *     left without a provisional index
     */
    protected final void complete(Request request) throws MojoExecutionException {
        Path pendingIndex = request.pendingIndexFile();
        if (Files.isRegularFile(pendingIndex)) {
            run("complete-compile", request);
            return;
        }
        Path pendingInventory = request.pendingInventoryFile();
        if (Files.isRegularFile(pendingInventory)) {
            throw new MojoExecutionException("this build prepared the compilation of "
                + request.compilationId() + " — " + pendingInventory + " is there, so its previous outputs"
                + " have been retired — but javac left no " + pendingIndex
                + ". The StateGraph annotation processor did not run: it is what publishes the index of an"
                + " accepted compilation. Check that " + ProcessorPathRequirements.PROCESSOR
                + " is on the <annotationProcessorPaths> of this compilation and that annotation processing"
                + " is not switched off.");
        }
        getLog().info("StateGraph completes nothing for " + request.compilationId() + ": there is no "
            + pendingIndex + " and no " + pendingInventory
            + ", so javac did not compile this source set in this build");
    }

    /**
     * Writes {@code request} into its own work directory and runs {@code operation} of the generator over it.
     *
     * @param operation one of {@code generate}, {@code prepare-compile} and {@code complete-compile}
     * @param request the request of the compilation this goal belongs to
     * @throws MojoExecutionException if the request cannot be written, if no {@code java} the generator can run on
     *     is found, or if the generator cannot be started, is interrupted or leaves a nonzero status
     */
    protected final void run(String operation, Request request) throws MojoExecutionException {
        Path requestFile = Path.of(request.workDirectory()).resolve(REQUEST);
        try {
            Files.createDirectories(requestFile.getParent());
            Files.writeString(requestFile, encode(request), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new MojoExecutionException("cannot write the StateGraph request " + requestFile, failure);
        }
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(String.join(File.pathSeparator, generatorClasspath()));
        command.add(MAIN_CLASS);
        command.add(operation);
        command.add("--request");
        command.add(requestFile.toString());
        getLog().debug("running the StateGraph generator: " + String.join(" ", command));
        execute(operation, requestFile, command);
    }

    /** Starts the generator, forwards what it printed and turns a nonzero status into a build failure. */
    private void execute(String operation, Path requestFile, List<String> command)
            throws MojoExecutionException {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(project.getBasedir())
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .start();
            String diagnostics;
            try (InputStream errors = process.getErrorStream()) {
                diagnostics = new String(errors.readAllBytes(), StandardCharsets.UTF_8).strip();
            }
            int status = process.waitFor();
            if (status != 0) {
                throw new MojoExecutionException("the StateGraph generator failed with status " + status
                    + " running '" + operation + "' for " + requestFile
                    + (diagnostics.isEmpty() ? "" : System.lineSeparator() + diagnostics));
            }
            if (!diagnostics.isEmpty()) {
                // The operation completed, so this is not a failure; it is still the only place the generator
                // has to report something a build should look at, and a silent error stream loses it.
                getLog().warn("the StateGraph generator reported while running '" + operation + "': "
                    + diagnostics);
            }
        } catch (IOException failure) {
            throw new MojoExecutionException("cannot run the StateGraph generator: "
                + String.join(" ", command), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("interrupted while running the StateGraph generator '"
                + operation + "' for " + requestFile, interrupted);
        }
    }

    /**
     * The {@code java} executable the generator runs under: the configured one, else the one the selected
     * {@code jdk} toolchain provides, else the one of the JVM running Maven — whichever it is, once it is known
     * to belong to a JDK the generator can run on.
     */
    private String javaExecutable() throws MojoExecutionException {
        if (javaExecutable != null && !javaExecutable.isBlank()) {
            return requireJdk(javaExecutable, "the configured <javaExecutable>");
        }
        Toolchain toolchain = toolchainManager.getToolchainFromBuildContext("jdk", session);
        if (toolchain != null) {
            String tool = toolchain.findTool("java");
            if (tool == null) {
                throw new MojoExecutionException("the jdk toolchain selected for this build, " + toolchain
                    + ", provides no 'java' tool, so the StateGraph generator cannot be started."
                    + " Select a jdk toolchain that provides one, or configure <javaExecutable>.");
            }
            return requireJdk(tool, "the java of the jdk toolchain selected for this build");
        }
        return requireJdk(Path.of(System.getProperty("java.home"), "bin", executableName("java")).toString(),
            "the java of the JVM running Maven");
    }

    /**
     * Refuses a {@code java} the generator cannot run on: one that names no file, on Windows neither as written nor
     * with the {@code .exe} that the process start adds to a file name without an extension; one with no {@code javac}
     * beside it, which belongs to a runtime without the jdk.compiler module the generator parses sources with;
     * and one whose JDK declares a release below 21 in its {@code release} file. A JDK without a readable
     * release file is not refused on its release. The JVM running Maven always passes that last check: the
     * descriptor of this plugin requires Java 21, so Maven refuses an older JVM before it loads a goal.
     *
     * @param java the executable, as the build configured or selected it
     * @param origin where the build took it from, as a failure names it
     * @return {@code java} itself
     * @throws MojoExecutionException naming the executable and what it lacks
     */
    private String requireJdk(String java, String origin) throws MojoExecutionException {
        String advice = " Point <javaExecutable>, or the jdk toolchain of the build, at the java of a JDK "
            + MINIMUM_LANGUAGE_VERSION + " or later.";
        if (!Files.isRegularFile(Jdk.launcher(java, project.getBasedir().toPath()))) {
            throw new MojoExecutionException("the StateGraph generator cannot be started, since " + origin
                + ", " + java + ", does not exist or is not a file." + advice);
        }
        Path home = Jdk.home(java, project.getBasedir().toPath());
        Path compiler = home.resolve("bin").resolve(executableName("javac"));
        if (!Files.isRegularFile(compiler)) {
            throw new MojoExecutionException("the StateGraph generator needs the jdk.compiler module, but "
                + origin + ", " + java + ", has no " + compiler.getFileName() + " beside it, so it belongs to a"
                + " runtime, not a JDK." + advice);
        }
        int release = Jdk.release(home);
        if (release > 0 && release < MINIMUM_LANGUAGE_VERSION) {
            throw new MojoExecutionException("the StateGraph generator needs Java " + MINIMUM_LANGUAGE_VERSION
                + " or later, but " + origin + ", " + java + ", belongs to Java " + release + "." + advice);
        }
        return java;
    }

    /** The name of a JDK tool on this operating system. */
    private static String executableName(String tool) {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
            ? tool + ".exe"
            : tool;
    }

    /**
     * The classpath the generator runs over: the artifacts Maven resolved for this plugin from its own POM —
     * the generator, what the generator pulls in, and this plugin's own jar, which is inert there. The
     * dependencies of the project being built are never on it: they are what the generator reads, not what it
     * runs with.
     */
    private List<String> generatorClasspath() {
        List<String> entries = new ArrayList<>();
        for (Artifact artifact : pluginArtifacts) {
            File file = artifact.getFile();
            if (file != null) {
                entries.add(file.getAbsolutePath());
            }
        }
        return entries;
    }

    /** The identity of this compilation: the configured one, else {@code groupId:artifactId:sourceSet}. */
    private String compilationId(String sourceSet) {
        if (compilationId != null && !compilationId.isBlank()) {
            return compilationId;
        }
        return project.getGroupId() + ':' + project.getArtifactId() + ':' + sourceSet;
    }

    /** The build directory of the project, as an absolute path. */
    private Path buildDirectory() {
        return absolute(project.getBuild().getDirectory());
    }

    /** The directory the generator writes the vocabulary of {@code execution} into, as an absolute path. */
    private Path generatedSources(CompilerExecution execution) {
        return buildDirectory().resolve(execution == CompilerExecution.MAIN ? "generated-sources"
            : "generated-test-sources").resolve(WORK);
    }

    /**
     * The configuration of the execution of the compiler that compiles {@code execution}, as
     * {@link CompilerConfiguration#of} picks it. Every goal asks {@link #skips} first, which skips a compilation the
     * default lifecycle runs no execution for, so there is one.
     */
    private CompilerConfiguration compiler(CompilerExecution execution) {
        return CompilerConfiguration.of(project, session, execution, lifecycles, generatedSources(execution))
            .orElseThrow(() -> new IllegalStateException("maven-compiler-plugin runs no execution of its "
                + execution.goal() + " goal in the default lifecycle of this project, which the goal should have"
                + " skipped"));
    }

    /**
     * The sources the generator parses: every source root javac compiles — the {@code <compileSourceRoots>} of the
     * compiler execution when it declares them, which replace the source roots of the project, else those — except
     * the two directories this build generates into, which its own outputs must never overlap, whatever symbolic links
     * their names lead through. A root the execution does not narrow is handed to the generator whole, and the
     * generator walks it with Ant's default excludes, not with plexus-utils' longer list; {@code SourcePatterns}
     * describes what that leaves in. That walk leaves out, as Gradle does, every directory whose name one of those
     * excludes names, with everything inside it, where the scanner of the compiler enters it, and fails on a directory
     * it cannot list, which that scanner reads as an empty one, so a root under which javac compiles a source inside
     * such a directory, or the walk would fail, is handed over as the files the compiler selects instead. A root the
     * includes or excludes of the compiler narrow is handed over as the very files they select, with the compiler's
     * own default excludes left out. Either is handed over under the name
     * {@link #spelling} gives it, which anchors the origins the generator publishes to the project directory.
     */
    private List<String> originalSources(CompilerExecution execution, List<String> roots, Path sourcesOutput,
            Path processorSourcesOutput) throws MojoExecutionException {
        CompilerConfiguration compiler = compiler(execution);
        SourcePatterns patterns = compiler.sourcePatterns();
        Set<Path> generated = Set.of(CompilerConfiguration.canonical(sourcesOutput),
            CompilerConfiguration.canonical(processorSourcesOutput));
        Path projectDirectory = Path.of(project.getBasedir().getAbsolutePath()).normalize();
        Path canonicalProject = CompilerConfiguration.canonical(projectDirectory);
        Set<String> sources = new LinkedHashSet<>();
        for (String root : roots) {
            Path declared = Path.of(root).isAbsolute() ? Path.of(root) : project.getBasedir().toPath().resolve(root);
            Path canonicalRoot = CompilerConfiguration.canonical(declared);
            if (generated.contains(canonicalRoot)) {
                continue;
            }
            Path source = spelling(declared, canonicalRoot, projectDirectory, canonicalProject);
            try {
                if (!patterns.narrow() && !walkedOtherwise(source, patterns)) {
                    sources.add(source.toString());
                    continue;
                }
                for (Path file : patterns.select(source)) {
                    sources.add(file.toString());
                }
            } catch (IOException failure) {
                throw new MojoExecutionException("cannot read the sources under " + source + " that the "
                    + compiler.id() + " execution compiles", failure);
            }
        }
        return List.copyOf(sources);
    }

    /**
     * Whether the generator, handed {@code root} whole, would walk it otherwise than the scanner of the compiler does:
     * whether javac compiles a source under it that the walk leaves out, one the scanner selects inside a directory
     * whose name a default exclude of Ant names, such as {@code old~} or {@code .gitignore}, or the walk would fail on
     * a directory it cannot list, which the scanner reads as an empty one. plexus-utils enters a directory such a name
     * names and leaves out only the files those patterns name, while the generator's walk, like Gradle's, leaves the
     * directory out with everything inside it; that walk opens every directory it reaches, such a one included. A
     * directory both leave out with everything inside it, such as {@code .git}, is not entered.
     *
     * @param root a source root of the compilation
     * @param patterns the includes and excludes of the compiler execution, which narrow no root
     * @return {@code true} if javac compiles such a source or the walk would fail on such a directory
     * @throws IOException if a directory fails while it is being listed
     */
    private static boolean walkedOtherwise(Path root, SourcePatterns patterns) throws IOException {
        if (!Files.isDirectory(root)) {
            return false;
        }
        List<Path> differences = new ArrayList<>();
        Files.walkFileTree(root, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
            new SimpleFileVisitor<Path>() {

                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    return !directory.equals(root) && PRUNED_ALIKE.contains(directory.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (!attributes.isRegularFile() || !file.getFileName().toString().endsWith(".java")) {
                        return FileVisitResult.CONTINUE;
                    }
                    List<String> names = names(root, file);
                    if (insideWhatTheWalkExcludes(names) && patterns.selects(String.join("/", names))) {
                        differences.add(file);
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    if (failure instanceof FileSystemLoopException || insideWhatTheWalkExcludes(names(root, file))) {
                        return FileVisitResult.CONTINUE;
                    }
                    differences.add(file);
                    return FileVisitResult.TERMINATE;
                }
            });
        return !differences.isEmpty();
    }

    /** The names of the path from {@code root} to {@code path}; a single empty name for {@code root} itself. */
    private static List<String> names(Path root, Path path) {
        List<String> names = new ArrayList<>();
        for (Path name : root.relativize(path)) {
            names.add(name.toString());
        }
        return names;
    }

    /**
     * Whether the path of {@code names} lies inside a directory the generator's walk of a whole root leaves out:
     * whether one of the names before its last one is.
     */
    private static boolean insideWhatTheWalkExcludes(List<String> names) {
        return names.subList(0, names.size() - 1).stream().anyMatch(StateGraphMojo::walkExcludes);
    }

    /**
     * Whether the generator's walk of a whole root leaves out a directory named {@code name}, with everything inside
     * it: whether a default exclude of Ant names it, as {@code ._*}, {@code *~}, {@code #*#}, {@code .#*},
     * {@code %*%} or one of {@link #WALK_EXCLUDED_NAMES}.
     */
    private static boolean walkExcludes(String name) {
        return name.startsWith("._")
            || WALK_EXCLUDED_NAMES.contains(name)
            || name.endsWith("~")
            || (name.length() > 1 && name.startsWith("#") && name.endsWith("#"))
            || name.startsWith(".#")
            || (name.length() > 1 && name.startsWith("%") && name.endsWith("%"));
    }

    /**
     * The name the generator is handed for a source root. That is the root as the build spells it, normalized, unless
     * a {@code ..} after a symbolic link makes that name another directory, in which case it is the root as the file
     * system resolves it. A name that lies in the project directory as the request spells it is kept. Any other name
     * is replaced by the project directory as the request spells it followed by the path from the project directory
     * to the root once symbolic links are resolved, {@code ..} included, when three things hold: that path differs
     * from the one the name gives, the file system resolves the new name to the root itself, and the new name,
     * normalized lexically as the generator normalizes it, still gives that path. The last fails where the generator
     * would read the new name as another directory, as when the project directory is spelled through a link whose
     * path has fewer names than that path has {@code ..}: normalization drops the {@code ..} that would climb above
     * the root of the file system.
     *
     * <p>The generator publishes the origin of every source relative to the project directory as the request spells
     * it, lexically, and the absolute path of a source on another drive. So the origin of a source under a root named
     * outside the project directory, on the same drive, starts with the path from the project directory to the root
     * once symbolic links are resolved, unless the new name fails one of the last two conditions, in which case it
     * starts with the path from the project directory to the name the root keeps, which, read lexically, still leads
     * from the project directory to the root. The origin of a source under a root named in the project directory
     * starts with that name, which a symbolic link in the project directory can make differ from the resolved
     * path.</p>
     *
     * @param root the root as the build spells it, absolute
     * @param canonicalRoot the root as {@code CompilerConfiguration.canonical} resolves it
     * @param projectDirectory the project directory as the request spells it, normalized
     * @param canonicalProject the project directory as {@code CompilerConfiguration.canonical} resolves it
     * @return the name to hand the generator for that root
     */
    private static Path spelling(Path root, Path canonicalRoot, Path projectDirectory, Path canonicalProject) {
        Path spelled = root.normalize();
        if (!CompilerConfiguration.canonical(spelled).equals(canonicalRoot)) {
            spelled = canonicalRoot;
        }
        if (spelled.startsWith(projectDirectory)) {
            return spelled;
        }
        try {
            Path resolved = canonicalProject.relativize(canonicalRoot);
            if (projectDirectory.relativize(spelled).equals(resolved)) {
                return spelled;
            }
            // Not normalized: the generator relativizes the name lexically, while a file system that resolves the link
            // a project directory may be spelled through before the .. that leaves it reads the root through it. Where
            // the file system resolves .. first instead, the name reaches another directory and is not used.
            Path anchored = projectDirectory.resolve(resolved);
            boolean readAsResolved = projectDirectory.relativize(anchored.normalize()).equals(resolved);
            return readAsResolved && CompilerConfiguration.canonical(anchored).equals(canonicalRoot)
                ? anchored
                : spelled;
        } catch (IllegalArgumentException otherRoot) {
            // A root on another drive cannot be named from the project directory.
            return spelled;
        }
    }

    /** The classpath the generator resolves headers and published indexes against, without {@code own}. */
    private List<String> applicationClasspath(List<String> elements, Path own) {
        Set<String> classpath = new LinkedHashSet<>();
        for (String element : elements) {
            Path entry = absolute(element);
            if (!entry.equals(own)) {
                classpath.add(entry.toString());
            }
        }
        return List.copyOf(classpath);
    }

    private List<String> compileClasspathElements() throws MojoExecutionException {
        try {
            return project.getCompileClasspathElements();
        } catch (DependencyResolutionRequiredException failure) {
            throw new MojoExecutionException("the compile classpath of " + project.getArtifactId()
                + " is not resolved, so the StateGraph generator cannot read the declarations it depends on",
                failure);
        }
    }

    private List<String> testClasspathElements() throws MojoExecutionException {
        try {
            return project.getTestClasspathElements();
        } catch (DependencyResolutionRequiredException failure) {
            throw new MojoExecutionException("the test classpath of " + project.getArtifactId()
                + " is not resolved, so the StateGraph generator cannot read the declarations it depends on",
                failure);
        }
    }

    /** {@code path} against the project directory when the project model left it relative. */
    private Path absolute(String path) {
        Path candidate = Path.of(path);
        return (candidate.isAbsolute() ? candidate : project.getBasedir().toPath().resolve(candidate))
                .normalize();
    }

    /**
     * Encodes {@code request} as the flat JSON object documented by the generator's manifest codec: the eight
     * members every request carries, then the release and the encoding of the compilation when they are known, in
     * the order the codec lists them.
     *
     * @param request the values of the request
     * @return the JSON document, ending with a newline
     */
    static String encode(Request request) {
        Objects.requireNonNull(request, "request");
        StringBuilder json = new StringBuilder(512);
        json.append("{\n");
        member(json, "projectDirectory", request.projectDirectory()).append(",\n");
        array(json, "sources", request.sources()).append(",\n");
        array(json, "classpath", request.classpath()).append(",\n");
        member(json, "sourcesOutput", request.sourcesOutput()).append(",\n");
        member(json, "processorSourcesOutput", request.processorSourcesOutput()).append(",\n");
        member(json, "classesOutput", request.classesOutput()).append(",\n");
        member(json, "workDirectory", request.workDirectory()).append(",\n");
        member(json, "compilationId", request.compilationId());
        if (request.release() != null) {
            json.append(",\n  ");
            string(json, "release").append(": ").append(request.release().intValue());
        }
        if (request.encoding() != null) {
            json.append(",\n");
            member(json, "encoding", request.encoding());
        }
        json.append("\n}\n");
        return json.toString();
    }

    private static StringBuilder member(StringBuilder json, String name, String value) {
        json.append("  ");
        string(json, name).append(": ");
        return string(json, value);
    }

    private static StringBuilder array(StringBuilder json, String name, List<String> values) {
        json.append("  ");
        string(json, name).append(": [");
        for (int index = 0; index < values.size(); index++) {
            json.append(index == 0 ? "\n    " : ",\n    ");
            string(json, values.get(index));
        }
        return json.append(values.isEmpty() ? "]" : "\n  ]");
    }

    /**
     * Appends {@code value} as a JSON string. Only what JSON forbids unescaped is escaped: the quote, the
     * backslash and every character below {@code U+0020}; everything else, non-ASCII included, is written as
     * itself and carried by the UTF-8 encoding of the file.
     */
    private static StringBuilder string(StringBuilder json, String value) {
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append(String.format("\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        return json.append('"');
    }

    /**
     * The values of one generation request, as the generator's flat JSON object carries them. Every path is an
     * absolute execution path, written exactly as the project model resolved it.
     *
     * @param projectDirectory the directory the compilation belongs to
     * @param sources the original source roots, or the files of them the compiler selects, which never overlap
     *     an output directory
     * @param classpath the compile classpath the generator resolves headers and published indexes against
     * @param sourcesOutput where the generated vocabulary is written
     * @param processorSourcesOutput where the annotation processor writes its own sources
     * @param classesOutput where javac writes the classes of this compilation
     * @param workDirectory where the manifests and the inventories of this compilation live
     * @param compilationId the identity of the project and source set: the configured one, else
     *     {@code groupId:artifactId:sourceSet}
     * @param release the Java release javac compiles the source set at, or {@code null} when the compiler names
     *     none this plugin understands, which the generator reads as release 21
     * @param encoding the name of the charset javac reads the sources in, or {@code null} when the build names
     *     none, which the generator reads as UTF-8
     */
    public record Request(String projectDirectory, List<String> sources, List<String> classpath,
            String sourcesOutput, String processorSourcesOutput, String classesOutput, String workDirectory,
            String compilationId, Integer release, String encoding) {

        /** The file name the generator writes the manifest of a compilation under, in its work directory. */
        private static final String MANIFEST = "annotation-manifest.json";

        /** The index the annotation processor publishes for a compilation javac has not yet accepted. */
        private static final String PENDING_INDEX = "META-INF/graphmaquina/pending-exports.json";

        /** The inventory the preparation of a cycle writes before it retires the first output of the previous one. */
        private static final String PENDING_INVENTORY = "owned-outputs.pending.json";

        /** Copies the two lists, so a request never reflects a later change of the collection it was built from. */
        public Request {
            Objects.requireNonNull(projectDirectory, "projectDirectory");
            sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
            classpath = List.copyOf(Objects.requireNonNull(classpath, "classpath"));
            Objects.requireNonNull(sourcesOutput, "sourcesOutput");
            Objects.requireNonNull(processorSourcesOutput, "processorSourcesOutput");
            Objects.requireNonNull(classesOutput, "classesOutput");
            Objects.requireNonNull(workDirectory, "workDirectory");
            Objects.requireNonNull(compilationId, "compilationId");
        }

        /**
         * A request that names neither the release nor the encoding of the compilation.
         *
         * @param projectDirectory the directory the compilation belongs to
         * @param sources the original source roots, or the files of them the compiler selects
         * @param classpath the compile classpath
         * @param sourcesOutput where the generated vocabulary is written
         * @param processorSourcesOutput where the annotation processor writes its own sources
         * @param classesOutput where javac writes the classes of this compilation
         * @param workDirectory where the manifests and the inventories of this compilation live
         * @param compilationId the identity of the project and source set
         */
        public Request(String projectDirectory, List<String> sources, List<String> classpath,
                String sourcesOutput, String processorSourcesOutput, String classesOutput, String workDirectory,
                String compilationId) {
            this(projectDirectory, sources, classpath, sourcesOutput, processorSourcesOutput, classesOutput,
                workDirectory, compilationId, null, null);
        }

        /**
         * The manifest of this compilation, which the generator writes and the annotation processor reads.
         *
         * @return the absolute path of the annotation manifest
         */
        public Path manifestFile() {
            return Path.of(workDirectory).toAbsolutePath().normalize().resolve(MANIFEST);
        }

        /**
         * The provisional index of this compilation, which the annotation processor writes during javac and the
         * completion, or else the next preparation, removes.
         *
         * @return the absolute path of the provisional index
         */
        public Path pendingIndexFile() {
            return Path.of(classesOutput).toAbsolutePath().normalize().resolve(PENDING_INDEX);
        }

        /**
         * The inventory of what this cycle retired, which the preparation writes before it retires anything and
         * the completion removes; a cycle that never completes leaves it to the next preparation.
         *
         * @return the absolute path of the pending inventory
         */
        public Path pendingInventoryFile() {
            return Path.of(workDirectory).toAbsolutePath().normalize().resolve(PENDING_INVENTORY);
        }
    }
}
