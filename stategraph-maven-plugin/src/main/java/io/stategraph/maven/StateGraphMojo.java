package io.stategraph.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * What every StateGraph goal shares: the request of one compilation, the Java executable the generator runs
 * under, the classpath it runs over and the process that carries it.
 *
 * <p>The generator is never loaded into the plugin's realm. It runs as a separate process over the artifacts
 * this plugin itself declares — {@code stategraph-codegen} and what that pulls in — never over the dependencies
 * of the project being built, and its command line is fixed: {@code <operation> --request <file>}. Status
 * {@code 0} means the operation completed; any other status fails the build with what the generator reported on
 * its error stream, because a build that continued would compile against the vocabulary of a previous run.</p>
 *
 * <p>The request is serialized by the small encoder below rather than through the generator's own codec: this
 * plugin depends on the documented JSON shape, not on the {@code stategraph-codegen} classes. Strings are
 * written as UTF-8 text with only the escapes JSON requires — a quote, a backslash and every character below
 * {@code U+0020} — so a path carrying spaces, quotes, backslashes or non-ASCII characters round-trips exactly.</p>
 *
 * <p>Every directory is derived from the project model — its source roots, its build directory and its class
 * output directories — so a project that moved them is generated for where it actually put them. The parameters
 * are injected by name from the plugin descriptor; this class declares no annotations of its own.</p>
 */
public abstract class StateGraphMojo extends AbstractMojo {

    /** The generator's entry point, as its command-line contract names it. */
    private static final String MAIN_CLASS = "io.stategraph.codegen.CodegenMain";

    /** The lowest Java feature release the generator and the compilation it prepares are supported on. */
    private static final int MINIMUM_LANGUAGE_VERSION = 21;

    /** The annotation processor that publishes the index of what javac accepted, named by failures. */
    private static final String PROCESSOR = "io.stategraph:stategraph-processor";

    /** The name of the request file every step of one compilation reads, inside that compilation's directory. */
    private static final String REQUEST = "request.json";

    /** The directory, under the build directory, that holds the manifests and inventories of a compilation. */
    private static final String WORK = "stategraph";

    /** The project this compilation belongs to; injected from {@code ${project}}. */
    private MavenProject project;

    /** The session this build runs in; injected from {@code ${session}} and read by the toolchain manager. */
    private MavenSession session;

    /** The artifacts of this plugin; injected from {@code ${plugin.artifacts}} and never the project's own. */
    private List<Artifact> pluginArtifacts;

    /** An explicit {@code java} executable for the generator; injected from {@code ${stategraph.javaExecutable}}. */
    private String javaExecutable;

    /** An explicit compilation identity; injected from {@code ${stategraph.compilationId}}. */
    private String compilationId;

    /**
     * The toolchains of the build, injected as a descriptor requirement: it both reports the {@code jdk}
     * toolchain the build selected and resolves the one a compiler execution requires for itself.
     */
    private ToolchainManager toolchainManager;

    /**
     * The project this goal runs for.
     *
     * @return the project being built
     */
    protected final MavenProject project() {
        return project;
    }

    /**
     * The request of the {@code main} compilation: the original source roots of the project, its compile
     * classpath without its own class output, and the directories this plugin and the compiler write into.
     *
     * @return the request the generator reads for the main source set
     * @throws MojoExecutionException if Maven has not resolved the compile classpath of the project
     */
    protected final Request mainRequest() throws MojoExecutionException {
        Path build = buildDirectory();
        Path sourcesOutput = build.resolve("generated-sources").resolve(WORK);
        Path processorSourcesOutput = build.resolve("generated-sources").resolve("annotations");
        Path classesOutput = absolute(project.getBuild().getOutputDirectory());
        return new Request(
            project.getBasedir().getAbsolutePath(),
            originalSources(project.getCompileSourceRoots(), sourcesOutput, processorSourcesOutput),
            applicationClasspath(compileClasspathElements(), classesOutput),
            sourcesOutput.toString(),
            processorSourcesOutput.toString(),
            classesOutput.toString(),
            build.resolve(WORK).resolve("main").toString(),
            compilationId("main"));
    }

    /**
     * The request of the {@code test} compilation. Its classpath keeps the main class output — the test
     * compilation resolves against it — and drops only the directory this compilation writes itself.
     *
     * @return the request the generator reads for the test source set
     * @throws MojoExecutionException if Maven has not resolved the test classpath of the project
     */
    protected final Request testRequest() throws MojoExecutionException {
        Path build = buildDirectory();
        Path sourcesOutput = build.resolve("generated-test-sources").resolve(WORK);
        Path processorSourcesOutput = build.resolve("generated-test-sources").resolve("test-annotations");
        Path classesOutput = absolute(project.getBuild().getTestOutputDirectory());
        return new Request(
            project.getBasedir().getAbsolutePath(),
            originalSources(project.getTestCompileSourceRoots(), sourcesOutput, processorSourcesOutput),
            applicationClasspath(testClasspathElements(), classesOutput),
            sourcesOutput.toString(),
            processorSourcesOutput.toString(),
            classesOutput.toString(),
            build.resolve(WORK).resolve("test").toString(),
            compilationId("test"));
    }

    /**
     * Refuses to generate for a compilation the compiler is not going to run the way this request describes:
     * under another JDK, under a release the generated vocabulary does not compile with, over another
     * annotation manifest, or writing the sources of the annotation processor somewhere this plugin does not
     * own. Nothing has been written when this fails.
     *
     * @param execution the compiler execution this generation belongs to
     * @param request the request the generator is about to run over
     * @throws MojoExecutionException naming both sides of the disagreement and the configuration that fixes it
     */
    protected final void verifyCompiler(CompilerExecution execution, Request request)
            throws MojoExecutionException {
        CompilerConfiguration compiler = CompilerConfiguration.of(project, session, execution);
        compiler.requireAgreementWith(javaExecutable(), toolchainManager, session);
        compiler.requireAnnotationProcessor(project);
        compiler.requireManifestArgument(request);
        compiler.requireProcessorSourcesOutput(request);
    }

    /**
     * Retires everything the previous cycle of this compilation owned and leaves the compiler no choice but to
     * run again.
     *
     * <p>The newest output of the previous compilation is read before the retirement, because the retirement is
     * what removes it; the carrier the generation of this cycle emitted is dated past it afterwards, so the
     * incremental compiler cannot mistake a compilation whose binaries have just been deleted for one that is
     * up to date. A build that switched this compilation off is refused before the first deletion.</p>
     *
     * @param execution the compiler execution this preparation belongs to
     * @param request the request the generation of this cycle wrote
     * @throws MojoExecutionException if the compilation is skipped, the generator failed, or the carrier of
     *     this generation cannot be found or dated
     */
    protected final void prepare(CompilerExecution execution, Request request) throws MojoExecutionException {
        CompilerConfiguration.of(project, session, execution).requireNotSkipped();
        Path sourcesOutput = Path.of(request.sourcesOutput());
        try {
            FileTime newest = CarrierTimestamp.newestOutput(Path.of(request.classesOutput()));
            run("prepare-compile", request);
            if (CarrierTimestamp.refresh(sourcesOutput, newest).isEmpty()) {
                throw new MojoExecutionException("the generation of " + request.compilationId()
                    + " left no compilation carrier under " + sourcesOutput + ", so the compiler could read"
                    + " the outputs this preparation retired as up to date; run the generator again");
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
     * failure, so a compilation javac rejected never reaches this step. A compilation javac never ran at all —
     * one whose sources the compiler found up to date — leaves no provisional index behind, and completing
     * without one would be completing a compilation that did not happen: the step says so and does nothing.</p>
     *
     * @param request the request the generation of this cycle wrote
     * @throws MojoExecutionException if the generator failed
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
                + " accepted compilation. Check that " + PROCESSOR
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
     * @throws MojoExecutionException if the request cannot be written or the generator left a nonzero status
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
     * {@code jdk} toolchain provides, else the JVM running Maven once it is known to be a JDK new enough to
     * host the generator.
     */
    private String javaExecutable() throws MojoExecutionException {
        if (javaExecutable != null && !javaExecutable.isBlank()) {
            return javaExecutable;
        }
        Toolchain toolchain = toolchainManager.getToolchainFromBuildContext("jdk", session);
        if (toolchain != null) {
            String tool = toolchain.findTool("java");
            if (tool == null) {
                throw new MojoExecutionException("the jdk toolchain selected for this build, " + toolchain
                    + ", provides no 'java' tool, so the StateGraph generator cannot be started."
                    + " Select a jdk toolchain that provides one, or configure <javaExecutable>.");
            }
            return tool;
        }
        return runningJavaExecutable();
    }

    /** The executable of the JVM running Maven, refused unless it is a JDK the generator can run on. */
    private static String runningJavaExecutable() throws MojoExecutionException {
        Path home = Path.of(System.getProperty("java.home"));
        int languageVersion = Runtime.version().feature();
        if (languageVersion < MINIMUM_LANGUAGE_VERSION) {
            throw new MojoExecutionException("the StateGraph generator needs Java " + MINIMUM_LANGUAGE_VERSION
                + " or later, but Maven itself runs on Java " + languageVersion + " at " + home
                + ". Select a jdk toolchain of Java " + MINIMUM_LANGUAGE_VERSION
                + " or later, or configure <javaExecutable>.");
        }
        Path compiler = home.resolve("bin").resolve(executableName("javac"));
        if (!Files.isRegularFile(compiler)) {
            throw new MojoExecutionException("the StateGraph generator needs the jdk.compiler module, but the"
                + " JVM running Maven, at " + home + ", carries no " + compiler.getFileName()
                + ", so it is a runtime, not a JDK. Select a jdk toolchain, or configure <javaExecutable>.");
        }
        return home.resolve("bin").resolve(executableName("java")).toString();
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
    private List<String> generatorClasspath() throws MojoExecutionException {
        List<String> entries = new ArrayList<>();
        for (Artifact artifact : pluginArtifacts) {
            File file = artifact.getFile();
            if (file != null) {
                entries.add(file.getAbsolutePath());
            }
        }
        if (entries.isEmpty()) {
            throw new MojoExecutionException("this plugin resolved no artifacts of its own, so the StateGraph"
                + " generator cannot be started; the " + MAIN_CLASS + " entry point lives in"
                + " io.stategraph:stategraph-codegen, a dependency of this plugin.");
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

    /**
     * The source roots the generator parses: everything the compilation declares except the two directories
     * this build generates into, which its own outputs must never overlap.
     */
    private List<String> originalSources(List<String> roots, Path sourcesOutput, Path processorSourcesOutput) {
        Set<String> sources = new LinkedHashSet<>();
        for (String root : roots) {
            Path source = absolute(root);
            if (!source.equals(sourcesOutput) && !source.equals(processorSourcesOutput)) {
                sources.add(source.toString());
            }
        }
        return List.copyOf(sources);
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
     * Encodes {@code request} as the flat JSON object documented by the generator's manifest codec: exactly the
     * eight members of the request, in the order the codec lists them.
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
        member(json, "compilationId", request.compilationId()).append('\n');
        json.append("}\n");
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
     * @param sources the original source roots, which never overlap an output directory
     * @param classpath the compile classpath the generator resolves headers and published indexes against
     * @param sourcesOutput where the generated vocabulary is written
     * @param processorSourcesOutput where the annotation processor writes its own sources
     * @param classesOutput where javac writes the classes of this compilation
     * @param workDirectory where the manifests and the inventories of this compilation live
     * @param compilationId the identity of the project and source set, as {@code group:artifact:sourceSet}
     */
    public record Request(String projectDirectory, List<String> sources, List<String> classpath,
            String sourcesOutput, String processorSourcesOutput, String classesOutput, String workDirectory,
            String compilationId) {

        /** The file name the generator writes the manifest of a compilation under, in its work directory. */
        private static final String MANIFEST = "annotation-manifest.json";

        /** The index the annotation processor publishes for a compilation javac has not yet accepted. */
        private static final String PENDING_INDEX = "META-INF/stategraph/pending-exports.json";

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
         * The manifest of this compilation, which the generator writes and the annotation processor reads.
         *
         * @return the absolute path of the annotation manifest
         */
        public Path manifestFile() {
            return Path.of(workDirectory).toAbsolutePath().normalize().resolve(MANIFEST);
        }

        /**
         * The provisional index of this compilation, which exists only between javac and completion.
         *
         * @return the absolute path of the provisional index
         */
        public Path pendingIndexFile() {
            return Path.of(classesOutput).toAbsolutePath().normalize().resolve(PENDING_INDEX);
        }

        /**
         * The inventory of what this cycle retired, which exists only between preparation and completion.
         *
         * @return the absolute path of the pending inventory
         */
        public Path pendingInventoryFile() {
            return Path.of(workDirectory).toAbsolutePath().normalize().resolve(PENDING_INVENTORY);
        }
    }
}
