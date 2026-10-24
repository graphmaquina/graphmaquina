package io.github.graphmaquina.maven;

import lombok.AccessLevel;
import lombok.Getter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.DefaultLifecycles;
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

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

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
 * <p>That execution is the one of the compiler's goal, {@code compile} or {@code testCompile}, that the default
 * lifecycle of the project runs and that compiles the vocabulary the generator writes, whatever its id. An execution
 * runs there when it is bound to a phase of the default lifecycle, the one the StateGraph goals run in: the phase it
 * declares or else the default phase of its goal. One bound to {@code none}, as kotlin-maven-plugin asks of
 * {@code default-compile} and {@code default-testCompile} in a build that compiles its Java sources through
 * executions of its own, never runs and is never read, and neither is one bound to a phase of another lifecycle,
 * such as {@code pre-site}, which runs only when the build invokes that lifecycle, never between the preparation and
 * the completion of a compilation. An execution compiles the vocabulary unless it declares
 * {@code <compileSourceRoots>} of its own that leave out the directory the generator writes into, as an execution
 * that compiles another source root into a multi-release jar does; such an execution runs untouched. A compilation
 * no execution runs is skipped, one that two running executions compile the vocabulary of is refused, and so is one
 * whose execution runs before the preparation or after the completion of the compilation.</p>
 *
 * <p>A setting is read the way Maven itself resolves a parameter: the effective configuration of the compiler
 * execution first — the one Maven merged the configuration the plugin declares for all of its executions into,
 * where what the execution declares itself, a list included, wins over the plugin's — and then the user property the
 * parameter is bound to, from the command line, from the properties of the JVM and from the properties of the project,
 * in that order. Reading only the XML would miss a project that configures its compiler entirely through
 * {@code maven.compiler.*} properties, which is a common and fully supported way to write a POM. Every value is read as
 * Maven evaluates it for the compiler, through Maven's own evaluator of plugin parameters, so an expression the
 * effective model leaves for that evaluator, such as {@code ${session.executionRootDirectory}}, is completed before the
 * value is compared with anything or handed to the generator.</p>
 *
 * <p>{@link CompilerExecutionOrder} checks where the executions of the compiler run in the build, and
 * {@link ProcessorPathRequirements} and {@link CompiledSources} check the annotation processor javac runs and the
 * sources it compiles, reading the settings of this execution through this class.</p>
 */
final class CompilerConfiguration {

    /** The processor option that tells the annotation processor which manifest describes this compilation. */
    private static final String MANIFEST_OPTION = "-Agraphmaquina.annotationManifest=";

    /** The prefix of the javac options that say whether to run annotation processing; javac takes the last one. */
    private static final String PROCESSING = "-proc:";

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

    /**
     * The javac option that names the release javac compiles at, spelled {@code --release N} or
     * {@code --release=N}; javac takes the last one.
     */
    private static final String RELEASE = "--release";

    /** The lowest Java feature release the generated vocabulary compiles under. */
    private static final int MINIMUM_RELEASE = 21;

    /** The type of toolchain a compilation of Java sources selects. */
    private static final String JDK = "jdk";

    /** The compilation this configuration is read for, which names the goal and the parameters it reads. */
    @Getter(AccessLevel.PACKAGE)
    private final CompilerExecution execution;

    /** The id of the execution of the compiler this configuration is read from, as the project model names it. */
    private final String id;

    /**
     * The ids of the running executions of the compiler goal that compile the vocabulary the generator writes, this
     * one among them unless none does.
     */
    private final List<String> compiling;

    private final Xpp3Dom configuration;
    private final Path basedir;
    private final List<Properties> properties;

    /** Maven's own evaluator of plugin parameters, over the session of this build. */
    private final PluginParameterExpressionEvaluator evaluator;

    private CompilerConfiguration(CompilerExecution execution, String id, List<String> compiling,
            Xpp3Dom configuration, Path basedir, List<Properties> properties, MavenSession session) {
        this.execution = execution;
        this.id = id;
        this.compiling = List.copyOf(compiling);
        this.configuration = configuration;
        this.basedir = basedir;
        this.properties = properties;
        // Maven evaluates the parameters of the compiler against the execution of the compiler, which this plugin
        // cannot see: an execution of no goal cannot complete an expression about the running goal or its plugin,
        // which evaluated(String) then keeps as it is written.
        this.evaluator = new PluginParameterExpressionEvaluator(session, new MojoExecution(new MojoDescriptor()));
    }

    /**
     * Reads the effective configuration of the execution of the compiler that runs {@code execution} for
     * {@code project}: of the running executions of its goal, the one that compiles {@code generatedSources}, else the
     * first of them; when several compile it, the first of those, which {@link #requireOneCompilingExecution} then
     * refuses.
     *
     * @param project the project being built
     * @param session the session, which carries the properties the command line and the JVM set
     * @param execution the compilation the calling goal belongs to
     * @param lifecycles the lifecycles of this build, among them the default lifecycle, a phase of which an execution
     *     must be bound to to run between the preparation and the completion
     * @param generatedSources the directory the generator writes the vocabulary of this compilation into
     * @return the configuration, empty when the default lifecycle runs no execution of the goal, as {@link #idle}
     *     reports
     */
    static Optional<CompilerConfiguration> of(MavenProject project, MavenSession session, CompilerExecution execution,
            DefaultLifecycles lifecycles, Path generatedSources) {
        // The order is the one Maven evaluates the expression of a compiler parameter in: the properties of the
        // session — the command line, then the JVM the build runs in — before the properties of the project.
        List<Properties> properties = List.of(session.getUserProperties(), session.getSystemProperties(),
            project.getProperties());
        Path basedir = project.getBasedir().toPath();
        // Maven has already merged the configuration the plugin declares for all of its executions into the
        // configuration of each execution, and an element the execution declares itself — a whole list included —
        // wins over the plugin's, so the configuration of the execution is all there is to read.
        List<CompilerConfiguration> running = new ArrayList<>();
        for (PluginExecution candidate : CompilerExecutionOrder.running(project, execution, lifecycles)) {
            running.add(new CompilerConfiguration(execution, candidate.getId(), List.of(),
                configuration(candidate.getConfiguration()), basedir, properties, session));
        }
        if (running.isEmpty()) {
            return Optional.empty();
        }
        List<CompilerConfiguration> compilingOnes = running.stream()
            .filter((CompilerConfiguration candidate) -> CompiledSources.compilesDirectory(candidate, generatedSources))
            .toList();
        CompilerConfiguration chosen = compilingOnes.isEmpty() ? running.get(0) : compilingOnes.get(0);
        List<String> ids = compilingOnes.stream().map((CompilerConfiguration candidate) -> candidate.id).toList();
        return Optional.of(new CompilerConfiguration(execution, chosen.id, ids, chosen.configuration, basedir,
            properties, session));
    }

    /**
     * Why the default lifecycle of {@code project} runs no execution of the compiler goal of {@code execution}, as
     * {@link CompilerExecutionOrder#idle} tells it.
     */
    static Optional<String> idle(MavenProject project, CompilerExecution execution, DefaultLifecycles lifecycles) {
        return CompilerExecutionOrder.idle(project, execution, lifecycles);
    }

    /**
     * The id of the execution of the compiler this configuration is read from.
     *
     * @return the execution id, as the project model names it
     */
    String id() {
        return id;
    }

    /**
     * Refuses a compilation whose javac would not run between the preparation and the completion of this compilation,
     * as {@link CompilerExecutionOrder#requireCompilerBetweenPreparationAndCompletion} refuses it.
     */
    void requireCompilerBetweenPreparationAndCompletion(MavenProject project, DefaultLifecycles lifecycles)
            throws MojoExecutionException {
        CompilerExecutionOrder.requireCompilerBetweenPreparationAndCompletion(execution, id, project, lifecycles);
    }

    /**
     * Refuses a compilation whose vocabulary two running executions of the compiler goal compile, naming them. The
     * generator describes one compilation in one request, read from one execution, and a second javac over the same
     * sources and the same class output fails on the classes the first one wrote, after the preparation retired the
     * outputs of the previous compilation.
     *
     * @param request the request the calling goal generates for
     * @throws MojoExecutionException naming the executions and how to keep one of them
     */
    void requireOneCompilingExecution(StateGraphMojo.Request request) throws MojoExecutionException {
        if (compiling.size() < 2) {
            return;
        }
        throw new MojoExecutionException("maven-compiler-plugin runs the executions "
            + String.join(" and ", compiling) + " of its " + execution.goal() + " goal for this project, and each of"
            + " them compiles " + request.sourcesOutput() + ", the vocabulary the StateGraph generator writes for this"
            + " compilation, which StateGraph supports for one javac only: the later one would fail on the classes the"
            + " earlier one wrote, after the preparation retired the outputs of the previous compilation. Keep one of"
            + " them, bind the others to the phase none, or give them <compileSourceRoots> of their own that leave out"
            + " that directory.");
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
            throw new MojoExecutionException("the " + id + " execution of maven-compiler-plugin "
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
     * <p>javac takes the last {@code --release} it is given, and maven-compiler-plugin passes a release that is set
     * as one ahead of the compiler arguments, so the last {@code --release} compiler argument wins over that release,
     * and is what is checked then; its refusal asks for that argument to change. Without a release, the compiler
     * passes {@code -source} and {@code -target}, which javac refuses beside a {@code --release}, so no such argument
     * is read.</p>
     *
     * <p>Without a release, a source or a target left unset is refused as well: {@code source} and {@code target}
     * of {@code maven-compiler-plugin} default to {@code 1.8}, so a compilation that sets neither a release nor
     * both of them compiles the generated vocabulary below Java 21.</p>
     */
    private void requireSupportedRelease() throws MojoExecutionException {
        Optional<Setting> testRelease = testSetting("testRelease", "maven.compiler.testRelease");
        Optional<Setting> release = setting("release", "maven.compiler.release");
        Optional<Setting> argument = lastOption(List.of(RELEASE));
        if ((testRelease.isPresent() || release.isPresent()) && argument.isPresent()) {
            requireSupported(argument.get(), "Give that argument a release of " + MINIMUM_RELEASE
                + " or later, or remove it.");
            return;
        }
        if (testRelease.isPresent()) {
            requireSupported(testRelease.get(), configure("testRelease"));
            return;
        }
        if (release.isPresent()) {
            requireSupported(release.get(), configure("release"));
            return;
        }
        Optional<Setting> source = testSetting("testSource", "maven.compiler.testSource")
            .or(() -> setting("source", "maven.compiler.source"));
        Optional<Setting> target = testSetting("testTarget", "maven.compiler.testTarget")
            .or(() -> setting("target", "maven.compiler.target"));
        if (source.isEmpty() && target.isEmpty()) {
            throw new MojoExecutionException("the " + id + " execution of maven-compiler-plugin"
                + " configures no release, so it compiles at the default source and target of that plugin,"
                + " which is below Java " + MINIMUM_RELEASE + "; the vocabulary the StateGraph generator emits"
                + " needs Java " + MINIMUM_RELEASE + " or later. Configure <release>" + MINIMUM_RELEASE
                + "</release> for that execution.");
        }
        if (source.isPresent()) {
            requireSupported(source.get(), configure("release"));
        }
        if (target.isPresent()) {
            requireSupported(target.get(), configure("release"));
        }
        if (source.isEmpty() || target.isEmpty()) {
            throw new MojoExecutionException("the " + id + " execution of maven-compiler-plugin"
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
     * @param remedy the sentence that says what makes this compilation compile at Java 21
     */
    private void requireSupported(Setting setting, String remedy) throws MojoExecutionException {
        int feature = featureVersion(setting.value());
        if (feature > 0 && feature < MINIMUM_RELEASE) {
            throw new MojoExecutionException("the " + id + " execution of maven-compiler-plugin"
                + " compiles with " + setting + ", but the vocabulary the StateGraph generator emits needs"
                + " Java " + MINIMUM_RELEASE + " or later. " + remedy);
        }
    }

    /** The advice to configure {@code parameter} at Java 21 for this execution. */
    private static String configure(String parameter) {
        return "Configure <" + parameter + '>' + MINIMUM_RELEASE + "</" + parameter + "> for that execution.";
    }

    /**
     * The release javac compiles this compilation at, which the generator parses the original sources at and
     * resolves the JDK types they name against, read as maven-compiler-plugin 3.15.0 reads it: {@code <release>}
     * or {@code maven.compiler.release}, else {@code <target>} or {@code maven.compiler.target}, as a feature
     * release ({@code 1.8} is 8). The test compilation takes {@code <testRelease>} or
     * {@code maven.compiler.testRelease} over the release, and {@code <testTarget>} or
     * {@code maven.compiler.testTarget} over the target, when they are set. When a release is set, the last
     * {@code --release} compiler argument wins over it, since javac takes the last {@code --release} it is given and
     * the compiler passes the release ahead of the compiler arguments.
     *
     * @return the release, empty when neither a release nor a target is set, or when the one read is not a release
     *     this plugin understands
     */
    Optional<Integer> release() {
        Optional<Setting> configured = testSetting("testRelease", "maven.compiler.testRelease")
            .or(() -> setting("release", "maven.compiler.release"));
        Optional<Setting> release = configured.isPresent()
            ? lastOption(List.of(RELEASE)).or(() -> configured)
            : testSetting("testTarget", "maven.compiler.testTarget")
                .or(() -> setting("target", "maven.compiler.target"));
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
     * <p>A javac that compiles inside Maven through the {@code javax.tools} API reads the sources through a file
     * manager the compiler opens in the configured encoding, which then wins over every {@code -encoding} javac is
     * given: the encoding is the configured one, else the value of the last {@code -encoding} compiler argument. There,
     * javac still writes the sources annotation processors generate in the last {@code -encoding} it is given, so
     * {@link #requireOneSourceEncoding} refuses one that names another charset than the configured encoding. A javac
     * the compiler forks, or runs inside Maven through {@code com.sun.tools.javac.Main}, as {@link #legacyJavacApi}
     * makes it, gets the configured encoding as an {@code -encoding} ahead of the compiler arguments, and takes the
     * last {@code -encoding} it is given: the encoding is the value of the last {@code -encoding} compiler argument,
     * else the configured one.</p>
     *
     * @param toolchains the component that resolves the {@code jdk} toolchains of this build, one of which makes the
     *     compiler fork javac
     * @param session the session the toolchains are resolved against
     * @return the name of the encoding, empty when the build names none. javac then reads the default charset of
     *     the JVM it runs in, which is UTF-8 from Java 18 on unless {@code file.encoding} names another, while the
     *     generator reads UTF-8.
     */
    Optional<String> encoding(ToolchainManager toolchains, MavenSession session) {
        Optional<Setting> configured = configuredEncoding();
        Optional<Setting> argument = lastOption(List.of(ENCODING));
        Optional<Setting> read = javac(toolchains, session).forked() || legacyJavacApi()
            ? argument.or(() -> configured)
            : configured.or(() -> argument);
        return read.map(Setting::value);
    }

    /**
     * Refuses, for a javac that compiles inside Maven through the {@code javax.tools} API, a last {@code -encoding}
     * compiler argument that names another charset than the configured encoding, when this JVM supports both. That
     * javac reads every source, those the annotation processors generate included, through a file manager the
     * compiler opens in the configured encoding, but writes the sources an annotation processor generates in the last
     * {@code -encoding} it is given, so it would read them back in another charset than they were written in. That
     * garbles the characters the two charsets encode differently, or fails javac after the preparation has retired the
     * outputs of the previous compilation, as it does for the sources of the StateGraph processor, which are ASCII,
     * when one of the two charsets encodes ASCII otherwise, as UTF-16 does. A javac the compiler forks, or runs
     * through {@code com.sun.tools.javac.Main}, reads and writes in the last {@code -encoding}, and an alias of the
     * configured charset names that very charset, so none of them is refused.
     *
     * @param javac the javac this compilation runs, as {@link #requireAgreementWith} resolves it
     * @throws MojoExecutionException naming the configured encoding, the compiler argument and what reconciles them
     */
    void requireOneSourceEncoding(Javac javac) throws MojoExecutionException {
        Optional<Setting> configured = configuredEncoding();
        Optional<Setting> argument = lastOption(List.of(ENCODING));
        if (javac.forked() || legacyJavacApi() || configured.isEmpty() || argument.isEmpty()
                || !supported(configured.get().value())
                || !supported(argument.get().value())
                || Charset.forName(configured.get().value()).equals(Charset.forName(argument.get().value()))) {
            return;
        }
        throw new MojoExecutionException(configured.get() + " and " + argument.get() + " of the " + id
            + " execution of maven-compiler-plugin name two charsets for a javac that compiles inside Maven through the"
            + " javax.tools API: it reads every source in the configured encoding, those annotation processors generate"
            + " included, while it writes the sources an annotation processor generates in the last -encoding it is"
            + " given, so it would read them back in another charset than they were written in, which garbles the"
            + " characters the two charsets encode differently or fails the compilation after the preparation retired"
            + " the outputs of the previous one. Remove that -encoding from the compiler arguments of that execution,"
            + " or configure <encoding>"
            + argument.get().value() + "</encoding> for it.");
    }

    /**
     * Whether maven-compiler-plugin runs javac inside Maven through {@code com.sun.tools.javac.Main}, as its command
     * line runs it, rather than through the {@code javax.tools} API: when {@code <forceLegacyJavacApi>} or
     * {@code <forceJavacCompilerUse>}, or the property {@code maven.compiler.forceLegacyJavacApi} or
     * {@code maven.compiler.forceJavacCompilerUse} it is bound to, is {@code true}. maven-compiler-plugin 3.15.0 hands
     * plexus-compiler-javac 2.16.2 the two as one switch, which a forked javac ignores. That javac still runs in the
     * JVM running Maven, so it resolves relative paths as any javac inside Maven does.
     */
    private boolean legacyJavacApi() {
        return Boolean.parseBoolean(setting("forceLegacyJavacApi", "maven.compiler.forceLegacyJavacApi")
            .map(Setting::value).orElse("false"))
            || Boolean.parseBoolean(setting("forceJavacCompilerUse", "maven.compiler.forceJavacCompilerUse")
                .map(Setting::value).orElse("false"));
    }

    /**
     * The encoding this compilation is configured with: {@code <encoding>} or the {@code encoding} property, else
     * {@code project.build.sourceEncoding}, the default maven-compiler-plugin gives that parameter.
     */
    private Optional<Setting> configuredEncoding() {
        return setting("encoding", "encoding").or(() -> property("project.build.sourceEncoding"));
    }

    /** Whether this JVM supports a charset of the name {@code name}. */
    private static boolean supported(String name) {
        try {
            return Charset.isSupported(name);
        } catch (IllegalArgumentException illegal) {
            return false;
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
     * Refuses a compilation that would not run the StateGraph annotation processor, as
     * {@link ProcessorPathRequirements#requireAnnotationProcessor} refuses it.
     */
    void requireAnnotationProcessor(MavenProject project, Javac javac) throws MojoExecutionException {
        ProcessorPathRequirements.requireAnnotationProcessor(this, project, javac);
    }

    /**
     * The value javac takes for an option from the compiler arguments, that of its last occurrence under any of
     * {@code names}: the argument that follows it, or, for a name spelled with two dashes, what follows an {@code =}
     * in the same argument. An occurrence that no argument follows gives an empty value.
     *
     * @return how the build spells that last occurrence, and its value; empty when no compiler argument gives the
     *     option
     */
    Optional<Setting> lastOption(List<String> names) {
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
                + id + " execution of maven-compiler-plugin rejects, which StateGraph does not"
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
            throw new MojoExecutionException(incremental.get() + " makes the " + id + " execution of"
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
            + " javac write the class files of the " + id + " execution of maven-compiler-plugin without"
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
            ? setting("outputDirectory", "maven.compiler.outputDirectory", File.class)
            : configured("outputDirectory", File.class);
        String writes = " execution of maven-compiler-plugin write its classes ";
        String consequence = ", while StateGraph completes this compilation in " + expected + ", the class output of"
            + " the project: the completion would find no class of this compilation to publish there, after the"
            + " preparation retired the outputs of the previous compilation.";
        if (directory.isPresent() && !canonical(absolute(directory.get().value())).equals(canonical(expected))) {
            throw new MojoExecutionException(directory.get() + " makes the " + id + writes + "to "
                + absolute(directory.get().value()) + consequence + " Leave outputDirectory unset for that execution,"
                + " or move the class output of the project itself with <" + (main ? "outputDirectory"
                : "testOutputDirectory") + "> in the <build> of the POM.");
        }
        Optional<Setting> versioned = main ? configured("multiReleaseOutput") : Optional.empty();
        if (versioned.isPresent() && Boolean.parseBoolean(versioned.get().value())) {
            Path versions = expected.resolve("META-INF").resolve("versions");
            Optional<Setting> release = setting("release", "maven.compiler.release");
            throw new MojoExecutionException(versioned.get() + " makes the " + id + writes
                + (release.isPresent() ? "to " + versions.resolve(release.get().value()) : "below " + versions)
                + consequence + " Remove multiReleaseOutput from that execution.");
        }
        Optional<Setting> destination = lastOption(List.of("-d"));
        if (destination.isPresent()) {
            Path written = whereJavacRuns(destination.get().value(), javac);
            if (!canonical(written).equals(canonical(expected))) {
                throw new MojoExecutionException(destination.get() + " of the " + id + " execution of"
                    + " maven-compiler-plugin makes javac write its classes to " + written + consequence
                    + " Remove that argument from the configuration of that execution.");
            }
        }
    }

    private MojoExecutionException compilesNothing(String setting, String remedy) {
        return new MojoExecutionException(setting + " makes the " + id + " execution of"
            + " maven-compiler-plugin run the annotation processors and compile nothing, which StateGraph does"
            + " not support: the preparation would retire the outputs of the previous compilation, and javac"
            + " would write no classes in their place. " + remedy);
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
            throw new MojoExecutionException("the " + id + " execution of maven-compiler-plugin"
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
                throw new MojoExecutionException("the " + id + " execution of"
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
            throw new MojoExecutionException("the " + id + " execution of maven-compiler-plugin"
                + " writes the sources of the annotation processor to " + configured.get()
                + ", while StateGraph owns and retires " + expected + ". Configure <" + parameter + '>'
                + expected + "</" + parameter + "> for that execution, or leave it unset.");
        }
        Optional<Setting> sources = lastOption(List.of("-s"));
        if (sources.isPresent()) {
            Path written = whereJavacRuns(sources.get().value(), javac);
            if (!canonical(written).equals(canonical(expected))) {
                throw new MojoExecutionException(sources.get() + " of the " + id + " execution of"
                    + " maven-compiler-plugin makes the annotation processor write its sources to " + written
                    + ", while StateGraph owns and retires " + expected + ". Remove that argument from the"
                    + " configuration of that execution.");
            }
        }
    }

    /**
     * The patterns javac selects the sources of every root of this compilation with, as
     * {@link CompiledSources#sourcePatterns} reads them.
     */
    SourcePatterns sourcePatterns() {
        return CompiledSources.sourcePatterns(this);
    }

    /**
     * The source roots javac compiles when this execution declares {@code <compileSourceRoots>} of its own, as
     * {@link CompiledSources#compileSourceRoots} reads them.
     */
    Optional<List<String>> compileSourceRoots() {
        return CompiledSources.compileSourceRoots(this);
    }

    /**
     * Refuses {@code <compileSourceRoots>} that leave out the directory the generator writes into, as
     * {@link CompiledSources#requireGeneratedSourcesCompiled} refuses them.
     */
    void requireGeneratedSourcesCompiled(StateGraphMojo.Request request) throws MojoExecutionException {
        CompiledSources.requireGeneratedSourcesCompiled(this, request);
    }

    /**
     * Refuses includes or excludes that leave out the carrier of this compilation, as
     * {@link CompiledSources#requireCarrierSelected} refuses them.
     */
    void requireCarrierSelected(StateGraphMojo.Request request) throws MojoExecutionException {
        CompiledSources.requireCarrierSelected(this, request);
    }

    /**
     * The values Maven gives one array or collection parameter of the effective configuration of this execution,
     * as the sisu-plexus configurator of Maven 3.9.12 converts it: when the element has text, that text evaluated as
     * {@link #evaluated} evaluates it, then split on every comma, empty parts included; else the text of each child
     * element, in order. Each value is then evaluated as {@link #evaluated} evaluates it, as that configurator
     * evaluates every value of the list again, and one that is empty, or that evaluates to nothing, becomes
     * {@code null}. So an expression the effective model leaves for that configurator, such as one of
     * {@code ${session}}, is completed before the values are read.
     *
     * @return the values, empty when the configuration has no such element, or when its text evaluates to nothing,
     *     being one expression naming an object Maven does not take for a {@code String}, such as
     *     {@code ${project.compileSourceRoots}}
     */
    Optional<List<String>> parameterValues(String parameter) {
        Optional<Xpp3Dom> declared = element(parameter);
        if (declared.isEmpty()) {
            return Optional.empty();
        }
        List<String> values = new ArrayList<>();
        String text = declared.get().getValue();
        if (text != null && !text.isEmpty()) {
            Optional<String> evaluatedText = evaluated(text);
            if (evaluatedText.isEmpty()) {
                return Optional.empty();
            }
            // The configurator splits an empty text into no value at all.
            if (!evaluatedText.get().isEmpty()) {
                for (String part : evaluatedText.get().split(",", -1)) {
                    values.add(listValue(part));
                }
            }
        } else {
            for (Xpp3Dom child : declared.get().getChildren()) {
                values.add(listValue(child.getValue()));
            }
        }
        return Optional.of(Collections.unmodifiableList(values));
    }

    /** One value of a list parameter, evaluated: {@code null} when it is empty or evaluates to nothing. */
    private String listValue(String value) {
        return value == null || value.isEmpty() ? null : evaluated(value).orElse(null);
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
     * The value of one {@code String} or {@code boolean} compiler parameter, and where the build set it: the effective
     * configuration of this execution, then the property the parameter is bound to, as the next method reads them.
     */
    private Optional<Setting> setting(String parameter, String property) {
        return setting(parameter, property, String.class);
    }

    /**
     * The value of one compiler parameter of {@code type}, and where the build set it: the effective configuration of
     * this execution, then the property the parameter is bound to. Either value is evaluated as Maven evaluates it for
     * that parameter, as {@link #evaluated(String, Class)} describes; one that evaluates to nothing counts as unset.
     */
    private Optional<Setting> setting(String parameter, String property, Class<?> type) {
        return configured(parameter, type).or(() -> property(property, type));
    }

    /** The value of one {@code String} or {@code boolean} compiler parameter bound to no property. */
    private Optional<Setting> configured(String parameter) {
        return configured(parameter, String.class);
    }

    /**
     * The value of one compiler parameter of {@code type} bound to no property, evaluated as Maven evaluates it for
     * that parameter, as {@link #evaluated(String, Class)} describes, and how the effective configuration spells it;
     * empty when it is unset or evaluates to nothing.
     */
    private Optional<Setting> configured(String parameter, Class<?> type) {
        return value(parameter).flatMap((String written) -> evaluated(written, type)
            .map((String configured) -> new Setting('<' + parameter + '>' + written + "</" + parameter + '>',
                configured)));
    }

    /** The value of one property a {@code String} parameter of the compiler is bound to, read as below. */
    private Optional<Setting> property(String property) {
        return property(property, String.class);
    }

    /**
     * The value of one property a compiler parameter of {@code type} is bound to, and where the build set it, from
     * the command line, from the properties of the JVM and from the properties of the project, in that order. Maven
     * evaluates a value it reads there for a parameter when the value holds an expression {@code ${...}}, such as
     * {@code ${session.executionRootDirectory}}, and only then: such a value is evaluated as
     * {@link #evaluated(String, Class)} describes, and one that evaluates to nothing counts as unset.
     */
    private Optional<Setting> property(String property, Class<?> type) {
        for (Properties candidates : properties) {
            String value = candidates.getProperty(property);
            if (value != null && !value.isBlank()) {
                String written = value.strip();
                Optional<String> resolved = written.contains("${") ? evaluated(written, type) : Optional.of(written);
                return resolved.map((String evaluated) -> new Setting(property + '=' + written, evaluated));
            }
        }
        return Optional.empty();
    }

    /**
     * The text of {@code name} in the effective configuration of this execution, as the effective model writes it,
     * before Maven evaluates it.
     */
    private Optional<String> value(String name) {
        return element(name).map(Xpp3Dom::getValue).filter(value -> !value.isBlank()).map(String::strip);
    }

    /** The element {@code name} of the effective configuration of this execution. */
    Optional<Xpp3Dom> element(String name) {
        return configuration == null ? Optional.empty() : Optional.ofNullable(configuration.getChild(name));
    }

    /**
     * The text of one child element, evaluated as {@link #evaluated} evaluates it, or {@code null} when the element
     * or its text is absent or evaluates to nothing.
     */
    String childValue(Xpp3Dom element, String name) {
        Xpp3Dom child = element.getChild(name);
        return child == null || child.getValue() == null ? null : evaluated(child.getValue().strip()).orElse(null);
    }

    /**
     * The compiler arguments of the effective configuration of this execution, as maven-compiler-plugin 3.15.0
     * adds them to the arguments of the compiler: the entries of the deprecated {@code <compilerArguments>} map, by
     * key, each key once with its last value, since Maven converts that element into a sorted map; then
     * {@code <compilerArgument>}; then the values of {@code <compilerArgs>}, as {@link #parameterValues} reads and
     * evaluates them, an empty one left out. The test compilation takes {@code <testCompilerArguments>} and
     * {@code <testCompilerArgument>} over the first two when they are set. Each entry of the map gives its key,
     * prefixed with a dash unless it starts with one, then its value unless that is empty; a key that then starts
     * with {@code -A} and has a value gives the single argument {@code key=value} instead. Each value is evaluated
     * first, as Maven evaluates the parameters it hands the compiler, which {@link #evaluated} describes: a value of
     * the map that evaluates to nothing leaves its key alone, and {@code <compilerArgument>} or a value of
     * {@code <compilerArgs>} that evaluates to nothing or to an empty text gives no argument, since
     * plexus-compiler-javac passes no empty argument to javac.
     */
    List<String> compilerArguments() {
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
            if (argument != null && !argument.isEmpty()) {
                arguments.add(argument);
            }
        }
        return List.copyOf(arguments);
    }

    /** {@code value} as Maven configures a {@code String} parameter of the compiler with it, as described below. */
    private Optional<String> evaluated(String value) {
        return evaluated(value, String.class);
    }

    /**
     * {@code value} as Maven configures a parameter of the compiler of {@code type} with it, as far as that can be
     * told when {@code generate} runs: evaluated for that type by Maven's own evaluator of plugin parameters. A
     * {@code boolean} parameter is evaluated as a {@code String}, which gives the same text, since that evaluator
     * takes a {@code Boolean} for either type. That completes what the effective model leaves for it, such as
     * {@code ${settings.localRepository}} or {@code ${session.executionRootDirectory}}. It keeps {@code $${...}} as
     * the text {@code ${...}}, and turns {@code $$} into {@code $} only after the last {@code ${...}} of the value, or
     * in the whole value when it holds none. An expression it cannot complete within a longer value stays as it is
     * written. A value that is one expression naming an object Maven does not take for that type, such as
     * {@code ${project.file}} for a {@code String}, evaluates to nothing, as it does whenever Maven evaluates it; one
     * naming a file, for a {@code File}, evaluates to its path. A value that is one expression the evaluator cannot
     * complete stays as it is written instead: Maven evaluates the parameters of the compiler only when the compiler
     * runs, after a plugin bound to a later phase may have set a property, and against the execution of the compiler,
     * which this plugin cannot see, so an expression about the running goal or its plugin, such as
     * {@code ${plugin.version}}, completes there and not here. So does a value the evaluator fails on.
     *
     * @return the evaluated value, empty when Maven evaluates it to nothing whenever it evaluates it
     */
    private Optional<String> evaluated(String value, Class<?> type) {
        try {
            Object typed = evaluator.evaluate(value, type);
            if (typed instanceof File file) {
                return Optional.of(file.getPath());
            }
            if (typed != null) {
                return Optional.of(String.valueOf(typed));
            }
            // Typed, the evaluator gives nothing both for an expression it cannot complete and for one naming an
            // object it does not take for that type; untyped, it gives that object.
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
    Optional<Setting> processing() {
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
     * hands the toolchain manager, as it hands it any other. Each value is evaluated as {@link #evaluated} evaluates
     * it; one that is absent or evaluates to nothing becomes an empty text.
     */
    private Optional<Map<String, String>> jdkToolchain() {
        Optional<Xpp3Dom> toolchain = element("jdkToolchain");
        if (toolchain.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> requirements = new LinkedHashMap<>();
        for (Xpp3Dom requirement : toolchain.get().getChildren()) {
            requirements.put(requirement.getName(),
                requirement.getValue() == null ? "" : evaluated(requirement.getValue().strip()).orElse(""));
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
    Path whereJavacRuns(String path, Javac javac) {
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
    record Setting(String description, String value) {

        @Override
        public String toString() {
            return description;
        }
    }
}
