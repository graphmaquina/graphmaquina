package io.github.graphmaquina.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import javax.lang.model.SourceVersion;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * What {@code maven-compiler-plugin} is configured to do for one compilation, and everything a StateGraph goal
 * must agree with before it writes or retires anything.
 *
 * <p>The generator and javac are two processes that must read and write the same files under the same language
 * version. Nothing in Maven enforces that: the compiler is configured in the POM and the generator is started
 * by this plugin, so a project can quietly point one of them somewhere else. Rather than mutate the execution
 * plan Maven already built, every goal reads the effective configuration of the compiler execution it belongs
 * to, and the {@code generate} and {@code generate-test} goals refuse to run when the two disagree, naming both
 * sides and the configuration that would fix it.</p>
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

    /** The prefix of the javac options that say whether to run annotation processing; javac takes the last one. */
    private static final String PROCESSING = "-proc:";

    /** The coordinates of the annotation processor that publishes the index of an accepted compilation. */
    private static final String PROCESSOR_GROUP = "io.github.graphmaquina";
    private static final String PROCESSOR_ARTIFACT = "graphmaquina-processor";

    /** That annotation processor, as a {@code -processor} list names it. */
    private static final String PROCESSOR_CLASS = "io.github.graphmaquina.processor.StateGraphProcessor";

    /** The javac option whose list names the only annotation processors javac runs. */
    private static final String PROCESSOR_LIST = "-processor";

    /**
     * The first Java release whose javac runs an annotation processor it finds on the class path only when annotation
     * processing is requested.
     */
    private static final int REQUESTED_PROCESSING_RELEASE = 23;

    /**
     * The two names of the javac option that gives javac a processor path of its own. javac takes the last path either
     * name gives it, and also takes the second as {@code --processor-path=path}.
     */
    private static final List<String> PROCESSOR_PATH_OPTIONS = List.of("-processorpath", "--processor-path");

    /** The javac option that gives javac a module path to find annotation processors on, which it then takes alone. */
    private static final String PROCESSOR_MODULE_PATH = "--processor-module-path";

    /** The class file of the StateGraph annotation processor, as a processor path holds it. */
    private static final String PROCESSOR_FILE = PROCESSOR_CLASS.replace('.', '/') + ".class";

    /**
     * The prefix of the javac options that list the debugging information javac writes. javac takes every one of
     * them, not the last: it writes an item when any of them lists it.
     */
    private static final String DEBUG_LIST = "-g:";

    /** The javac option that asks for no debugging information, which javac reads as a {@code -g:} list of no item. */
    private static final String NO_DEBUG = "-g:none";

    /** The items a {@code -g:} list of javac may name, as javac spells them. */
    private static final Set<String> DEBUG_ITEMS = Set.of("lines", "vars", "source");

    /** The javac option that names the encoding javac reads the sources in; javac takes the last one. */
    private static final String ENCODING = "-encoding";

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

    /** Maven's own evaluator of plugin parameters, over the session of this build. */
    private final PluginParameterExpressionEvaluator evaluator;

    private CompilerConfiguration(CompilerExecution execution, Xpp3Dom configuration, Path basedir,
            List<Properties> properties, MavenSession session) {
        this.execution = execution;
        this.configuration = configuration;
        this.basedir = basedir;
        this.properties = properties;
        // Maven evaluates the parameters of the compiler against the execution of the compiler, which this plugin
        // cannot see: an execution of no goal cannot complete an expression about the running goal or its plugin,
        // which evaluated(String) then keeps as it is written.
        this.evaluator = new PluginParameterExpressionEvaluator(session, new MojoExecution(new MojoDescriptor()));
    }

    /**
     * Reads the effective configuration of {@code execution} from {@code project} and {@code session}.
     *
     * @param project the project being built
     * @param session the session, which carries the properties the command line and the JVM set
     * @param execution the compiler execution the calling goal belongs to
     * @return the configuration, which reads only properties when the project model carries no compiler plugin
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
        return new CompilerConfiguration(execution, configuration, project.getBasedir().toPath(), properties,
            session);
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
     * @return the javac this compilation runs, which is on the JDK of the generator
     * @throws MojoExecutionException naming both sides when the compiler and the generator disagree
     */
    Javac requireAgreementWith(String generatorJava, ToolchainManager toolchains, MavenSession session)
            throws MojoExecutionException {
        requireSupportedRelease();
        Javac javac = javac(toolchains, session);
        if (!Jdk.same(javac.home(), Jdk.home(generatorJava, basedir))) {
            throw new MojoExecutionException("the " + execution.id() + " execution of maven-compiler-plugin "
                + javac.description() + ", while the StateGraph generator runs under " + generatorJava + ". The two"
                + " must be the same JDK, or the vocabulary the generator emits is not the vocabulary javac compiles."
                + " Configure <javaExecutable> of this plugin to the java of the JDK javac compiles with, or have"
                + " javac compile with the JDK of the generator.");
        }
        return javac;
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
     * <p>Without a release, a source or a target left unset is refused as well: {@code source} and {@code target}
     * of {@code maven-compiler-plugin} default to {@code 1.8}, so a compilation that sets neither a release nor
     * both of them compiles the generated vocabulary below Java 21.</p>
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
     * @return the release, empty when neither a release nor a target is set, or when the one read is not a release
     *     this plugin understands
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
     * in and writes the generated ones in, as maven-compiler-plugin 3.15.0 and plexus-compiler-javac 2.16.2 hand it to
     * javac. The configured encoding is {@code <encoding>} or the {@code encoding} property, else
     * {@code project.build.sourceEncoding}, the default maven-compiler-plugin gives that parameter.
     *
     * <p>A javac that compiles inside Maven reads the sources through a file manager the compiler opens in the
     * configured encoding, which then wins over every {@code -encoding} javac is given: the encoding is the configured
     * one, else the value of the last {@code -encoding} compiler argument. A javac the compiler forks gets the
     * configured encoding as an {@code -encoding} ahead of the compiler arguments, and takes the last {@code -encoding}
     * it is given: the encoding is the value of the last {@code -encoding} compiler argument, else the configured
     * one.</p>
     *
     * @param toolchains the component that resolves the {@code jdk} toolchains of this build, one of which makes the
     *     compiler fork javac
     * @param session the session the toolchains are resolved against
     * @return the name of the encoding, empty when the build names none. javac then reads the default charset of
     *     the JVM it runs in, which is UTF-8 from Java 18 on unless {@code file.encoding} names another, while the
     *     generator reads UTF-8.
     */
    Optional<String> encoding(ToolchainManager toolchains, MavenSession session) {
        Optional<Setting> configured = setting("encoding", "encoding")
            .or(() -> property("project.build.sourceEncoding"));
        Optional<Setting> argument = lastOption(List.of(ENCODING));
        Optional<Setting> read = javac(toolchains, session).forked()
            ? argument.or(() -> configured)
            : configured.or(() -> argument);
        return read.map(Setting::value);
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
     * The javac this compilation runs, resolved the way maven-compiler-plugin resolves it.
     *
     * <p>That is the {@code <executable>} it forks when it forks one; else, unless an {@code <executable>} is
     * configured without a fork — which makes the compiler ignore every toolchain and compile inside Maven — the
     * first jdk toolchain that matches its {@code <jdkToolchain>}, or the toolchain selected for the build when none
     * matches or none is required, whose javac it forks; else the JDK running Maven. An empty
     * {@code <jdkToolchain/>} is a requirement every jdk toolchain meets, so it selects the first jdk toolchain of
     * the build, and a {@code <jdkToolchain>} that matches nothing is no failure of its own: the compiler falls back,
     * and so does this method. javac runs in a process of its own when the compiler forks it, as it does once it
     * selects a toolchain, and inside the JVM running Maven otherwise.</p>
     *
     * <p>The last JDK is exact when javac compiles inside Maven. A {@code <fork>} with neither an
     * {@code <executable>} nor a toolchain makes plexus-compiler-javac look javac up itself: first at
     * {@code java.home/bin/javac} on macOS, at {@code java.home/../sh/javac} on AIX and at
     * {@code java.home/../bin/javac} elsewhere; then, on every one of these platforms, at
     * {@code $JAVA_HOME/bin/javac} when that first location is no file; and last as a plain {@code javac} from the
     * {@code PATH} when {@code JAVA_HOME} gives none either. This method still names the JDK running Maven. That is
     * the JDK of the forked javac when javac is found at {@code java.home/bin/javac}, which only the macOS lookup
     * tries, or at {@code $JAVA_HOME/bin/javac} with {@code JAVA_HOME} naming the JDK running Maven, as it does when
     * {@code mvn} starts Maven through it.</p>
     */
    private Javac javac(ToolchainManager toolchains, MavenSession session) {
        Optional<Setting> executable = setting("executable", "maven.compiler.executable");
        boolean fork = Boolean.parseBoolean(setting("fork", "maven.compiler.fork").map(Setting::value)
            .orElse("false"));
        if (executable.isPresent() && fork) {
            return new Javac(Jdk.home(executable.get().value(), basedir),
                "forks javac at " + executable.get().value(), true);
        }
        Toolchain selected = null;
        String selection = "";
        String fallback = "";
        if (executable.isEmpty()) {
            Optional<Map<String, String>> requirements = jdkToolchain();
            if (requirements.isPresent()) {
                List<Toolchain> candidates = toolchains.getToolchains(session, JDK, requirements.get());
                boolean empty = requirements.get().isEmpty();
                if (candidates.isEmpty()) {
                    fallback = ", since " + (empty ? "its empty <jdkToolchain>" : "its jdk toolchain "
                        + requirements.get()) + " matches no toolchain of this build";
                } else {
                    selected = candidates.get(0);
                    selection = empty
                        ? "the first jdk toolchain of this build, as its empty <jdkToolchain> selects, "
                        : "the jdk toolchain " + requirements.get() + ", which selects ";
                }
            }
            if (selected == null) {
                selected = toolchains.getToolchainFromBuildContext(JDK, session);
                selection = "the jdk toolchain selected for this build, ";
            }
        }
        String javac = selected == null ? null : selected.findTool("javac");
        if (javac != null) {
            return new Javac(Jdk.home(javac, basedir), "compiles with " + selection + javac + fallback, true);
        }
        Path running = Path.of(System.getProperty("java.home"));
        return new Javac(running, "compiles with the JDK running Maven, at " + running + fallback,
            fork || selected != null);
    }

    /**
     * Refuses a compilation that switches annotation processing off, that has javac run a list of annotation
     * processors without the StateGraph one or with a name that is not a valid qualified class name, which javac
     * cannot load, that gives javac a processor module path, whose processor path does not carry the StateGraph
     * annotation processor, or which, without a processor path, does not carry it among the dependencies of the
     * project, or carries it there for a javac that would not run it. A well-formed name of a class that is not on
     * the processor path, such as a misspelt one, is not refused: javac fails on it after the preparation, like any
     * failed compilation, and the next preparation reads the pending inventory that compilation left to clean up after
     * it.
     *
     * <p>The processor is the only thing that publishes the index of what javac accepted. A compilation that
     * runs without it publishes nothing, and since the preparation has already retired the index of the previous
     * cycle by then, the build would be left with no index at all and would fail only after that retirement, in
     * javac or in the {@code complete} goal.</p>
     *
     * <p>javac takes the last {@code -proc:} option it is given, and maven-compiler-plugin passes {@code <proc>}
     * ahead of the compiler arguments, so a {@code -proc:} compiler argument wins over it. Given a {@code -processor}
     * list, javac runs only the processors it names, and of several such lists it takes the last one;
     * maven-compiler-plugin passes {@code <annotationProcessors>} as such a list ahead of the compiler arguments, even
     * when it names nothing, so a {@code -processor} compiler argument wins over it as well.</p>
     *
     * <p>Given a processor path, javac looks for annotation processors there and not on the class path. The processor
     * path it takes is the last one a {@code -processorpath} or {@code --processor-path} compiler argument gives it,
     * else the one maven-compiler-plugin passes ahead of the compiler arguments when {@code <annotationProcessorPaths>}
     * holds a path; one that holds none counts as absent, as it does for the compiler. A processor path given as a
     * compiler argument, evaluated as Maven evaluates it for the compiler, must reach the class of the StateGraph
     * processor, looked up as the processor class loader of javac looks it up: through each entry of the path,
     * resolved where javac runs, what the {@code Class-Path} of a jar's manifest names, and the versioned entries a
     * multi-release jar holds for the release of the JDK that javac belongs to, as its {@code release} file declares
     * it, or for any release when that file declares none. A path that still holds an expression after that
     * evaluation, such as one about the running goal, or a property not set yet when {@code generate} runs, which a
     * plugin bound to a later phase may still set, is not checked; a compiler argument that is one expression naming
     * an object Maven does not take for a {@code String}, such as {@code ${project.file}}, is left out, as Maven leaves
     * it out. Otherwise {@code <annotationProcessorPaths>} must name the processor. A processor module path is
     * refused, which StateGraph does not support: javac rejects one next to a processor path, which
     * maven-compiler-plugin passes for every {@code <annotationProcessorPaths>} that holds a path, and without a
     * processor path it looks for the processors on that module path instead of the class path.</p>
     *
     * <p>Without a processor path, javac finds the processors on the class path, among the dependencies of the
     * project, which must then carry the processor. From Java 23 on, javac runs the processors it finds there only
     * when annotation processing is requested: by {@code -proc:full} or {@code -proc:only} as the last {@code -proc:}
     * option it takes, or by a {@code -processor} list. A processor that is only a dependency of the project is
     * therefore refused when {@code javac} belongs to a JDK whose release file declares Java 23 or later and nothing
     * requests processing. A JDK whose release file declares no release is taken for an older one, whose javac runs
     * such a processor anyway.</p>
     *
     * @param project the project being built, whose resolved artifacts carry the processor when javac is given no
     *     processor path
     * @param javac the javac this compilation runs, as {@link #requireAgreementWith} resolves it
     * @throws MojoExecutionException naming what switched the processor off, the list that leaves it out or holds a
     *     name that is not a valid qualified class name, where the processor is missing, or the settings that would
     *     make javac run a processor it finds on the class path
     */
    void requireAnnotationProcessor(MavenProject project, Javac javac) throws MojoExecutionException {
        Optional<Setting> proc = processing();
        if (proc.isPresent() && "none".equalsIgnoreCase(proc.get().value())) {
            throw noProcessing(proc.get().toString());
        }
        boolean listed = requireProcessorListed();
        Optional<Setting> modulePath = lastOption(List.of(PROCESSOR_MODULE_PATH));
        if (modulePath.isPresent()) {
            throw new MojoExecutionException(modulePath.get() + " of the " + execution.id() + " execution of"
                + " maven-compiler-plugin makes javac look for annotation processors on a module path, which"
                + " StateGraph does not support: it reads the processor path javac takes. Remove that argument, and"
                + " declare " + PROCESSOR_GROUP + ':' + PROCESSOR_ARTIFACT + " in <annotationProcessorPaths> of that"
                + " execution, after Lombok when the project uses Lombok.");
        }
        Optional<Setting> pathArgument = lastOption(PROCESSOR_PATH_OPTIONS);
        if (pathArgument.isPresent()) {
            if (pathArgument.get().value().contains("${") || holdsProcessor(pathArgument.get().value(), javac)) {
                return;
            }
            throw new MojoExecutionException(pathArgument.get() + " of the " + execution.id() + " execution of"
                + " maven-compiler-plugin gives javac the processor path it takes, the only place it then looks for"
                + " annotation processors, and that path reaches no " + PROCESSOR_FILE + ", so javac would compile the"
                + " generated vocabulary and publish no index of what it accepted, and the preparation that follows"
                + " would retire the index of the previous compilation. Add the jar of " + PROCESSOR_GROUP + ':'
                + PROCESSOR_ARTIFACT + ", and the jars it depends on, to that path, or remove that argument and declare"
                + " the processor in <annotationProcessorPaths> of that execution, after Lombok when the project uses"
                + " Lombok.");
        }
        Optional<Xpp3Dom> paths = element("annotationProcessorPaths")
            .filter((Xpp3Dom declared) -> declared.getChildCount() > 0);
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
                boolean requested = listed || proc.filter((Setting option) -> "full".equalsIgnoreCase(option.value())
                    || "only".equalsIgnoreCase(option.value())).isPresent();
                int release = Jdk.release(javac.home());
                if (release >= REQUESTED_PROCESSING_RELEASE && !requested) {
                    throw new MojoExecutionException(PROCESSOR_GROUP + ':' + PROCESSOR_ARTIFACT + " is only a"
                        + " dependency of this project, and the " + execution.id() + " execution of"
                        + " maven-compiler-plugin, which declares no path in <annotationProcessorPaths>, "
                        + javac.description() + ", a JDK of Java " + release + ": from Java "
                        + REQUESTED_PROCESSING_RELEASE + " on, javac runs no annotation processor it finds on the class"
                        + " path unless annotation processing is requested, so javac would compile the generated"
                        + " vocabulary and publish no index of what it accepted, and the preparation that follows would"
                        + " retire the index of the previous compilation. Configure <proc>full</proc> for that"
                        + " execution, or declare the processor in <annotationProcessorPaths> of that execution, after"
                        + " Lombok when the project uses Lombok.");
                }
                return;
            }
        }
        throw missingProcessor("the dependencies of this project, and the " + execution.id()
            + " execution of maven-compiler-plugin declares no path in <annotationProcessorPaths>");
    }

    /**
     * Whether a processor path javac is given reaches the class file of the StateGraph annotation processor, as
     * {@link ProcessorPath} looks it up, for the release the {@code release} file of the JDK that javac belongs to
     * declares. When that file declares none this plugin can read, the release is unknown, and a multi-release jar
     * then reaches the class under any of its versioned entries: refusing a build javac might accept would be worse
     * than letting an older javac fail on such a jar after the preparation. The path is split on the path separator,
     * as javac splits it, its empty entries left out, and each entry is resolved where javac runs.
     */
    private boolean holdsProcessor(String path, Javac javac) {
        List<Path> entries = new ArrayList<>();
        for (String entry : path.split(Pattern.quote(File.pathSeparator), -1)) {
            if (entry.isEmpty()) {
                continue;
            }
            try {
                entries.add(whereJavacRuns(entry, javac));
            } catch (InvalidPathException unusable) {
                // javac leaves out an entry it cannot name.
            }
        }
        return ProcessorPath.reaches(entries, PROCESSOR_FILE, Jdk.release(javac.home()));
    }

    /**
     * The value javac takes for an option from the compiler arguments, that of its last occurrence under any of
     * {@code names}: the argument that follows it, or, for a name spelled with two dashes, what follows an {@code =}
     * in the same argument. An occurrence that no argument follows gives an empty value.
     *
     * @return how the build spells that last occurrence, and its value; empty when no compiler argument gives the
     *     option
     */
    private Optional<Setting> lastOption(List<String> names) {
        List<String> arguments = compilerArguments();
        Optional<Setting> last = Optional.empty();
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if (names.contains(argument)) {
                // The value is the next argument, which is then no option of its own.
                String value = index + 1 < arguments.size() ? arguments.get(++index) : "";
                last = Optional.of(new Setting("the compiler argument " + argument + ' ' + value, value));
                continue;
            }
            for (String name : names) {
                if (name.startsWith("--") && argument.startsWith(name + '=')) {
                    last = Optional.of(new Setting("the compiler argument " + argument,
                        argument.substring(name.length() + 1)));
                }
            }
        }
        return last;
    }

    private MojoExecutionException noProcessing(String setting) {
        return new MojoExecutionException(setting + " switches annotation processing off for the "
            + execution.id() + " execution of maven-compiler-plugin, which StateGraph does not support: "
            + PROCESSOR_GROUP + ':' + PROCESSOR_ARTIFACT + " is what publishes the index of what javac"
            + " accepted, so this build would retire the index of the previous compilation and publish"
            + " nothing in its place. Run with annotation processing on.");
    }

    /**
     * Refuses the {@code -processor} list javac takes when it holds a name that is not a valid qualified class name,
     * such as an empty one or one with a space in it, which javac cannot load, or when it leaves out the StateGraph
     * annotation processor.
     *
     * <p>The list is the last {@code -processor} compiler argument, else {@code <annotationProcessors>}, whose
     * values maven-compiler-plugin 3.15.0 hands javac joined by commas, even when there is none, a value Maven left
     * empty reaching javac as {@code null}. javac 21 splits the list on every comma, dropping the empty names at its
     * end, though a list without a comma is one name, even when it is empty; it takes each name exactly as written
     * and stops at the first one it cannot load. A well-formed name of a class that is not on the processor path,
     * such as a misspelt one, passes this check, and javac fails on it after the preparation.</p>
     *
     * @return whether javac takes a {@code -processor} list, which then names the StateGraph annotation processor
     */
    private boolean requireProcessorListed() throws MojoExecutionException {
        List<String> arguments = compilerArguments();
        int option = arguments.lastIndexOf(PROCESSOR_LIST);
        String list;
        String gives;
        String removal;
        if (option >= 0 && option + 1 < arguments.size()) {
            list = arguments.get(option + 1);
            gives = "the compiler argument " + PROCESSOR_LIST + " of the " + execution.id()
                + " execution of maven-compiler-plugin gives";
            removal = "remove that argument";
        } else {
            Optional<List<String>> declared = parameterValues("annotationProcessors");
            if (declared.isEmpty()) {
                return false;
            }
            list = String.join(",", declared.get());
            gives = "the <annotationProcessors> of the " + execution.id() + " execution of maven-compiler-plugin give";
            removal = "remove that list, so javac runs every processor on its processor path";
        }
        String[] names = list.split(",");
        for (String name : names) {
            if (!SourceVersion.isName(name)) {
                throw new MojoExecutionException(gives + " javac the processor list '" + list + "', whose name '"
                    + name + "' is not a valid qualified class name, so javac could not load a processor of that"
                    + " name and would fail after the preparation retired the index of the previous compilation."
                    + " javac splits that list on every comma and takes each name exactly as written, spaces"
                    + " included. Correct or remove that name.");
            }
        }
        if (!Arrays.asList(names).contains(PROCESSOR_CLASS)) {
            throw new MojoExecutionException(gives + " javac the processor list '" + list + "', which leaves out "
                + PROCESSOR_CLASS + ", and javac runs only the annotation processors that list names: "
                + PROCESSOR_GROUP + ':' + PROCESSOR_ARTIFACT + " is what publishes the index of what javac accepted,"
                + " so this build would retire the index of the previous compilation and publish nothing in its"
                + " place. Add " + PROCESSOR_CLASS + " to that list, after the processors of Lombok when the project"
                + " uses Lombok, or " + removal + '.');
        }
        return true;
    }

    /**
     * Refuses a compilation that can end without the classes the completion publishes while the build goes on:
     * {@code proc=only}, as the last {@code -proc:} option javac takes, runs the annotation processors and compiles
     * nothing, and {@code failOnError=false} lets a compilation javac rejected reach the completion. Either way the
     * preparation has retired the outputs of the previous cycle by then, and the completion would find no class of
     * this compilation to publish.
     *
     * @throws MojoExecutionException naming the setting that lets the compilation end without its classes
     */
    void requireCompiledClasses() throws MojoExecutionException {
        Optional<Setting> configured = setting("proc", "maven.compiler.proc");
        Optional<Setting> proc = processing();
        if (proc.isPresent() && "only".equalsIgnoreCase(proc.get().value())) {
            throw compilesNothing(proc.get().toString(), proc.equals(configured)
                ? "Leave proc unset, or set it to full."
                : "Remove that argument from the configuration of that execution.");
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

    /**
     * Refuses a compilation that compiles only part of its module. With {@code useIncrementalCompilation} false,
     * maven-compiler-plugin 3.15.0 hands javac only the sources its stale-source scanner selects, those whose class
     * file is missing or older than the source; with the default, {@code true}, it recompiles the whole module
     * whenever it compiles at all. The preparation retires only the outputs the previous cycle owned, so over the
     * class files of a previous build a compilation with the setting off would hand javac the generated sources
     * whose classes were retired but not the unchanged hand-written sources, whose class files survive, while the
     * annotation processor must see every declaration the index publishes.
     *
     * @throws MojoExecutionException naming the setting that switches {@code useIncrementalCompilation} off
     */
    void requireWholeModuleCompilation() throws MojoExecutionException {
        Optional<Setting> incremental = setting("useIncrementalCompilation",
            "maven.compiler.useIncrementalCompilation");
        if (incremental.isPresent() && !Boolean.parseBoolean(incremental.get().value())) {
            throw new MojoExecutionException(incremental.get() + " makes the " + execution.id() + " execution of"
                + " maven-compiler-plugin compile only the sources whose class file is missing or older than the"
                + " source, which StateGraph does not support: the preparation retires only the outputs the previous"
                + " cycle owned, so over a previous build javac would compile the generated vocabulary without the"
                + " unchanged hand-written sources, whose class files survive, and the annotation processor would"
                + " miss declarations the index publishes. Leave useIncrementalCompilation at its default, true.");
        }
    }

    /**
     * Refuses a compilation whose class files javac writes without the {@code SourceFile} attribute, through which
     * the completion attributes each class file to the source it was compiled from; without it the completion would
     * find no class of this compilation to publish, after the preparation retired the outputs of the previous one.
     *
     * <p>javac writes that attribute when it is given no {@code -g:} option, of which {@code -g:none} is one, or when
     * one of its {@code -g:} options lists {@code source}, whatever their order: it takes every one of them, not the
     * last. A plain {@code -g} is no such option. maven-compiler-plugin 3.15.0 passes {@code <debuglevel>}, or its
     * {@code maven.compiler.debuglevel} property, as {@code -g:<debuglevel>} ahead of the compiler arguments, but only
     * while {@code <debug>}, or {@code maven.compiler.debug}, is on, as it is by default: with it off it passes no
     * {@code -g} option at all. A {@code -g:} option javac cannot read, such as one that names an item in capitals,
     * is not refused: it fails the compilation before javac compiles anything, like any option javac does not
     * know.</p>
     *
     * @throws MojoExecutionException naming the settings that leave the attribute out
     */
    void requireSourceFileAttribute() throws MojoExecutionException {
        List<Setting> options = new ArrayList<>();
        boolean debug = setting("debug", "maven.compiler.debug")
            .map((Setting on) -> Boolean.parseBoolean(on.value())).orElse(true);
        Optional<Setting> level = setting("debuglevel", "maven.compiler.debuglevel");
        if (debug && level.isPresent()) {
            options.add(new Setting(level.get().toString(), DEBUG_LIST + level.get().value()));
        }
        for (String argument : compilerArguments()) {
            if (argument.startsWith(DEBUG_LIST)) {
                options.add(new Setting("the compiler argument " + argument, argument));
            }
        }
        if (options.isEmpty()) {
            return;
        }
        List<String> settings = new ArrayList<>();
        for (Setting option : options) {
            Optional<List<String>> items = debugItems(option.value());
            if (items.isEmpty() || items.get().contains("source")) {
                return;
            }
            settings.add(option.toString());
        }
        boolean one = settings.size() == 1;
        throw new MojoExecutionException(String.join(" and ", settings) + (one ? " makes" : " make")
            + " javac write the class files of the " + execution.id() + " execution of maven-compiler-plugin without"
            + " the SourceFile attribute, which javac writes only when it is given no -g: option or one that lists"
            + " source. StateGraph does not support that: the completion attributes each class file to the source it"
            + " was compiled from through that attribute, so it would find no class of this compilation to publish,"
            + " after the preparation retired the outputs of the previous compilation. Remove "
            + (one ? "that setting" : "those settings") + ", or have one -g: option list source, as"
            + " <debuglevel>lines,vars,source</debuglevel> does.");
    }

    /**
     * The items one {@code -g:} option lists, as javac reads it: none for {@code -g:none}, else the names between
     * its commas, each of which must be {@code lines}, {@code vars} or {@code source}.
     *
     * @return the items, empty when javac cannot read the option
     */
    private static Optional<List<String>> debugItems(String option) {
        if (option.equals(NO_DEBUG)) {
            return Optional.of(List.of());
        }
        List<String> items = List.of(option.substring(DEBUG_LIST.length()).split(",+"));
        return DEBUG_ITEMS.containsAll(items) ? Optional.of(items) : Optional.empty();
    }

    /**
     * Refuses a compilation whose classes javac writes anywhere but the class output of the project, where the
     * completion reads them and would find no class of this compilation to publish, after the preparation retired
     * the outputs of the previous one.
     *
     * <p>maven-compiler-plugin 3.15.0 writes them to the {@code <outputDirectory>} of the execution, whose default is
     * the class output of the project, and which the main compilation also reads from its
     * {@code maven.compiler.outputDirectory} property; Maven resolves a relative one against the project directory.
     * The main compilation writes them below {@code META-INF/versions} of that directory, in the directory of its
     * release, when {@code <multiReleaseOutput>} is true. The compiler passes that directory to javac as a {@code -d}
     * ahead of the compiler arguments, and javac takes the last {@code -d} it is given, so a {@code -d} compiler
     * argument moves the classes too, and the index the annotation processor writes among them, to the directory it
     * names, resolved where javac runs. Directories are compared once their symbolic links are resolved, as
     * {@link #canonical} compares them.</p>
     *
     * @param request the request the calling goal generates for
     * @param javac the javac this compilation runs, as {@link #requireAgreementWith} resolves it
     * @throws MojoExecutionException naming the setting that moves the classes and where the project keeps them
     */
    void requireClassesOutput(StateGraphMojo.Request request, Javac javac) throws MojoExecutionException {
        Path expected = Path.of(request.classesOutput()).toAbsolutePath().normalize();
        boolean main = execution == CompilerExecution.MAIN;
        Optional<Setting> directory = main
            ? setting("outputDirectory", "maven.compiler.outputDirectory")
            : configured("outputDirectory");
        String writes = " execution of maven-compiler-plugin write its classes ";
        String consequence = ", while StateGraph completes this compilation in " + expected + ", the class output of"
            + " the project: the completion would find no class of this compilation to publish there, after the"
            + " preparation retired the outputs of the previous compilation.";
        if (directory.isPresent() && !canonical(absolute(directory.get().value())).equals(canonical(expected))) {
            throw new MojoExecutionException(directory.get() + " makes the " + execution.id() + writes + "to "
                + absolute(directory.get().value()) + consequence + " Leave outputDirectory unset for that execution,"
                + " or move the class output of the project itself with <" + (main ? "outputDirectory"
                : "testOutputDirectory") + "> in the <build> of the POM.");
        }
        Optional<Setting> versioned = main ? configured("multiReleaseOutput") : Optional.empty();
        if (versioned.isPresent() && Boolean.parseBoolean(versioned.get().value())) {
            Path versions = expected.resolve("META-INF").resolve("versions");
            Optional<Setting> release = setting("release", "maven.compiler.release");
            throw new MojoExecutionException(versioned.get() + " makes the " + execution.id() + writes
                + (release.isPresent() ? "to " + versions.resolve(release.get().value()) : "below " + versions)
                + consequence + " Remove multiReleaseOutput from that execution.");
        }
        Optional<Setting> destination = lastOption(List.of("-d"));
        if (destination.isPresent()) {
            Path written = whereJavacRuns(destination.get().value(), javac);
            if (!canonical(written).equals(canonical(expected))) {
                throw new MojoExecutionException(destination.get() + " of the " + execution.id() + " execution of"
                    + " maven-compiler-plugin makes javac write its classes to " + written + consequence
                    + " Remove that argument from the configuration of that execution.");
            }
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
     * <p>The annotation processor opens the path it is given as it is, so a relative one is resolved against the
     * directory javac runs in: the project directory for a javac the compiler forks, which it starts there, and the
     * directory Maven was started in for a javac that compiles inside the JVM running Maven. In a build of several
     * modules that directory is not the directory of every module. The file javac reads and the manifest are compared
     * once their symbolic links are resolved, as {@link #canonical} compares them, since the working directory of the
     * JVM is named with its links resolved while a {@code -f} option can name the project directory through one.</p>
     *
     * @param request the request the calling goal generates for
     * @param javac the javac this compilation runs, as {@link #requireAgreementWith} resolves it
     * @throws MojoExecutionException printing the argument the execution must declare
     */
    void requireManifestArgument(StateGraphMojo.Request request, Javac javac) throws MojoExecutionException {
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
            Path read = whereJavacRuns(value, javac);
            if (!canonical(read).equals(canonical(expected))) {
                String resolved = Path.of(value).isAbsolute() ? "" : javac.forked()
                    ? ", which javac, forked in the project directory, resolves to " + read
                    : ", which javac, compiling inside the JVM running Maven, resolves against the directory Maven"
                        + " was started in, to " + read;
                throw new MojoExecutionException("the " + execution.id() + " execution of"
                    + " maven-compiler-plugin reads the annotation manifest " + value + resolved
                    + ", while the StateGraph generator writes " + expected
                    + ". Declare exactly this compiler argument for that execution: " + option);
            }
        }
    }

    /**
     * Refuses a generated-sources directory other than the one the request retires from, since the sources the
     * annotation processor writes are owned outputs of this compilation. The two javac executions name that
     * directory with two different parameters, and each is read with its own. The compiler passes that directory to
     * javac as a {@code -s} ahead of the compiler arguments, and javac takes the last {@code -s} it is given, so a
     * {@code -s} compiler argument, resolved where javac runs, must name it as well. Directories are compared once
     * their symbolic links are resolved, as {@link #canonical} compares them.
     *
     * @param request the request the calling goal generates for
     * @param javac the javac this compilation runs, as {@link #requireAgreementWith} resolves it
     * @throws MojoExecutionException naming both directories
     */
    void requireProcessorSourcesOutput(StateGraphMojo.Request request, Javac javac) throws MojoExecutionException {
        String parameter = execution.generatedSourcesParameter();
        Optional<String> configured = value(parameter);
        Path expected = Path.of(request.processorSourcesOutput()).toAbsolutePath().normalize();
        if (configured.isPresent() && !canonical(absolute(configured.get())).equals(canonical(expected))) {
            throw new MojoExecutionException("the " + execution.id() + " execution of maven-compiler-plugin"
                + " writes the sources of the annotation processor to " + configured.get()
                + ", while StateGraph owns and retires " + expected + ". Configure <" + parameter + '>'
                + expected + "</" + parameter + "> for that execution, or leave it unset.");
        }
        Optional<Setting> sources = lastOption(List.of("-s"));
        if (sources.isPresent()) {
            Path written = whereJavacRuns(sources.get().value(), javac);
            if (!canonical(written).equals(canonical(expected))) {
                throw new MojoExecutionException(sources.get() + " of the " + execution.id() + " execution of"
                    + " maven-compiler-plugin makes the annotation processor write its sources to " + written
                    + ", while StateGraph owns and retires " + expected + ". Remove that argument from the"
                    + " configuration of that execution.");
            }
        }
    }

    /**
     * The patterns javac selects the sources of every root of this compilation with: {@code <includes>} and
     * {@code <excludes>} for the main compilation, {@code <testIncludes>} and {@code <testExcludes>} for the test
     * one, whose excludes maven-compiler-plugin 3.15.0 joins with its {@code <testIncrementalExcludes>} when it
     * selects them. The main compilation leaves its {@code <incrementalExcludes>} out of that selection, where it
     * joins its excludes with themselves instead, and reads them only in its check for stale sources, which
     * {@link #requireCarrierSelected} accounts for. None of these lists has a user property, so only the
     * configuration declares them.
     *
     * @return the patterns, which narrow nothing when the execution declares none of these lists
     */
    SourcePatterns sourcePatterns() {
        List<String> excludes = new ArrayList<>();
        for (String parameter : excludesParameters()) {
            excludes.addAll(patterns(parameter));
        }
        return new SourcePatterns(patterns(execution.includesParameter()), excludes);
    }

    /**
     * The source roots javac compiles when this execution declares {@code <compileSourceRoots>} of its own, which
     * replace the source roots of the project: each as an absolute path, a relative one resolved, as
     * maven-compiler-plugin 3.15.0 resolves it, against the working directory of the JVM running Maven. An empty value
     * is left out.
     *
     * @return the roots; empty when the execution declares no such element, or one with neither text nor a child,
     *     for which Maven gives the compiler its default, the source roots of the project, so that javac compiles those
     */
    Optional<List<String>> compileSourceRoots() {
        Optional<Xpp3Dom> element = element("compileSourceRoots");
        if (element.isEmpty() || element.get().getChildCount() == 0
                && (element.get().getValue() == null || element.get().getValue().isBlank())) {
            return Optional.empty();
        }
        return parameterValues("compileSourceRoots").map((List<String> declared) -> {
            List<String> roots = new ArrayList<>();
            for (String root : declared) {
                if (root != null) {
                    roots.add(Path.of(root).toAbsolutePath().toString());
                }
            }
            return List.copyOf(roots);
        });
    }

    /**
     * Refuses {@code <compileSourceRoots>} that leave out the directory the generator writes into. They replace the
     * source roots of the project for javac, the one this plugin adds included, so javac would compile the sources of
     * this compilation without the vocabulary they use, and the completion would find nothing of this compilation to
     * publish, after the preparation retired the outputs of the previous one. A root is that directory when both name
     * it once their symbolic links are resolved, as {@link #canonical} compares them.
     *
     * @param request the request the calling goal generates for
     * @throws MojoExecutionException naming the directory the compiler leaves out
     */
    void requireGeneratedSourcesCompiled(StateGraphMojo.Request request) throws MojoExecutionException {
        Optional<List<String>> roots = compileSourceRoots();
        Path generated = Path.of(request.sourcesOutput()).toAbsolutePath().normalize();
        if (roots.isPresent() && roots.get().stream()
                .noneMatch((String root) -> canonical(Path.of(root)).equals(canonical(generated)))) {
            throw new MojoExecutionException("the <compileSourceRoots> of the " + execution.id() + " execution of"
                + " maven-compiler-plugin replace the source roots of the project and leave out " + generated
                + ", where the StateGraph generator writes the vocabulary of this compilation, so javac would compile"
                + " the sources of this compilation without it, and the completion would find nothing of this"
                + " compilation to publish, after the preparation retired the outputs of the previous compilation."
                + " Add <compileSourceRoot>" + generated + "</compileSourceRoot> to them, or leave compileSourceRoots"
                + " unset.");
        }
    }

    /**
     * The parameters whose patterns leave sources of this compilation out of what javac compiles: {@code excludes}
     * for the main compilation, and {@code testExcludes} and {@code testIncrementalExcludes} for the test one.
     */
    private List<String> excludesParameters() {
        return execution == CompilerExecution.TEST
            ? List.of(execution.excludesParameter(), "testIncrementalExcludes")
            : List.of(execution.excludesParameter());
    }

    /**
     * Refuses includes or excludes that leave out the carrier of this compilation, the excludes being those
     * {@link #sourcePatterns} reads. maven-compiler-plugin selects the sources of every root with them, the root this
     * plugin generates into included, so patterns that miss the carrier would have javac compile the generated
     * vocabulary without it, and the completion would find nothing of this compilation to publish. Includes that
     * hold only empty values select no source at all, so javac would not run. The carrier is matched under the very
     * name the generator gives it: {@code GraphCompilation} and the first sixteen hexadecimal digits of the SHA-256
     * of the identity of the compilation, in {@code io/github/graphmaquina/generated/bootstrap}. It is selected as
     * {@link SourcePatterns#selects} selects a source, so an include that matches it is not enough when the scanner of
     * the compiler never enters a directory above it, as for {@code /io/github/graphmaquina/generated/**}, whose
     * leading separator keeps the scanner out of every directory the include does not match itself.
     *
     * <p>The main compilation also leaves the sources its {@code <incrementalExcludes>} match out of the check for
     * stale sources that decides whether it runs javac at all, while it still compiles them. The carrier, whose class
     * file the completion deletes, is what makes that check compile the module again after the preparation, so an
     * {@code <incrementalExcludes>} pattern that matches the carrier is refused as well: in a build in which nothing
     * else changed, javac would not run. The test compilation reads its {@code <testIncrementalExcludes>} in both
     * places, and they are among the excludes above.</p>
     *
     * @param request the request the calling goal generates for
     * @throws MojoExecutionException naming the pattern that leaves the carrier out, or the includes that hold only
     *     empty values, and the configuration that lets it in
     */
    void requireCarrierSelected(StateGraphMojo.Request request) throws MojoExecutionException {
        String carrier = CARRIER_PACKAGE + "/GraphCompilation" + carrierDigest(request.compilationId()) + ".java";
        String compiler = " of the " + execution.id() + " execution of maven-compiler-plugin";
        String consequence = ", the carrier the StateGraph generator emits for this compilation under "
            + request.sourcesOutput() + ": the compiler applies its patterns to that directory too, so javac would"
            + " compile the generated vocabulary without its carrier and the completion would find nothing to"
            + " publish.";
        String remedy = " Change that pattern so it matches nothing under io/github/graphmaquina/generated.";
        for (String parameter : excludesParameters()) {
            for (String exclude : patterns(parameter)) {
                if (SourcePatterns.matches(exclude, carrier)) {
                    throw new MojoExecutionException("the <" + parameter + "> pattern " + exclude + compiler
                        + " leaves out " + carrier + consequence + remedy);
                }
            }
        }
        if (execution == CompilerExecution.MAIN) {
            for (String exclude : patterns("incrementalExcludes")) {
                if (SourcePatterns.matches(exclude, carrier)) {
                    throw new MojoExecutionException("the <incrementalExcludes> pattern " + exclude + compiler
                        + " leaves " + carrier + ", the carrier the StateGraph generator emits for this compilation"
                        + " under " + request.sourcesOutput() + ", out of the check the compiler makes for stale"
                        + " sources before it runs javac. The carrier has no class file once a compilation completes,"
                        + " and that is what makes the compiler compile the module again after the preparation:"
                        + " without it, a build in which nothing else changed would not run javac, and the completion"
                        + " would find nothing of this compilation to publish." + remedy);
                }
            }
        }
        // maven-compiler-plugin includes every source when the includes are empty.
        if (parameterValues(execution.includesParameter()).orElse(List.of()).isEmpty()) {
            return;
        }
        List<String> includes = patterns(execution.includesParameter());
        if (includes.isEmpty()) {
            throw new MojoExecutionException("the <" + execution.includesParameter() + ">" + compiler + " hold only"
                + " empty values, which Maven turns into null: maven-compiler-plugin then adds no default include,"
                + " and the scanner of plexus-utils leaves the null out, so the compiler would select no source, "
                + carrier + " included, javac would not run and the completion would find nothing of this compilation"
                + " to publish. Remove those empty values, so that maven-compiler-plugin includes every source.");
        }
        SourcePatterns selection = sourcePatterns();
        if (selection.selects(carrier)) {
            return;
        }
        // An include can match the carrier while the scanner never reaches it, as one that starts with a separator.
        String unreached = "";
        Optional<String> skipped = selection.skippedDirectory(carrier);
        Optional<String> matching = includes.stream()
            .filter((String include) -> SourcePatterns.matches(include, carrier)).findFirst();
        if (skipped.isPresent() && matching.isPresent()) {
            unreached = " The include " + matching.get() + " matches that path, but the scanner of the compiler never"
                + " enters " + skipped.get() + ": it enters a directory only when an include matches that directory and"
                + " no exclude does, or when an include that does not start with a separator could match a path below"
                + " it.";
        }
        throw new MojoExecutionException("the <" + execution.includesParameter() + ">" + compiler + ", " + includes
            + ", select no " + carrier + consequence + unreached + " Add <include>" + GENERATED_INCLUDE
            + "</include> to the <" + execution.includesParameter() + "> of that execution.");
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

    /** The patterns one list parameter of this execution declares, as Maven converts it, empty ones left out. */
    private List<String> patterns(String parameter) {
        List<String> patterns = new ArrayList<>();
        for (String pattern : parameterValues(parameter).orElse(List.of())) {
            if (pattern != null) {
                patterns.add(pattern);
            }
        }
        return List.copyOf(patterns);
    }

    /**
     * The values Maven gives one array or collection parameter of the effective configuration of this execution,
     * as the sisu-plexus configurator of Maven 3.9.12 converts it: the text of the element split on every comma,
     * empty parts included, when it has text, else the text of each child element, in order. A value that is empty
     * becomes {@code null}. The text is read from the effective model, where Maven has interpolated the properties
     * but not the expressions only that configurator evaluates, such as those of {@code ${session}}, which are read
     * as written, as everywhere in this class.
     *
     * @return the values, empty when the configuration has no such element
     */
    private Optional<List<String>> parameterValues(String parameter) {
        Optional<Xpp3Dom> declared = element(parameter);
        if (declared.isEmpty()) {
            return Optional.empty();
        }
        List<String> values = new ArrayList<>();
        String text = declared.get().getValue();
        if (text != null && !text.isEmpty()) {
            for (String part : text.split(",", -1)) {
                values.add(part.isEmpty() ? null : part);
            }
        } else {
            for (Xpp3Dom child : declared.get().getChildren()) {
                String value = child.getValue();
                values.add(value == null || value.isEmpty() ? null : value);
            }
        }
        return Optional.of(Collections.unmodifiableList(values));
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
        return configured(parameter).or(() -> property(property));
    }

    /** The value of one compiler parameter bound to no property, and how the effective configuration spells it. */
    private Optional<Setting> configured(String parameter) {
        return value(parameter).map((String configured) -> new Setting('<' + parameter + '>' + configured + "</"
            + parameter + '>', configured));
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

    /**
     * The compiler arguments of the effective configuration of this execution, as maven-compiler-plugin 3.15.0
     * adds them to the arguments of the compiler: the entries of the deprecated {@code <compilerArguments>} map, by
     * key, each key once with its last value, since Maven converts that element into a sorted map; then
     * {@code <compilerArgument>}; then the values of {@code <compilerArgs>}, as {@link #parameterValues} reads them,
     * an empty one left out. The test compilation takes {@code <testCompilerArguments>} and
     * {@code <testCompilerArgument>} over the first two when they are set. Each entry of the map gives its key,
     * prefixed with a dash unless it starts with one, then its value unless that is empty; a key that then starts
     * with {@code -A} and has a value gives the single argument {@code key=value} instead. Each value is evaluated
     * first, as Maven evaluates the parameters it hands the compiler, which {@link #evaluated} describes: a value of
     * the map that evaluates to nothing leaves its key alone, and {@code <compilerArgument>} or a value of
     * {@code <compilerArgs>} that evaluates to nothing or to an empty text gives no argument, since
     * plexus-compiler-javac passes no empty argument to javac.
     */
    private List<String> compilerArguments() {
        List<String> arguments = new ArrayList<>();
        Optional<Xpp3Dom> testMap = execution == CompilerExecution.TEST
            ? element("testCompilerArguments")
            : Optional.empty();
        Optional<Xpp3Dom> map = testMap.or(() -> element("compilerArguments"));
        if (map.isPresent()) {
            Map<String, String> entries = new TreeMap<>();
            for (Xpp3Dom entry : map.get().getChildren()) {
                String value = entry.getValue();
                entries.put(entry.getName(), value == null || value.isEmpty() ? "" : evaluated(value).orElse(""));
            }
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                String key = entry.getKey().startsWith("-") ? entry.getKey() : '-' + entry.getKey();
                if (key.startsWith("-A") && !entry.getValue().isEmpty()) {
                    arguments.add(key + '=' + entry.getValue());
                    continue;
                }
                arguments.add(key);
                if (!entry.getValue().isEmpty()) {
                    arguments.add(entry.getValue());
                }
            }
        }
        Optional<String> testArgument = execution == CompilerExecution.TEST
            ? value("testCompilerArgument")
            : Optional.empty();
        testArgument.or(() -> value("compilerArgument")).flatMap(this::evaluated)
            .filter((String argument) -> !argument.isEmpty())
            .ifPresent(arguments::add);
        for (String argument : parameterValues("compilerArgs").orElse(List.of())) {
            if (argument != null) {
                evaluated(argument).filter((String text) -> !text.isEmpty()).ifPresent(arguments::add);
            }
        }
        return List.copyOf(arguments);
    }

    /**
     * {@code value} as Maven configures a {@code String} parameter of the compiler with it, as far as that can be told
     * when {@code generate} runs: evaluated as a {@code String} by Maven's own evaluator of plugin parameters. That
     * completes what the effective model leaves for it, such as {@code ${settings.localRepository}} or
     * {@code ${session.executionRootDirectory}}. It keeps {@code $${...}} as the text {@code ${...}}, and turns
     * {@code $$} into {@code $} only after the last {@code ${...}} of the value, or in the whole value when it holds
     * none. An expression it cannot complete within a longer value stays as it is written. A value that is one
     * expression naming an object Maven does not take for a {@code String}, such as {@code ${project.file}},
     * evaluates to nothing, as it does whenever Maven evaluates it. A value that is one expression the evaluator
     * cannot complete stays as it is written instead: Maven evaluates the parameters of the compiler only when the
     * compiler runs, after a plugin bound to a later phase may have set a property, and against the execution of the
     * compiler, which this plugin cannot see, so an expression about the running goal or its plugin, such as
     * {@code ${plugin.version}}, completes there and not here. So does a value the evaluator fails on.
     *
     * @return the evaluated value, empty when Maven evaluates it to nothing whenever it evaluates it
     */
    private Optional<String> evaluated(String value) {
        try {
            Object typed = evaluator.evaluate(value, String.class);
            if (typed != null) {
                return Optional.of(String.valueOf(typed));
            }
            // Typed as a String, the evaluator gives nothing both for an expression it cannot complete and for one
            // naming an object it does not take for a String; untyped, it gives that object.
            return evaluator.evaluate(value) == null ? Optional.of(value) : Optional.empty();
        } catch (ExpressionEvaluationException | RuntimeException unevaluable) {
            return Optional.of(value);
        }
    }

    /**
     * The {@code -proc:} option javac takes for this compilation, the last one it is given: the last {@code -proc:}
     * compiler argument, else {@code <proc>} or its property, which maven-compiler-plugin 3.15.0 passes as
     * {@code -proc:<value>} ahead of the compiler arguments.
     *
     * @return how the build spells that option, and its value; empty when the build sets none
     */
    private Optional<Setting> processing() {
        List<String> arguments = compilerArguments();
        for (int index = arguments.size() - 1; index >= 0; index--) {
            String argument = arguments.get(index);
            if (argument.startsWith(PROCESSING)) {
                return Optional.of(new Setting("the compiler argument " + argument,
                    argument.substring(PROCESSING.length())));
            }
        }
        return setting("proc", "maven.compiler.proc");
    }

    /**
     * The requirements the compiler selects its own JDK with: empty when the execution declares no
     * {@code <jdkToolchain>}, and an empty map for one that declares no requirement, which maven-compiler-plugin still
     * hands the toolchain manager, as it hands it any other.
     */
    private Optional<Map<String, String>> jdkToolchain() {
        Optional<Xpp3Dom> toolchain = element("jdkToolchain");
        if (toolchain.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> requirements = new LinkedHashMap<>();
        for (Xpp3Dom requirement : toolchain.get().getChildren()) {
            requirements.put(requirement.getName(),
                requirement.getValue() == null ? "" : requirement.getValue().strip());
        }
        return Optional.of(Map.copyOf(requirements));
    }

    /**
     * The file a {@code java.io.File} parameter of the compiler names, as Maven aligns it to the project directory:
     * an absolute one as it is, a relative one against the project directory, normalized.
     */
    private Path absolute(String path) {
        Path candidate = Path.of(path);
        return candidate.isAbsolute() ? candidate : basedir.resolve(candidate).normalize();
    }

    /**
     * The file a path that javac is given names, a relative one resolved against the directory javac runs in: the
     * project directory for a javac the compiler forks, which it starts there, and the working directory of the JVM
     * running Maven, which is the directory Maven was started in, for a javac that compiles inside that JVM.
     */
    private Path whereJavacRuns(String path, Javac javac) {
        Path candidate = Path.of(path);
        if (candidate.isAbsolute()) {
            return candidate;
        }
        return javac.forked() ? basedir.resolve(candidate) : candidate.toAbsolutePath();
    }

    /**
     * {@code path} as an absolute path whose longest existing part is resolved as the file system resolves it, every
     * symbolic link first and every {@code ..} after it, as javac resolves the directory it writes into, with the rest
     * appended and normalized. Two names of one file or directory then compare equal, whether it exists yet or not:
     * the working directory of the JVM is named with its links resolved, while Maven keeps the name a {@code -f}
     * option gives the project directory, which can lead through a link.
     *
     * @param path a path
     * @return the path to compare it by, or its absolute, normalized form when no part of it can be read
     */
    static Path canonical(Path path) {
        Path absolute = path.toAbsolutePath();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute.normalize();
        }
        try {
            return existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
        } catch (IOException unreadable) {
            return absolute.normalize();
        }
    }

    /**
     * The javac one compilation runs, as maven-compiler-plugin selects it.
     *
     * @param home the home of the JDK that javac belongs to
     * @param description how the compiler selects that javac, as a failure names it after the execution: the javac it
     *     forks, the toolchain whose javac it compiles with, or the JDK running Maven
     * @param forked whether javac runs in a process of its own, which the compiler starts in the project directory,
     *     rather than inside the JVM running Maven
     */
    record Javac(Path home, String description, boolean forked) {
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
