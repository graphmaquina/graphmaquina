package io.github.graphmaquina.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
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
 * <p>A setting is read the way Maven itself resolves a parameter: the effective configuration of the compiler
 * execution first — the one Maven merged the configuration the plugin declares for all of its executions into,
 * where what the execution declares itself, a list included, wins over the plugin's — and then the
 * user property the parameter is bound to, from the command line, from the properties of the JVM and from the
 * properties of the project, in that order. Reading only the XML would miss a project that configures its
 * compiler entirely through {@code maven.compiler.*} properties, which is a common and fully supported way to
 * write a POM.</p>
 */
final class CompilerConfiguration {

    /** The key the project model files {@code maven-compiler-plugin} under. */
    private static final String COMPILER = "org.apache.maven.plugins:maven-compiler-plugin";

    /** The processor option that tells the annotation processor which manifest describes this compilation. */
    private static final String MANIFEST_OPTION = "-Agraphmaquina.annotationManifest=";

    /** The javac option that switches annotation processing off, which this plugin cannot work without. */
    private static final String NO_PROCESSING = "-proc:none";

    /** The javac option that runs the annotation processors and compiles nothing. */
    private static final String ONLY_PROCESSING = "-proc:only";

    /** The coordinates of the annotation processor that publishes the index of an accepted compilation. */
    private static final String PROCESSOR_GROUP = "io.github.graphmaquina";
    private static final String PROCESSOR_ARTIFACT = "graphmaquina-processor";

    /** The lowest Java feature release the generated vocabulary compiles under. */
    private static final int MINIMUM_RELEASE = 21;

    /** The type of toolchain a compilation of Java sources selects. */
    private static final String JDK = "jdk";

    /** The package the generator emits the carrier of every compilation into, as a path the compiler matches. */
    private static final String CARRIER_PACKAGE = "io/github/graphmaquina/generated/bootstrap";

    /** The include that selects everything the generator emits outside the packages of a project. */
    private static final String GENERATED_INCLUDE = "io/github/graphmaquina/generated/**";

    private final CompilerExecution execution;
    private final Xpp3Dom configuration;
    private final Path basedir;
    private final List<Properties> properties;

    private CompilerConfiguration(CompilerExecution execution, Xpp3Dom configuration, Path basedir,
            List<Properties> properties) {
        this.execution = execution;
        this.configuration = configuration;
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
        Xpp3Dom configuration = null;
        if (compiler != null) {
            // Maven has already merged the configuration the plugin declares for all of its executions into the
            // configuration of each execution, and an element the execution declares itself — a whole list
            // included — wins over the plugin's. Reading the plugin's again would read arguments javac never
            // receives, so it is read on its own only when the model carries no such execution at all.
            configuration = configuration(compiler.getConfiguration());
            for (PluginExecution candidate : compiler.getExecutions()) {
                if (execution.id().equals(candidate.getId())) {
                    configuration = configuration(candidate.getConfiguration());
                }
            }
        }
        // The order is the one Maven evaluates the expression of a compiler parameter in: the properties of the
        // session — the command line, then the JVM the build runs in — before the properties of the project.
        List<Properties> properties = List.of(session.getUserProperties(), session.getSystemProperties(),
            project.getProperties());
        return new CompilerConfiguration(execution, configuration, project.getBasedir().toPath(), properties);
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
        requireSameJdk(generatorJava, toolchains, session);
    }

    /**
     * Refuses a compilation that would not run at Java 21 or later.
     *
     * <p>The settings are the ones this compilation reads, as maven-compiler-plugin 3.15.0 reads them: the test
     * compilation takes {@code <testRelease>}, {@code <testSource>} and {@code <testTarget>}, or their
     * {@code maven.compiler.test*} properties, over {@code <release>}, {@code <source>} and {@code <target>} when
     * they are set. A release wins over a source and a target, so they are checked only when no release is set.
     * Advice to configure {@code <release>} is given only where it fixes the compilation: a test release that is
     * set wins over it, so the refusal of one asks for {@code <testRelease>} instead.</p>
     *
     * <p>An absent release is refused as well: {@code source} and {@code target} of
     * {@code maven-compiler-plugin} default to {@code 1.8}, so a project that configures no release at all
     * compiles the generated vocabulary at a version it does not parse.</p>
     */
    private void requireSupportedRelease() throws MojoExecutionException {
        Optional<Setting> testRelease = testSetting("testRelease", "maven.compiler.testRelease");
        if (testRelease.isPresent()) {
            requireSupported(testRelease.get(), "testRelease");
            return;
        }
        Optional<Setting> release = setting("release", "maven.compiler.release");
        if (release.isPresent()) {
            requireSupported(release.get(), "release");
            return;
        }
        Optional<Setting> source = testSetting("testSource", "maven.compiler.testSource")
            .or(() -> setting("source", "maven.compiler.source"));
        Optional<Setting> target = testSetting("testTarget", "maven.compiler.testTarget")
            .or(() -> setting("target", "maven.compiler.target"));
        if (source.isEmpty() && target.isEmpty()) {
            throw new MojoExecutionException("the " + execution.id() + " execution of maven-compiler-plugin"
                + " configures no release, so it compiles at the default source and target of that plugin,"
                + " which is below Java " + MINIMUM_RELEASE + "; the vocabulary the StateGraph generator emits"
                + " needs Java " + MINIMUM_RELEASE + " or later. Configure <release>" + MINIMUM_RELEASE
                + "</release> for that execution.");
        }
        if (source.isPresent()) {
            requireSupported(source.get(), "release");
        }
        if (target.isPresent()) {
            requireSupported(target.get(), "release");
        }
        if (source.isEmpty() || target.isEmpty()) {
            throw new MojoExecutionException("the " + execution.id() + " execution of maven-compiler-plugin"
                + " configures " + (source.isEmpty() ? target.get() : source.get()) + " and leaves the other"
                + " of source and target at the default of that plugin, which is below Java "
                + MINIMUM_RELEASE + ". Configure <release>" + MINIMUM_RELEASE + "</release> for that"
                + " execution instead.");
        }
    }

    /**
     * Refuses one release-shaped setting below what the generated vocabulary compiles with.
     *
     * @param setting the setting this compilation compiles with
     * @param fix the parameter whose value of Java 21 makes this compilation compile at Java 21
     */
    private void requireSupported(Setting setting, String fix) throws MojoExecutionException {
        int feature = featureVersion(setting.value());
        if (feature > 0 && feature < MINIMUM_RELEASE) {
            throw new MojoExecutionException("the " + execution.id() + " execution of maven-compiler-plugin"
                + " compiles with " + setting + ", but the vocabulary the StateGraph generator emits needs"
                + " Java " + MINIMUM_RELEASE + " or later. Configure <" + fix + '>' + MINIMUM_RELEASE
                + "</" + fix + "> for that execution.");
        }
    }

    /**
     * The release javac compiles this compilation at, which the generator parses the original sources at and
     * resolves the JDK types they name against, read as maven-compiler-plugin 3.15.0 reads it: {@code <release>}
     * or {@code maven.compiler.release}, else {@code <target>} or {@code maven.compiler.target}, as a feature
     * release ({@code 1.8} is 8). The test compilation takes {@code <testRelease>} or
     * {@code maven.compiler.testRelease} over the release, and {@code <testTarget>} or
     * {@code maven.compiler.testTarget} over the target, when they are set.
     *
     * @return the release, empty when neither names one this plugin understands
     */
    Optional<Integer> release() {
        Optional<Setting> release = testSetting("testRelease", "maven.compiler.testRelease")
            .or(() -> setting("release", "maven.compiler.release"));
        if (release.isEmpty()) {
            release = testSetting("testTarget", "maven.compiler.testTarget")
                .or(() -> setting("target", "maven.compiler.target"));
        }
        return release.map((Setting declared) -> featureVersion(declared.value()))
            .filter((Integer feature) -> feature > 0);
    }

    /** One setting only the test compilation reads, as {@link #setting} reads it; empty for the main one. */
    private Optional<Setting> testSetting(String parameter, String property) {
        return execution == CompilerExecution.TEST ? setting(parameter, property) : Optional.empty();
    }

    /**
     * The encoding javac reads the sources of this compilation in, which the generator reads the original sources
     * in and writes the generated ones in: {@code <encoding>} or the {@code encoding} property, else
     * {@code project.build.sourceEncoding}, the default maven-compiler-plugin gives that parameter.
     *
     * @return the name of the encoding, empty when the build names none. javac then reads the default charset of
     *     the JVM it runs in, which is UTF-8 from Java 18 on unless {@code file.encoding} names another, while the
     *     generator reads UTF-8.
     */
    Optional<String> encoding() {
        return setting("encoding", "encoding").or(() -> property("project.build.sourceEncoding"))
            .map(Setting::value);
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

    /**
     * Refuses a compilation whose javac runs on another JDK than the generator.
     *
     * <p>The JDK of javac is resolved the way maven-compiler-plugin resolves it: the {@code <executable>} it forks
     * when it forks one; else, unless an {@code <executable>} is configured without a fork — which makes the
     * compiler ignore every toolchain — the first jdk toolchain that matches its {@code <jdkToolchain>}, or the
     * toolchain selected for the build when none matches or none is required, whose javac it forks; else the
     * JDK running Maven. A {@code <jdkToolchain>} that matches nothing is therefore no failure of its own: the
     * compiler falls back, and so does this check. Two executables are on the same JDK when their homes are the
     * same directory, whatever path names them.</p>
     *
     * <p>The last case is exact when javac compiles inside Maven. A {@code <fork>} with neither an
     * {@code <executable>} nor a toolchain makes plexus-compiler-javac look javac up itself: at
     * {@code java.home/bin/javac} on macOS, at {@code java.home/../sh/javac} on AIX, and elsewhere at
     * {@code java.home/../bin/javac}, or at {@code $JAVA_HOME/bin/javac} when that is no file. This check still
     * compares the JDK running Maven. That is the JDK of the forked javac on macOS. Elsewhere it is so only when
     * no javac lies beside {@code java.home} in that way and {@code JAVA_HOME} names the JDK running Maven, as it
     * does when {@code mvn} starts Maven through it.</p>
     */
    private void requireSameJdk(String generatorJava, ToolchainManager toolchains, MavenSession session)
            throws MojoExecutionException {
        Optional<Setting> executable = setting("executable", "maven.compiler.executable");
        boolean fork = Boolean.parseBoolean(setting("fork", "maven.compiler.fork").map(Setting::value)
            .orElse("false"));
        if (executable.isPresent() && fork) {
            requireSameJdk(Jdk.home(executable.get().value(), basedir), "forks javac at "
                + executable.get().value(), generatorJava);
            return;
        }
        Toolchain selected = null;
        String selection = "";
        String fallback = "";
        if (executable.isEmpty()) {
            Map<String, String> requirements = jdkToolchain();
            if (!requirements.isEmpty()) {
                List<Toolchain> candidates = toolchains.getToolchains(session, JDK, requirements);
                if (candidates.isEmpty()) {
                    fallback = ", since its jdk toolchain " + requirements + " matches no toolchain of this build";
                } else {
                    selected = candidates.get(0);
                    selection = "the jdk toolchain " + requirements + ", which selects ";
                }
            }
            if (selected == null) {
                selected = toolchains.getToolchainFromBuildContext(JDK, session);
                selection = "the jdk toolchain selected for this build, ";
            }
        }
        String javac = selected == null ? null : selected.findTool("javac");
        if (javac != null) {
            requireSameJdk(Jdk.home(javac, basedir), "compiles with " + selection + javac + fallback,
                generatorJava);
            return;
        }
        Path running = Path.of(System.getProperty("java.home"));
        requireSameJdk(running, "compiles with the JDK running Maven, at " + running + fallback, generatorJava);
    }

    /** Refuses a javac whose JDK lies at another home than the JDK of the generator. */
    private void requireSameJdk(Path javacHome, String compiles, String generatorJava)
            throws MojoExecutionException {
        if (!Jdk.same(javacHome, Jdk.home(generatorJava, basedir))) {
            throw new MojoExecutionException("the " + execution.id() + " execution of maven-compiler-plugin "
                + compiles + ", while the StateGraph generator runs under " + generatorJava + ". The two must be"
                + " the same JDK, or the vocabulary the generator emits is not the vocabulary javac compiles."
                + " Configure <javaExecutable> of this plugin to the java of the JDK javac compiles with, or have"
                + " javac compile with the JDK of the generator.");
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

    /**
     * Refuses a compilation that can end without the classes the completion publishes while the build goes on:
     * {@code proc=only} runs the annotation processors and compiles nothing, and {@code failOnError=false} lets
     * a compilation javac rejected reach the completion. Either way the preparation has retired the outputs of
     * the previous cycle by then, and the completion would find no class of this compilation to publish.
     *
     * @throws MojoExecutionException naming the setting that lets the compilation end without its classes
     */
    void requireCompiledClasses() throws MojoExecutionException {
        Optional<Setting> proc = setting("proc", "maven.compiler.proc");
        if (proc.isPresent() && "only".equalsIgnoreCase(proc.get().value())) {
            throw compilesNothing(proc.get().toString(), "Leave proc unset, or set it to full.");
        }
        if (compilerArguments().contains(ONLY_PROCESSING)) {
            throw compilesNothing("the compiler argument " + ONLY_PROCESSING,
                "Remove that argument from the configuration of that execution.");
        }
        Optional<Setting> failOnError = setting("failOnError", "maven.compiler.failOnError");
        if (failOnError.isPresent() && !Boolean.parseBoolean(failOnError.get().value())) {
            throw new MojoExecutionException(failOnError.get() + " lets the build continue past a compilation the "
                + execution.id() + " execution of maven-compiler-plugin rejects, which StateGraph does not"
                + " support: the preparation would retire the outputs of the previous compilation, and the"
                + " completion would find no classes to publish in their place. Leave failOnError at its default,"
                + " true.");
        }
    }

    private MojoExecutionException compilesNothing(String setting, String remedy) {
        return new MojoExecutionException(setting + " makes the " + execution.id() + " execution of"
            + " maven-compiler-plugin run the annotation processors and compile nothing, which StateGraph does"
            + " not support: the preparation would retire the outputs of the previous compilation, and javac"
            + " would write no classes in their place. " + remedy);
    }

    private MojoExecutionException missingProcessor(String where) {
        return new MojoExecutionException(PROCESSOR_GROUP + ':' + PROCESSOR_ARTIFACT + " is not on " + where
            + ", so javac would compile the generated vocabulary and publish no index of what it accepted,"
            + " and the preparation that follows would retire the index of the previous compilation. Declare it"
            + " in <annotationProcessorPaths> of that execution, after Lombok when the project uses Lombok.");
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
     * The patterns javac selects the sources of every root of this compilation with: {@code <includes>} and
     * {@code <excludes>} for the main compilation, {@code <testIncludes>} and {@code <testExcludes>} for the test
     * one. The two lists have no user property, so only the configuration declares them.
     *
     * @return the patterns, which narrow nothing when the execution declares neither list
     */
    SourcePatterns sourcePatterns() {
        return new SourcePatterns(patterns(execution.includesParameter()), patterns(execution.excludesParameter()));
    }

    /**
     * Refuses includes or excludes that leave out the carrier of this compilation. maven-compiler-plugin selects
     * the sources of every root with them, the root this plugin generates into included, so patterns that miss
     * the carrier would have javac compile the generated vocabulary without it, and the completion would find
     * nothing of this compilation to publish. The carrier is matched under the very name the generator gives it:
     * {@code GraphCompilation} and the first sixteen hexadecimal digits of the SHA-256 of the identity of the
     * compilation, in {@code io/github/graphmaquina/generated/bootstrap}.
     *
     * @param request the request the calling goal generates for
     * @throws MojoExecutionException naming the pattern that leaves the carrier out and the configuration that
     *     lets it in
     */
    void requireCarrierSelected(StateGraphMojo.Request request) throws MojoExecutionException {
        String carrier = CARRIER_PACKAGE + "/GraphCompilation" + carrierDigest(request.compilationId()) + ".java";
        String compiler = " of the " + execution.id() + " execution of maven-compiler-plugin";
        String consequence = ", the carrier the StateGraph generator emits for this compilation under "
            + request.sourcesOutput() + ": the compiler applies its patterns to that directory too, so javac would"
            + " compile the generated vocabulary without its carrier and the completion would find nothing to"
            + " publish.";
        for (String exclude : patterns(execution.excludesParameter())) {
            if (SourcePatterns.matches(exclude, carrier)) {
                throw new MojoExecutionException("the <" + execution.excludesParameter() + "> pattern " + exclude
                    + compiler + " leaves out " + carrier + consequence + " Change that pattern so it matches"
                    + " nothing under io/github/graphmaquina/generated.");
            }
        }
        List<String> includes = patterns(execution.includesParameter());
        if (includes.isEmpty()) {
            return;
        }
        for (String include : includes) {
            if (SourcePatterns.matches(include, carrier)) {
                return;
            }
        }
        throw new MojoExecutionException("the <" + execution.includesParameter() + ">" + compiler + ", " + includes
            + ", select no " + carrier + consequence + " Add <include>" + GENERATED_INCLUDE + "</include> to the <"
            + execution.includesParameter() + "> of that execution.");
    }

    /**
     * The digest that names the carrier of {@code compilationId}: the first sixteen hexadecimal digits of the
     * SHA-256 of its UTF-8 bytes. It copies the rule of the generator's
     * {@code io.github.graphmaquina.codegen.emit.CompilationMarkerEmitter}, whose classes this plugin does not
     * load, so the two must change together.
     */
    private static String carrierDigest(String compilationId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(compilationId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("this Java runtime provides no SHA-256, which every Java runtime must",
                absent);
        }
    }

    /** The patterns one list parameter of this execution declares, one per child element. */
    private List<String> patterns(String parameter) {
        List<String> patterns = new ArrayList<>();
        Optional<Xpp3Dom> declared = element(parameter);
        if (declared.isPresent()) {
            for (Xpp3Dom pattern : declared.get().getChildren()) {
                if (pattern.getValue() != null && !pattern.getValue().isBlank()) {
                    patterns.add(pattern.getValue().strip());
                }
            }
        }
        return List.copyOf(patterns);
    }

    /**
     * The setting that switched this compilation off, read the way maven-compiler-plugin reads the skip
     * parameter of its own goal.
     *
     * @return how the build spells that setting, empty when javac compiles this source set
     */
    Optional<String> skip() {
        return setting(execution.skipParameter(), execution.skipProperty())
            .filter((Setting skip) -> Boolean.parseBoolean(skip.value()))
            .map(Setting::toString);
    }

    /**
     * The value of one compiler parameter, and where the build set it: the effective configuration of this
     * execution, then the property the parameter is bound to.
     */
    private Optional<Setting> setting(String parameter, String property) {
        Optional<String> configured = value(parameter);
        if (configured.isPresent()) {
            return Optional.of(new Setting('<' + parameter + '>' + configured.get() + "</" + parameter + '>',
                configured.get()));
        }
        return property(property);
    }

    /**
     * The value of one property, and where the build set it, from the command line, from the properties of the
     * JVM and from the properties of the project, in that order.
     */
    private Optional<Setting> property(String property) {
        for (Properties candidates : properties) {
            String value = candidates.getProperty(property);
            if (value != null && !value.isBlank()) {
                return Optional.of(new Setting(property + '=' + value.strip(), value.strip()));
            }
        }
        return Optional.empty();
    }

    /** The value of {@code name} in the effective configuration of this execution. */
    private Optional<String> value(String name) {
        return element(name).map(Xpp3Dom::getValue).filter(value -> !value.isBlank()).map(String::strip);
    }

    /** The element {@code name} of the effective configuration of this execution. */
    private Optional<Xpp3Dom> element(String name) {
        return configuration == null ? Optional.empty() : Optional.ofNullable(configuration.getChild(name));
    }

    /** The text of one child element, or {@code null} when the element or its text is absent. */
    private static String childValue(Xpp3Dom element, String name) {
        Xpp3Dom child = element.getChild(name);
        return child == null || child.getValue() == null ? null : child.getValue().strip();
    }

    /** Every compiler argument the effective configuration of this execution passes to javac. */
    private List<String> compilerArguments() {
        List<String> arguments = new ArrayList<>();
        Optional<Xpp3Dom> declared = element("compilerArgs");
        if (declared.isPresent()) {
            for (Xpp3Dom argument : declared.get().getChildren()) {
                if (argument.getValue() != null) {
                    arguments.add(argument.getValue().strip());
                }
            }
        }
        value("compilerArgument").ifPresent(arguments::add);
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
