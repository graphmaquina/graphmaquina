package io.stategraph.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * What {@code maven-compiler-plugin} is configured to do for one compilation, and everything a StateGraph goal
 * must agree with before it writes or retires anything.
 *
 * <p>The generator and javac are two processes that must read and write the same files under the same language
 * version. Nothing in Maven enforces that: the compiler is configured in the POM and the generator is started
 * by this plugin, so a project can quietly point one of them somewhere else. Rather than mutate the execution
 * plan Maven already built, every goal reads the effective configuration of the compiler execution it belongs
 * to and refuses to run when the two disagree, naming both sides and the configuration that would fix it.</p>
 *
 * <p>A setting is read the way Maven itself resolves a parameter: the configuration of the compiler execution
 * first, then the configuration the plugin declares for all of its executions, and then the user property the
 * parameter is bound to — from the command line, from the properties of the project and from the properties of
 * the JVM, in that order. Reading only the XML would miss a project that configures its compiler entirely
 * through {@code maven.compiler.*} properties, which is a common and fully supported way to write a POM.</p>
 */
final class CompilerConfiguration {

    /** The key the project model files {@code maven-compiler-plugin} under. */
    private static final String COMPILER = "org.apache.maven.plugins:maven-compiler-plugin";

    /** The processor option that tells the annotation processor which manifest describes this compilation. */
    private static final String MANIFEST_OPTION = "-Astategraph.annotationManifest=";

    /** The javac option that switches annotation processing off, which this plugin cannot work without. */
    private static final String NO_PROCESSING = "-proc:none";

    /** The coordinates of the annotation processor that publishes the index of an accepted compilation. */
    private static final String PROCESSOR_GROUP = "io.stategraph";
    private static final String PROCESSOR_ARTIFACT = "stategraph-processor";

    /** The lowest Java feature release the generated vocabulary compiles under. */
    private static final int MINIMUM_RELEASE = 21;

    /** The type of toolchain a compilation of Java sources selects. */
    private static final String JDK = "jdk";

    private final CompilerExecution execution;
    private final Xpp3Dom shared;
    private final Xpp3Dom own;
    private final Path basedir;
    private final List<Properties> properties;

    private CompilerConfiguration(CompilerExecution execution, Xpp3Dom shared, Xpp3Dom own, Path basedir,
            List<Properties> properties) {
        this.execution = execution;
        this.shared = shared;
        this.own = own;
        this.basedir = basedir;
        this.properties = properties;
    }

    /**
     * Reads the effective configuration of {@code execution} from {@code project} and {@code session}.
     *
     * @param project the project being built
     * @param session the session, which carries the properties the command line and the JVM set
     * @param execution the compiler execution the calling goal belongs to
     * @return the configuration, empty when the project declares no compiler plugin and no property of its own
     */
    static CompilerConfiguration of(MavenProject project, MavenSession session, CompilerExecution execution) {
        Plugin compiler = project.getPlugin(COMPILER);
        Xpp3Dom shared = null;
        Xpp3Dom own = null;
        if (compiler != null) {
            shared = configuration(compiler.getConfiguration());
            for (PluginExecution candidate : compiler.getExecutions()) {
                if (execution.id().equals(candidate.getId())) {
                    own = configuration(candidate.getConfiguration());
                }
            }
        }
        // The order is Maven's own precedence for a user property: the command line, then the project, then
        // the JVM the build runs in.
        List<Properties> properties = List.of(session.getUserProperties(), project.getProperties(),
            session.getSystemProperties());
        return new CompilerConfiguration(execution, shared, own, project.getBasedir().toPath(), properties);
    }

    /** The configuration of a plugin or of one of its executions, which the model carries as an XML tree. */
    private static Xpp3Dom configuration(Object configuration) {
        return configuration instanceof Xpp3Dom dom ? dom : null;
    }

    /**
     * Refuses a compilation that would run under a different JDK than the generator, or under a release the
     * generated vocabulary does not compile with.
     *
     * @param generatorJava the {@code java} executable this plugin starts the generator with
     * @param toolchains the component that resolves the {@code jdk} toolchains of this build
     * @param session the session the toolchains are resolved against
     * @throws MojoExecutionException naming both sides when the compiler and the generator disagree
     */
    void requireAgreementWith(String generatorJava, ToolchainManager toolchains, MavenSession session)
            throws MojoExecutionException {
        requireSupportedRelease();
        requireUnforkedCompiler(generatorJava);
        requireSameToolchain(generatorJava, toolchains, session);
    }

    /**
     * Refuses a compilation that would not run at Java 21 or later.
     *
     * <p>An absent {@code release} is refused as well: {@code source} and {@code target} of
     * {@code maven-compiler-plugin} default to {@code 1.8}, so a project that configures no release at all
     * compiles the generated vocabulary at a version it does not parse.</p>
     */
    private void requireSupportedRelease() throws MojoExecutionException {
        Optional<Setting> release = setting("release", "maven.compiler.release");
        if (release.isPresent()) {
            requireSupported(release.get());
            return;
        }
        Optional<Setting> source = setting("source", "maven.compiler.source");
        Optional<Setting> target = setting("target", "maven.compiler.target");
        if (source.isEmpty() && target.isEmpty()) {
            throw new MojoExecutionException("the " + execution.id() + " execution of maven-compiler-plugin"
                + " configures no release, so it compiles at the default source and target of that plugin,"
                + " which is below Java " + MINIMUM_RELEASE + "; the vocabulary the StateGraph generator emits"
                + " needs Java " + MINIMUM_RELEASE + " or later. Configure <release>" + MINIMUM_RELEASE
                + "</release> for that execution.");
        }
        if (source.isPresent()) {
            requireSupported(source.get());
        }
        if (target.isPresent()) {
            requireSupported(target.get());
        }
        if (source.isEmpty() || target.isEmpty()) {
            throw new MojoExecutionException("the " + execution.id() + " execution of maven-compiler-plugin"
                + " configures " + (source.isEmpty() ? target.get() : source.get()) + " and leaves the other"
                + " of source and target at the default of that plugin, which is below Java "
                + MINIMUM_RELEASE + ". Configure <release>" + MINIMUM_RELEASE + "</release> for that"
                + " execution instead.");
        }
    }

    /** Refuses one release-shaped setting below what the generated vocabulary compiles with. */
    private void requireSupported(Setting setting) throws MojoExecutionException {
        int feature = featureVersion(setting.value());
        if (feature > 0 && feature < MINIMUM_RELEASE) {
            throw new MojoExecutionException("the " + execution.id() + " execution of maven-compiler-plugin"
                + " compiles with " + setting + ", but the vocabulary the StateGraph generator emits needs"
                + " Java " + MINIMUM_RELEASE + " or later. Configure <release>" + MINIMUM_RELEASE
                + "</release> for that execution.");
        }
    }

    /** The feature release {@code value} names, {@code 0} when it is not a release this plugin understands. */
    private static int featureVersion(String value) {
        String feature = value.startsWith("1.") ? value.substring(2) : value;
        try {
            return Integer.parseInt(feature.strip());
        } catch (NumberFormatException notAVersion) {
            return 0;
        }
    }

    /** Refuses a compiler forked at an executable this plugin cannot know the language version of. */
    private void requireUnforkedCompiler(String generatorJava) throws MojoExecutionException {
        Optional<Setting> executable = setting("executable", "maven.compiler.executable");
        Optional<Setting> fork = setting("fork", "maven.compiler.fork");
        if (executable.isEmpty() || !Boolean.parseBoolean(fork.map(Setting::value).orElse("false"))) {
            return;
        }
        throw new MojoExecutionException("the " + execution.id() + " execution of maven-compiler-plugin forks"
            + " javac at " + executable.get().value() + ", while the StateGraph generator runs under "
            + generatorJava + ". The two must be the same JDK, or the vocabulary the generator emits is not"
            + " the vocabulary javac compiles. Remove the fork and select a <jdkToolchain> instead, or"
            + " configure <javaExecutable> of this plugin to the same JDK.");
    }

    /**
     * Refuses a {@code jdkToolchain} that selects no JDK, or one other than the generator's.
     *
     * <p>The toolchain is resolved the way {@code maven-compiler-plugin} resolves its own: the first of the
     * {@code jdk} toolchains of this build that matches the requirements is the one javac would run under.</p>
     */
    private void requireSameToolchain(String generatorJava, ToolchainManager toolchains, MavenSession session)
            throws MojoExecutionException {
        Map<String, String> requirements = jdkToolchain();
        if (requirements.isEmpty()) {
            return;
        }
        List<Toolchain> candidates = toolchains.getToolchains(session, JDK, requirements);
        if (candidates.isEmpty()) {
            throw disagreeingToolchain(requirements, generatorJava, "no jdk toolchain of this build");
        }
        Toolchain selected = candidates.get(0);
        String tool = selected.findTool("java");
        if (tool != null && sameFile(tool, generatorJava)) {
            return;
        }
        throw disagreeingToolchain(requirements, generatorJava,
            tool == null ? String.valueOf(selected) : tool);
    }

    private MojoExecutionException disagreeingToolchain(Map<String, String> requirements, String generatorJava,
            String selected) {
        return new MojoExecutionException("the " + execution.id() + " execution of maven-compiler-plugin"
            + " compiles with the jdk toolchain " + requirements + ", which selects " + selected
            + ", while the StateGraph generator runs under " + generatorJava
            + ". The two must be the same JDK. Configure <javaExecutable> of this plugin to the JDK that"
            + " toolchain selects, or select the toolchain of the generator for the compiler as well.");
    }

    /** Whether two executables are the same file, compared by path when one of them does not exist. */
    private static boolean sameFile(String one, String other) {
        Path first = Path.of(one);
        Path second = Path.of(other);
        try {
            return Files.isSameFile(first, second);
        } catch (IOException unreadable) {
            return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize());
        }
    }

    /**
     * Refuses a compilation javac would run without the StateGraph annotation processor.
     *
     * <p>The processor is the only thing that publishes the index of what javac accepted. A compilation that
     * runs without it compiles the generated vocabulary, writes the carrier of the compilation into the class
     * output and publishes nothing — and since the preparation has already retired the index of the previous
     * cycle, the build would package a jar carrying the carrier and no index at all.</p>
     *
     * @param project the project being built, whose resolved artifacts carry the processor when the compiler
     *     declares no processor path of its own
     * @throws MojoExecutionException naming what switched the processor off or where it is missing
     */
    void requireAnnotationProcessor(MavenProject project) throws MojoExecutionException {
        Optional<Setting> proc = setting("proc", "maven.compiler.proc");
        if (proc.isPresent() && "none".equalsIgnoreCase(proc.get().value())) {
            throw noProcessing(proc.get().toString());
        }
        if (compilerArguments().contains(NO_PROCESSING)) {
            throw noProcessing("the compiler argument " + NO_PROCESSING);
        }
        Optional<Xpp3Dom> paths = element("annotationProcessorPaths");
        if (paths.isPresent()) {
            for (Xpp3Dom path : paths.get().getChildren()) {
                if (PROCESSOR_GROUP.equals(childValue(path, "groupId"))
                        && PROCESSOR_ARTIFACT.equals(childValue(path, "artifactId"))) {
                    return;
                }
            }
            throw missingProcessor("the <annotationProcessorPaths> of the " + execution.id()
                + " execution of maven-compiler-plugin");
        }
        for (Artifact artifact : project.getArtifacts()) {
            if (PROCESSOR_GROUP.equals(artifact.getGroupId())
                    && PROCESSOR_ARTIFACT.equals(artifact.getArtifactId())) {
                return;
            }
        }
        throw missingProcessor("the dependencies of this project, and the " + execution.id()
            + " execution of maven-compiler-plugin declares no <annotationProcessorPaths>");
    }

    private MojoExecutionException noProcessing(String setting) {
        return new MojoExecutionException(setting + " switches annotation processing off for the "
            + execution.id() + " execution of maven-compiler-plugin, which StateGraph does not support: "
            + PROCESSOR_GROUP + ':' + PROCESSOR_ARTIFACT + " is what publishes the index of what javac"
            + " accepted, so this build would retire the index of the previous compilation and publish"
            + " nothing in its place. Run with annotation processing on.");
    }

    private MojoExecutionException missingProcessor(String where) {
        return new MojoExecutionException(PROCESSOR_GROUP + ':' + PROCESSOR_ARTIFACT + " is not on " + where
            + ", so javac would compile the generated vocabulary and publish no index of what it accepted,"
            + " while this build has already retired the index of the previous compilation. Declare it in"
            + " <annotationProcessorPaths> of that execution, after Lombok when the project uses Lombok.");
    }

    /**
     * Refuses a compilation whose compiler arguments do not name the very manifest this request writes, which
     * is what makes the annotation processor read this generation and not a previous one.
     *
     * @param request the request the calling goal generates for
     * @throws MojoExecutionException printing the argument the execution must declare
     */
    void requireManifestArgument(StateGraphMojo.Request request) throws MojoExecutionException {
        Path expected = request.manifestFile();
        String option = MANIFEST_OPTION + expected;
        Set<String> declared = new LinkedHashSet<>();
        for (String argument : compilerArguments()) {
            if (argument.startsWith(MANIFEST_OPTION)) {
                declared.add(argument.substring(MANIFEST_OPTION.length()));
            }
        }
        if (declared.isEmpty()) {
            throw new MojoExecutionException("the " + execution.id() + " execution of maven-compiler-plugin"
                + " declares no annotation manifest, so javac would compile without the vocabulary the"
                + " StateGraph generator wrote. Add this compiler argument to that execution: " + option);
        }
        for (String value : declared) {
            if (!absolute(value).equals(expected)) {
                throw new MojoExecutionException("the " + execution.id() + " execution of"
                    + " maven-compiler-plugin reads the annotation manifest " + value
                    + ", while the StateGraph generator writes " + expected
                    + ". Declare exactly this compiler argument for that execution: " + option);
            }
        }
    }

    /**
     * Refuses a generated-sources directory other than the one the request retires from, since the sources the
     * annotation processor writes are owned outputs of this compilation. The two javac executions name that
     * directory with two different parameters, and each is read with its own.
     *
     * @param request the request the calling goal generates for
     * @throws MojoExecutionException naming both directories
     */
    void requireProcessorSourcesOutput(StateGraphMojo.Request request) throws MojoExecutionException {
        String parameter = execution.generatedSourcesParameter();
        Optional<String> configured = value(parameter);
        if (configured.isEmpty()) {
            return;
        }
        Path expected = Path.of(request.processorSourcesOutput()).toAbsolutePath().normalize();
        if (!absolute(configured.get()).equals(expected)) {
            throw new MojoExecutionException("the " + execution.id() + " execution of maven-compiler-plugin"
                + " writes the sources of the annotation processor to " + configured.get()
                + ", while StateGraph owns and retires " + expected + ". Configure <" + parameter + '>'
                + expected + "</" + parameter + "> for that execution, or leave it unset.");
        }
    }

    /**
     * Refuses a build that switched this compilation off, before anything of the previous cycle is retired:
     * retiring the outputs of a compilation javac is not going to run would leave the project published with
     * nothing at all.
     *
     * @throws MojoExecutionException naming the setting that switched the compilation off
     */
    void requireNotSkipped() throws MojoExecutionException {
        Optional<Setting> skip = setting(execution.skipParameter(), execution.skipProperty());
        if (skip.isPresent() && Boolean.parseBoolean(skip.get().value())) {
            throw new MojoExecutionException(skip.get() + " switches the " + execution.id()
                + " compilation off, which StateGraph does not support: this build would retire everything"
                + " the previous compilation published and compile nothing to replace it. Run without it, or"
                + " run without the StateGraph plugin.");
        }
    }

    /**
     * The value of one compiler parameter, and where the build set it: the configuration of this execution,
     * then the shared configuration, then the property the parameter is bound to.
     */
    private Optional<Setting> setting(String parameter, String property) {
        Optional<String> configured = value(parameter);
        if (configured.isPresent()) {
            return Optional.of(new Setting('<' + parameter + '>' + configured.get() + "</" + parameter + '>',
                configured.get()));
        }
        for (Properties candidates : properties) {
            String value = candidates.getProperty(property);
            if (value != null && !value.isBlank()) {
                return Optional.of(new Setting(property + '=' + value.strip(), value.strip()));
            }
        }
        return Optional.empty();
    }

    /** The value of {@code name}, from this execution first and from the shared configuration after it. */
    private Optional<String> value(String name) {
        return element(name).map(Xpp3Dom::getValue).filter(value -> !value.isBlank()).map(String::strip);
    }

    /** The element {@code name} of this execution's configuration, else of the shared configuration. */
    private Optional<Xpp3Dom> element(String name) {
        for (Xpp3Dom scope : scopes()) {
            Xpp3Dom child = scope.getChild(name);
            if (child != null) {
                return Optional.of(child);
            }
        }
        return Optional.empty();
    }

    /** The text of one child element, or {@code null} when the element or its text is absent. */
    private static String childValue(Xpp3Dom element, String name) {
        Xpp3Dom child = element.getChild(name);
        return child == null || child.getValue() == null ? null : child.getValue().strip();
    }

    /** Every compiler argument the execution and the shared configuration declare, in that order. */
    private List<String> compilerArguments() {
        List<String> arguments = new ArrayList<>();
        for (Xpp3Dom scope : scopes()) {
            Xpp3Dom declared = scope.getChild("compilerArgs");
            if (declared != null) {
                for (Xpp3Dom argument : declared.getChildren()) {
                    if (argument.getValue() != null) {
                        arguments.add(argument.getValue().strip());
                    }
                }
            }
            Xpp3Dom single = scope.getChild("compilerArgument");
            if (single != null && single.getValue() != null) {
                arguments.add(single.getValue().strip());
            }
        }
        return List.copyOf(arguments);
    }

    /** The requirements the compiler selects its own JDK with, empty when it selects none. */
    private Map<String, String> jdkToolchain() {
        Optional<Xpp3Dom> toolchain = element("jdkToolchain");
        if (toolchain.isEmpty()) {
            return Map.of();
        }
        Map<String, String> requirements = new LinkedHashMap<>();
        for (Xpp3Dom requirement : toolchain.get().getChildren()) {
            requirements.put(requirement.getName(),
                requirement.getValue() == null ? "" : requirement.getValue().strip());
        }
        return Map.copyOf(requirements);
    }

    /** The configuration scopes to read, most specific first. */
    private List<Xpp3Dom> scopes() {
        List<Xpp3Dom> scopes = new ArrayList<>(2);
        if (own != null) {
            scopes.add(own);
        }
        if (shared != null) {
            scopes.add(shared);
        }
        return scopes;
    }

    /** {@code path} against the project directory when the configuration left it relative. */
    private Path absolute(String path) {
        Path candidate = Path.of(path);
        return (candidate.isAbsolute() ? candidate : basedir.resolve(candidate)).normalize();
    }

    /**
     * One resolved compiler setting: the value the compiler will use, and how the build spells it, so a failure
     * can quote the very XML element or the very property a developer has to change.
     *
     * @param description how the build spells this setting, as {@code <name>value</name>} or {@code name=value}
     * @param value the value itself
     */
    private record Setting(String description, String value) {

        @Override
        public String toString() {
            return description;
        }
    }
}
