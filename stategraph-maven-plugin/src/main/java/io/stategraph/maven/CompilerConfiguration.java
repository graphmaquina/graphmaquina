package io.stategraph.maven;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.MisconfiguredToolchainException;
import org.apache.maven.toolchain.ToolchainManagerPrivate;
import org.apache.maven.toolchain.ToolchainPrivate;
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
 * <p>The configuration is read from the project model: the configuration of the compiler execution itself,
 * overlaid on the configuration the plugin declares for all of its executions. That is the same precedence
 * Maven applies when it injects a parameter, so what this class reads is what javac will be given.</p>
 */
final class CompilerConfiguration {

    /** The key the project model files {@code maven-compiler-plugin} under. */
    private static final String COMPILER = "org.apache.maven.plugins:maven-compiler-plugin";

    /** The processor option that tells the annotation processor which manifest describes this compilation. */
    private static final String MANIFEST_OPTION = "-Astategraph.annotationManifest=";

    /** The lowest Java feature release the generated vocabulary compiles under. */
    private static final int MINIMUM_RELEASE = 21;

    /** The type of toolchain a compilation of Java sources selects. */
    private static final String JDK = "jdk";

    private final CompilerExecution execution;
    private final Xpp3Dom shared;
    private final Xpp3Dom own;
    private final Path basedir;

    private CompilerConfiguration(CompilerExecution execution, Xpp3Dom shared, Xpp3Dom own, Path basedir) {
        this.execution = execution;
        this.shared = shared;
        this.own = own;
        this.basedir = basedir;
    }

    /**
     * Reads the effective configuration of {@code execution} from {@code project}.
     *
     * @param project the project being built
     * @param execution the compiler execution the calling goal belongs to
     * @return the configuration, empty when the project declares no compiler plugin of its own
     */
    static CompilerConfiguration of(MavenProject project, CompilerExecution execution) {
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
        return new CompilerConfiguration(execution, shared, own, project.getBasedir().toPath());
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
    void requireAgreementWith(String generatorJava, ToolchainManagerPrivate toolchains, MavenSession session)
            throws MojoExecutionException {
        requireSupportedRelease();
        requireUnforkedCompiler(generatorJava);
        requireSameToolchain(generatorJava, toolchains, session);
    }

    /** Refuses a {@code release}, {@code source} or {@code target} below what the generated sources need. */
    private void requireSupportedRelease() throws MojoExecutionException {
        for (String name : List.of("release", "source", "target")) {
            Optional<String> configured = value(name);
            if (configured.isEmpty()) {
                continue;
            }
            int feature = featureVersion(configured.get());
            if (feature > 0 && feature < MINIMUM_RELEASE) {
                throw new MojoExecutionException("the " + execution.id() + " execution of"
                    + " maven-compiler-plugin compiles with <" + name + '>' + configured.get() + "</" + name
                    + ">, but the vocabulary the StateGraph generator emits needs Java " + MINIMUM_RELEASE
                    + " or later. Configure <release>" + MINIMUM_RELEASE + "</release> for that execution.");
            }
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
        Optional<String> executable = value("executable");
        if (executable.isEmpty() || !Boolean.parseBoolean(value("fork").orElse("false"))) {
            return;
        }
        throw new MojoExecutionException("the " + execution.id() + " execution of maven-compiler-plugin forks"
            + " javac at " + executable.get() + ", while the StateGraph generator runs under " + generatorJava
            + ". The two must be the same JDK, or the vocabulary the generator emits is not the vocabulary"
            + " javac compiles. Remove <fork> and <executable> and select a <jdkToolchain> instead, or"
            + " configure <javaExecutable> of this plugin to the same JDK.");
    }

    /** Refuses a {@code jdkToolchain} that selects no JDK, or one other than the generator's. */
    private void requireSameToolchain(String generatorJava, ToolchainManagerPrivate toolchains,
            MavenSession session) throws MojoExecutionException {
        Map<String, String> requirements = jdkToolchain();
        if (requirements.isEmpty()) {
            return;
        }
        ToolchainPrivate[] candidates;
        try {
            candidates = toolchains.getToolchainsForType(JDK, session);
        } catch (MisconfiguredToolchainException failure) {
            throw new MojoExecutionException("the jdk toolchains of this build cannot be read, so the"
                + " <jdkToolchain>" + requirements + "</jdkToolchain> of the " + execution.id()
                + " execution of maven-compiler-plugin cannot be compared with the JDK the StateGraph"
                + " generator runs under, " + generatorJava, failure);
        }
        for (ToolchainPrivate candidate : candidates) {
            if (candidate.matchesRequirements(requirements)) {
                String tool = candidate.findTool("java");
                if (tool != null && sameFile(tool, generatorJava)) {
                    return;
                }
                throw disagreeingToolchain(requirements, generatorJava, String.valueOf(tool));
            }
        }
        throw disagreeingToolchain(requirements, generatorJava, "no jdk toolchain of this build");
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
     * Refuses a {@code generatedSourcesDirectory} other than the one the request retires from, since the
     * sources the annotation processor writes are owned outputs of this compilation.
     *
     * @param request the request the calling goal generates for
     * @throws MojoExecutionException naming both directories
     */
    void requireProcessorSourcesOutput(StateGraphMojo.Request request) throws MojoExecutionException {
        Optional<String> configured = value("generatedSourcesDirectory");
        if (configured.isEmpty()) {
            return;
        }
        Path expected = Path.of(request.processorSourcesOutput()).toAbsolutePath().normalize();
        if (!absolute(configured.get()).equals(expected)) {
            throw new MojoExecutionException("the " + execution.id() + " execution of maven-compiler-plugin"
                + " writes the sources of the annotation processor to " + configured.get()
                + ", while StateGraph owns and retires " + expected
                + ". Configure <generatedSourcesDirectory>" + expected + "</generatedSourcesDirectory> for"
                + " that execution, or leave it unset.");
        }
    }

    /**
     * Refuses a build that switched this compilation off, before anything of the previous cycle is retired:
     * retiring the outputs of a compilation javac is not going to run would leave the project published with
     * nothing at all.
     *
     * @param project the project being built
     * @param session the session, which carries the properties the command line set
     * @throws MojoExecutionException naming the setting that switched the compilation off
     */
    void requireNotSkipped(MavenProject project, MavenSession session) throws MojoExecutionException {
        if (Boolean.parseBoolean(value(execution.skipParameter()).orElse("false"))) {
            throw skipped('<' + execution.skipParameter() + '>' + " of the " + execution.id()
                + " execution of maven-compiler-plugin");
        }
        for (Properties properties : List.of(session.getUserProperties(), project.getProperties(),
                session.getSystemProperties())) {
            if (Boolean.parseBoolean(properties.getProperty(execution.skipProperty(), "false"))) {
                throw skipped(execution.skipProperty());
            }
        }
    }

    private MojoExecutionException skipped(String setting) {
        return new MojoExecutionException(setting + " switches the " + execution.id() + " compilation off,"
            + " which StateGraph does not support: this build would retire everything the previous"
            + " compilation published and compile nothing to replace it. Run without it, or run without the"
            + " StateGraph plugin.");
    }

    /** The value of {@code name}, from this execution first and from the shared configuration after it. */
    private Optional<String> value(String name) {
        for (Xpp3Dom scope : scopes()) {
            Xpp3Dom child = scope.getChild(name);
            if (child != null && child.getValue() != null && !child.getValue().isBlank()) {
                return Optional.of(child.getValue().strip());
            }
        }
        return Optional.empty();
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
        for (Xpp3Dom scope : scopes()) {
            Xpp3Dom toolchain = scope.getChild("jdkToolchain");
            if (toolchain != null) {
                Map<String, String> requirements = new LinkedHashMap<>();
                for (Xpp3Dom requirement : toolchain.getChildren()) {
                    requirements.put(requirement.getName(),
                        requirement.getValue() == null ? "" : requirement.getValue().strip());
                }
                return Map.copyOf(requirements);
            }
        }
        return Map.of();
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
}
