package io.github.graphmaquina.gradle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Test-only helper that materializes minimal Gradle projects applying the StateGraph plugin and runs them through
 * TestKit. This is test-support code, not a production API.
 *
 * <p>The project directory of every fixture but {@link #applicationOnly} carries a space and a non-ASCII character
 * in its name, so the functional tests that run a generation also exercise the request encoding and the process
 * invocation over a path that is not plain ASCII.</p>
 */
public final class PluginFixture {

    /** The version every StateGraph artifact of the fixture repository is published under. */
    private static final String VERSION = requireProperty("graphmaquina.version");

    /** The Lombok version the entity's accessors are generated with; declared before the StateGraph processor. */
    private static final String LOMBOK = "1.18.48";

    /** The JUnit platform the fixture with plain tests runs its own test source set on. */
    private static final String JUNIT = "5.13.4";

    /** Marker the fixture's classpath reporting task prefixes every runtime entry with. */
    private static final String RUNTIME_ENTRY = "RUNTIME_CLASSPATH_ENTRY=";

    /** Marker the fixture's source-root reporting task prefixes every source directory with. */
    private static final String SOURCE_ROOT = "MAIN_SOURCE_ROOT=";

    /** Marker the fixture's compiler-argument reporting task prefixes every argument with. */
    private static final String TEST_COMPILER_ARG = "TEST_COMPILER_ARG=";

    /** The published index a compilation leaves beside its classes. */
    private static final String EXPORTS = "META-INF/graphmaquina/exports.json";

    /** The manifest and index schema this release of the tool reads. */
    private static final int SCHEMA_VERSION = 2;

    private final Path directory;

    /** Whether TestKit injects the plugin under development, instead of the build resolving a published one. */
    private final boolean injectedPluginClasspath;

    private PluginFixture(Path directory, boolean injectedPluginClasspath) {
        this.directory = directory;
        this.injectedPluginClasspath = injectedPluginClasspath;
    }

    /**
     * Writes a settings file and a build script that applies only the {@code java} and {@code io.github.graphmaquina}
     * plugins, with no further configuration.
     *
     * @param directory the project directory to materialize the build into
     * @return a fixture bound to the written project
     * @throws IOException if the build files cannot be written
     */
    public static PluginFixture applicationOnly(Path directory) throws IOException {
        Files.writeString(directory.resolve("settings.gradle.kts"), """
                rootProject.name = "graphmaquina-plugin-fixture"
                """);
        Files.writeString(directory.resolve("build.gradle.kts"), """
                plugins {
                    java
                    id("io.github.graphmaquina")
                }
                """);
        return new PluginFixture(directory, true);
    }

    /**
     * Writes the shared adapter domain — {@code fx.Status}, {@code fx.Transition}, {@code fx.Order},
     * {@code fx.Actions}, {@code fx.Orders} and the unmarked {@code fx.Unrelated} — into a build that resolves
     * StateGraph from {@code repository}, compiles with Lombok before the StateGraph processor, declares that
     * processor for both the main and the test source set and uses the project's own strict compiler options.
     *
     * @param directory the temporary directory to materialize the project under
     * @param repository the file Maven repository the StateGraph artifacts were published to
     * @return a fixture bound to the written project
     * @throws IOException if the project cannot be written
     * @throws IllegalStateException if {@code repository} is not an existing directory
     */
    public static PluginFixture basic(Path directory, Path repository) throws IOException {
        return materialize(directory, repository, "", true,
                "    testAnnotationProcessor(\"io.github.graphmaquina:graphmaquina-processor:" + VERSION + "\")\n", "");
    }

    /**
     * The same domain, in a build that declares the plugin with a version and resolves it — plugin marker and
     * plugin jar — from the fixture repository, with nothing injected by TestKit. It is the shape of a consumer
     * build outside this repository. Only its main source set declares the StateGraph processor.
     *
     * @param directory the temporary directory to materialize the project under
     * @param repository the file Maven repository the StateGraph artifacts and the plugin were published to
     * @return a fixture bound to the written project
     * @throws IOException if the project cannot be written
     */
    public static PluginFixture publishedPlugin(Path directory, Path repository) throws IOException {
        return materialize(directory, repository, " version \"" + VERSION + "\"", false, "", "");
    }

    /**
     * The same project, with a test source set that declares no StateGraph processor at all: plain JUnit tests
     * beside a main source set that does declare one. It is the shape of every project whose tests only assert
     * on the machines the main sources declare.
     *
     * @param directory the temporary directory to materialize the project under
     * @param repository the file Maven repository the StateGraph artifacts were published to
     * @return a fixture bound to the written project
     * @throws IOException if the project cannot be written
     */
    public static PluginFixture plainJUnitTests(Path directory, Path repository) throws IOException {
        PluginFixture fixture = materialize(directory, repository, "", true, """
                        testImplementation(platform("org.junit:junit-bom:%1$s"))
                        testImplementation("org.junit.jupiter:junit-jupiter")
                        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
                """.formatted(JUNIT), """
                tasks.named<Test>("test") {
                    useJUnitPlatform()
                }

                tasks.register("printTestCompilerArgs") {
                    val arguments = tasks.named<JavaCompile>("compileTestJava").map { it.options.allCompilerArgs }
                    doLast {
                        arguments.get().forEach { println("%1$s" + it) }
                    }
                }
                """.formatted(TEST_COMPILER_ARG));
        fixture.writeTestSource("fx.OrderTest", """
                package fx;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                /** A plain JUnit test: nothing it declares belongs to a graph. */
                class OrderTest {

                    @Test
                    void anOrderStartsNew() {
                        assertEquals(Status.NEW, new Order().getStatus());
                    }
                }
                """);
        return fixture;
    }

    /**
     * The same project as {@link #basic}, with a block that realizes both {@code JavaCompile} tasks — and so
     * runs every configuration action they carry — before the {@code dependencies} block declares the
     * processor either source set takes part with. It is the ordering a build script falls into as soon as it
     * configures a compilation near the top of the file.
     *
     * @param directory the temporary directory to materialize the project under
     * @param repository the file Maven repository the StateGraph artifacts were published to
     * @return a fixture bound to the written project
     * @throws IOException if the project cannot be written
     */
    public static PluginFixture realizingCompilationsEarly(Path directory, Path repository) throws IOException {
        return materialize(directory, repository, "", true,
                "    testAnnotationProcessor(\"io.github.graphmaquina:graphmaquina-processor:" + VERSION + "\")\n", "", """
                // Realized before anything is declared for them, which is what the wiring must survive.
                tasks.named<JavaCompile>("compileJava").get()
                tasks.named<JavaCompile>("compileTestJava").get()
                """);
    }

    /**
     * Moves the class output of the main compilation, by appending what a build script would have carried from
     * the start.
     *
     * @param relativePath the new destination directory, relative to the build directory
     * @throws IOException if the build script cannot be rewritten
     */
    public void relocateClassOutput(String relativePath) throws IOException {
        Path script = directory.resolve("build.gradle.kts");
        Files.writeString(script, Files.readString(script, StandardCharsets.UTF_8) + """

                tasks.named<JavaCompile>("compileJava") {
                    destinationDirectory = layout.buildDirectory.dir("%s")
                }
                """.formatted(relativePath), StandardCharsets.UTF_8);
    }

    /**
     * Appends {@code snippet} to the build script, which is what a build script that carried it from the start
     * declares last.
     *
     * @param snippet the Kotlin DSL to append
     * @throws IOException if the build script cannot be rewritten
     */
    public void appendToBuildScript(String snippet) throws IOException {
        Path script = directory.resolve("build.gradle.kts");
        Files.writeString(script, Files.readString(script, StandardCharsets.UTF_8) + "\n" + snippet,
                StandardCharsets.UTF_8);
    }

    /**
     * Replaces the one occurrence of {@code target} in the build script with {@code replacement}.
     *
     * @param target the text to replace, which the build script must carry exactly once
     * @param replacement the text to put in its place
     * @throws IOException if the build script cannot be rewritten
     * @throws IllegalStateException if the build script does not carry {@code target} exactly once
     */
    public void replaceInBuildScript(String target, String replacement) throws IOException {
        Path script = directory.resolve("build.gradle.kts");
        String content = Files.readString(script, StandardCharsets.UTF_8);
        int index = content.indexOf(target);
        if (index < 0 || content.indexOf(target, index + 1) >= 0) {
            throw new IllegalStateException("the build script does not carry exactly one " + target);
        }
        Files.writeString(script, content.substring(0, index) + replacement
                + content.substring(index + target.length()), StandardCharsets.UTF_8);
    }

    /**
     * Writes {@code content} to {@code relativePath} under the project directory in UTF-8, creating its directory.
     *
     * @param relativePath the path of the file, relative to the project directory
     * @param content the text of the file
     * @throws IOException if the file cannot be written
     */
    public void writeFile(String relativePath, String content) throws IOException {
        writeFile(relativePath, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes {@code content} as it is to {@code relativePath} under the project directory, creating its directory,
     * so a test can leave a file that is not text at all.
     *
     * @param relativePath the path of the file, relative to the project directory
     * @param content the bytes of the file
     * @throws IOException if the file cannot be written
     */
    public void writeFile(String relativePath, byte[] content) throws IOException {
        Path file = directory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.write(file, content);
    }

    /**
     * Replaces the generator with a stand-in that only prints {@code line} on its error stream and exits with
     * status {@code 0}, so a test can watch what the build does with a generator that completes while it
     * reports something. The stand-in is compiled here and declared in the {@code graphmaquinaGenerator}
     * configuration, which keeps the real generator out of it.
     *
     * @param line the line the stand-in prints, without quotes or backslashes
     * @throws IOException if the stand-in cannot be written or packaged
     * @throws IllegalStateException if the stand-in does not compile
     */
    public void replaceGeneratorWithOneThatReports(String line) throws IOException {
        Path sources = Files.createDirectories(directory.resolve("stand-in-generator/src"));
        Path classes = Files.createDirectories(directory.resolve("stand-in-generator/classes"));
        Path main = sources.resolve("CodegenMain.java");
        Files.writeString(main, """
                package io.github.graphmaquina.codegen;

                /** A stand-in generator that reports on its error stream and completes. */
                public final class CodegenMain {

                    private CodegenMain() {
                    }

                    public static void main(String[] arguments) {
                        System.err.println("%s");
                    }
                }
                """.formatted(line), StandardCharsets.UTF_8);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int status = compiler.run(null, null, null, "--release", "21", "-d", classes.toString(), main.toString());
        if (status != 0) {
            throw new IllegalStateException("the stand-in generator does not compile: status " + status);
        }
        String entry = "io/github/graphmaquina/codegen/CodegenMain.class";
        try (ZipOutputStream out = new ZipOutputStream(
                Files.newOutputStream(directory.resolve("stand-in-codegen.jar")), StandardCharsets.UTF_8)) {
            out.putNextEntry(new ZipEntry(entry));
            out.write(Files.readAllBytes(classes.resolve(entry)));
            out.closeEntry();
        }
        appendToBuildScript("""
                dependencies {
                    "graphmaquinaGenerator"(files("stand-in-codegen.jar"))
                }
                """);
    }

    /** Writes the shared project with {@code testProcessors} and {@code extraConfiguration} spliced into it. */
    private static PluginFixture materialize(Path directory, Path repository, String pluginVersion,
            boolean injectedPluginClasspath, String testProcessors, String extraConfiguration)
            throws IOException {
        return materialize(directory, repository, pluginVersion, injectedPluginClasspath, testProcessors,
                extraConfiguration, "");
    }

    /** The same, with {@code earlyConfiguration} spliced in before the {@code dependencies} block. */
    private static PluginFixture materialize(Path directory, Path repository, String pluginVersion,
            boolean injectedPluginClasspath, String testProcessors, String extraConfiguration,
            String earlyConfiguration) throws IOException {
        if (!Files.isDirectory(repository)) {
            throw new IllegalStateException("the fixture repository " + repository + " does not exist; run"
                    + " ./gradlew :graphmaquina-core:publishMavenPublicationToAdapterTestsRepository"
                    + " :graphmaquina-codegen:publishMavenPublicationToAdapterTestsRepository"
                    + " :graphmaquina-processor:publishMavenPublicationToAdapterTestsRepository first");
        }
        Path project = Files.createDirectories(directory.resolve("fixture project ñ"));
        Files.writeString(project.resolve("settings.gradle.kts"), settings(repository));
        Files.writeString(project.resolve("build.gradle.kts"),
                buildScript(pluginVersion, testProcessors, extraConfiguration, earlyConfiguration));
        PluginFixture fixture = new PluginFixture(project, injectedPluginClasspath);
        fixture.writeSources(domainSources());
        return fixture;
    }

    /**
     * The settings file: the plugin repositories and the dependency repositories both start with the fixture
     * repository, followed by Maven Central, and the plugin repositories end with the Gradle plugin portal.
     */
    private static String settings(Path repository) {
        String url = repository.toUri().toString();
        return """
                pluginManagement {
                    repositories {
                        maven { url = uri("%s") }
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }

                dependencyResolutionManagement {
                    repositories {
                        maven { url = uri("%s") }
                        mavenCentral()
                    }
                }

                rootProject.name = "fixture"
                """.formatted(url, url);
    }

    /**
     * The build script: the plugin, a Java 21 toolchain, the strict compiler options, Lombok before the
     * StateGraph processor, and the three reporting tasks the assertions read: {@code printRuntimeClasspath},
     * {@code printSourceRoots} and {@code printGeneratorLauncher}.
     */
    private static String buildScript(String pluginVersion, String testProcessors,
            String extraConfiguration, String earlyConfiguration) {
        return """
                import io.github.graphmaquina.gradle.GenerateGraphApi

                plugins {
                    java
                    id("io.github.graphmaquina")%7$s
                }

                group = "fx.build"
                version = "1.0.0"

                java {
                    toolchain { languageVersion = JavaLanguageVersion.of(21) }
                }

                configurations.all {
                    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
                }

                %8$s
                dependencies {
                    implementation("io.github.graphmaquina:graphmaquina-core:%1$s")
                    implementation(fileTree("lib") { include("*.jar") })
                    compileOnly("org.projectlombok:lombok:%2$s")
                    // Lombok first: the processor reads the accessors Lombok generates on Order.
                    annotationProcessor("org.projectlombok:lombok:%2$s")
                    annotationProcessor("io.github.graphmaquina:graphmaquina-processor:%1$s")
                %5$s}

                tasks.withType<JavaCompile>().configureEach {
                    options.encoding = "UTF-8"
                    options.release = 21
                    options.compilerArgs.addAll(listOf("-Xlint:all,-serial", "-Werror"))
                }

                tasks.register("printRuntimeClasspath") {
                    val entries = configurations.named("runtimeClasspath")
                    doLast {
                        entries.get().files.forEach { println("%3$s" + it.name) }
                    }
                }

                tasks.register("printSourceRoots") {
                    val roots = provider {
                        sourceSets.getByName("main").java.srcDirs.map { it.absolutePath }.sorted()
                    }
                    doLast {
                        roots.get().forEach { println("%4$s" + it) }
                    }
                }

                tasks.register("printGeneratorLauncher") {
                    val generator = tasks.named<GenerateGraphApi>("generateStateGraphApi")
                            .flatMap { it.javaLauncher }
                    val toolchain = javaToolchains.launcherFor(java.toolchain)
                    val compiler = tasks.named<JavaCompile>("compileJava").flatMap { it.javaCompiler }
                    doLast {
                        println("GENERATOR_LAUNCHER=" + generator.get().executablePath.asFile.absolutePath)
                        println("GENERATOR_LANGUAGE_VERSION="
                                + generator.get().metadata.languageVersion.asInt())
                        println("TOOLCHAIN_LAUNCHER=" + toolchain.get().executablePath.asFile.absolutePath)
                        println("COMPILER_HOME=" + compiler.get().metadata.installationPath.asFile.absolutePath)
                        println("DAEMON_JAVA_HOME=" + System.getProperty("java.home"))
                        println("DAEMON_LANGUAGE_VERSION=" + Runtime.version().feature())
                    }
                }

                %6$s
                """.formatted(VERSION, LOMBOK, RUNTIME_ENTRY, SOURCE_ROOT, testProcessors, extraConfiguration,
                pluginVersion, earlyConfiguration);
    }

    /**
     * The shared adapter domain, one entry per compilation unit, keyed by qualified name, so a test can vary
     * one declaration and rewrite the whole source tree with it.
     *
     * @return a mutable copy of the domain
     */
    public static Map<String, String> domainSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.Status", """
                package fx;

                /** The states an order moves through. */
                public enum Status { NEW, DONE }
                """);
        sources.put("fx.Transition", """
                package fx;

                /** The identified transitions of the order graph. */
                public enum Transition { APPROVE }
                """);
        sources.put("fx.Order", """
                package fx;

                import io.github.graphmaquina.annotation.GraphProperty;
                import lombok.Getter;
                import lombok.Setter;

                /** The entity of the graph; Lombok writes the accessors the processor resolves. */
                @Getter
                @Setter
                public final class Order {

                    @GraphProperty
                    private String id = "order";

                    @GraphProperty
                    private Status status = Status.NEW;
                }
                """);
        sources.put("fx.Actions", """
                package fx;

                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;

                /** The action host of the graph. */
                public final class Actions {

                    @GraphAction
                    public ActionOutcome approve(TransitionContext<Order, Status, Transition> context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        sources.put("fx.Orders", orders("Orders", "Approve", "approve"));
        sources.put("fx.Unrelated", """
                package fx;

                /** A type no marker mentions, kept to prove that cleanup is selective. */
                public final class Unrelated {

                    private Unrelated() {
                    }
                }
                """);
        return sources;
    }

    /**
     * One facade declaration, so a test can rename the machine or the action it binds and rebuild.
     *
     * @param facade the simple name of the machine interface
     * @param symbol the simple name of the generated action symbol the operation binds
     * @param operation the name of the facade's operation
     * @return the source of the facade
     */
    public static String orders(String facade, String symbol, String operation) {
        return """
                package fx;

                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;

                import static fx.Status.DONE;
                import static fx.Status.NEW;
                import static fx.Transition.APPROVE;

                /** The machine of the fixture. */
                @GenerateGraphApi
                @%1$sGraph.Definition(initial = NEW, terminals = DONE,
                    state = OrderRefs.Status.class, id = OrderRefs.Id.class)
                @%1$sGraph.StatePolicy(state = NEW, otherwise = "a new order is approved")
                public interface %1$s extends Machine<Order, Status, Transition> {

                    @%1$sGraph.Transition(id = APPROVE, from = NEW, to = DONE, action = ActionsRefs.%2$s.class)
                    Outcome<Order, Status, Transition> %3$s(Order order);
                }
                """.formatted(facade, symbol, operation);
    }

    /**
     * The publication domain of typed recovery, in a package of its own: {@code fxr.Tickets} declares PUBLISH with
     * two {@code recoveries}, a {@code failsOn} type and a fallback, {@code fxr.TicketActions} makes each ticket meet
     * the failure its id names, and {@code fxr.Publications} publishes one ticket per route and prints one line
     * {@code ROUTED <id> <recovery> <state> kind=<kind> cause=<cause>} for each.
     *
     * @return a mutable copy of the domain, one entry per compilation unit, keyed by qualified name
     */
    public static Map<String, String> recoverySources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fxr.TicketState", """
                package fxr;

                /** The states a ticket moves through. */
                public enum TicketState {
                    READY, PUBLISHED, RETRY_PENDING, REJECTED_BY_PROVIDER, INTEGRATION_FAILED, CLOSED
                }
                """);
        sources.put("fxr.TicketChange", """
                package fxr;

                /** The identified transitions of the publication graph. */
                public enum TicketChange {
                    PUBLISH, SCHEDULE_RETRY, RECORD_REJECTION, MARK_INTEGRATION_FAILED, RETRY, CLOSE
                }
                """);
        sources.put("fxr.PublicationFailures", """
                package fxr;

                import io.github.graphmaquina.api.FailureKind;

                /** The failure kinds the publication adapter reports. */
                public final class PublicationFailures {

                    private PublicationFailures() {
                    }

                    /** The provider could not take the publication now. */
                    public record Unavailable() implements FailureKind {
                    }

                    /** The provider refused the content. */
                    public record Rejected() implements FailureKind {
                    }
                }
                """);
        sources.put("fxr.ServiceUnavailableException", """
                package fxr;

                /** Thrown when the provider is temporarily unavailable. */
                public final class ServiceUnavailableException extends RuntimeException {

                    public ServiceUnavailableException(String message) {
                        super(message);
                    }
                }
                """);
        sources.put("fxr.ProviderRejectedException", """
                package fxr;

                /** Thrown when the provider rejects the request. */
                public final class ProviderRejectedException extends RuntimeException {

                    public ProviderRejectedException(String message) {
                        super(message);
                    }
                }
                """);
        sources.put("fxr.InvalidResponseException", """
                package fxr;

                /** Thrown when the provider's response cannot be read. */
                public final class InvalidResponseException extends RuntimeException {

                    public InvalidResponseException(String message) {
                        super(message);
                    }
                }
                """);
        sources.put("fxr.Ticket", """
                package fxr;

                import io.github.graphmaquina.annotation.GraphProperty;

                /** The entity of the publication graph; its id names the failure its publication meets. */
                public final class Ticket {

                    private final String id;

                    @GraphProperty
                    private TicketState status = TicketState.READY;

                    public Ticket(String id) {
                        this.id = id;
                    }

                    @GraphProperty
                    public String getId() {
                        return id;
                    }

                    public TicketState getStatus() {
                        return status;
                    }

                    public void setStatus(TicketState value) {
                        status = value;
                    }
                }
                """);
        sources.put("fxr.TicketActions", """
                package fxr;

                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;

                /** The action host of the publication graph: the id of each ticket picks the failure it meets. */
                public final class TicketActions {

                    @GraphAction
                    public ActionOutcome publish(TransitionContext<Ticket, TicketState, TicketChange> context) {
                        return switch (context.entity().getId()) {
                            case "unavailable" -> ActionOutcome.failed(PublicationFailures.Unavailable.class,
                                    "provider unavailable");
                            case "rejected" -> throw new ProviderRejectedException("content rejected");
                            case "invalid" -> throw new InvalidResponseException("unreadable response");
                            default -> ActionOutcome.ok();
                        };
                    }
                }
                """);
        sources.put("fxr.Tickets", """
                package fxr;

                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;

                import static fxr.TicketState.CLOSED;
                import static fxr.TicketState.INTEGRATION_FAILED;
                import static fxr.TicketState.PUBLISHED;
                import static fxr.TicketState.READY;
                import static fxr.TicketState.REJECTED_BY_PROVIDER;
                import static fxr.TicketState.RETRY_PENDING;

                /** The publication machine: a failure of PUBLISH takes the route its kind or its type selects. */
                @GenerateGraphApi
                @TicketsGraph.Definition(initial = READY, terminals = {PUBLISHED, CLOSED}, allowsCycle = READY,
                    state = TicketRefs.Status.class, id = TicketRefs.Id.class)
                @TicketsGraph.StatePolicy(state = READY, otherwise = "publish the ticket")
                @TicketsGraph.StatePolicy(state = RETRY_PENDING, otherwise = "retry the publication explicitly")
                @TicketsGraph.StatePolicy(state = REJECTED_BY_PROVIDER, otherwise = "close the rejected ticket")
                @TicketsGraph.StatePolicy(state = INTEGRATION_FAILED, otherwise = "close after manual review")
                @TicketsGraph.Transition(id = TicketChange.SCHEDULE_RETRY, from = READY, to = RETRY_PENDING)
                @TicketsGraph.Transition(id = TicketChange.RECORD_REJECTION, from = READY, to = REJECTED_BY_PROVIDER)
                @TicketsGraph.Transition(id = TicketChange.MARK_INTEGRATION_FAILED, from = READY,
                    to = INTEGRATION_FAILED)
                @TicketsGraph.Transition(id = TicketChange.RETRY, from = RETRY_PENDING, to = READY)
                @TicketsGraph.Transition(id = TicketChange.CLOSE, from = {REJECTED_BY_PROVIDER, INTEGRATION_FAILED},
                    to = CLOSED)
                public interface Tickets extends Machine<Ticket, TicketState, TicketChange> {

                    @TicketsGraph.Transition(id = TicketChange.PUBLISH, from = READY, to = PUBLISHED,
                        action = TicketActionsRefs.Publish.class,
                        recoveries = {
                            @TicketsGraph.Recovery(kind = PublicationFailures.Unavailable.class,
                                transition = TicketChange.SCHEDULE_RETRY,
                                failsOn = ServiceUnavailableException.class),
                            @TicketsGraph.Recovery(kind = PublicationFailures.Rejected.class,
                                transition = TicketChange.RECORD_REJECTION,
                                failsOn = ProviderRejectedException.class)},
                        failsOn = InvalidResponseException.class,
                        onFailure = TicketChange.MARK_INTEGRATION_FAILED)
                    Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                }
                """);
        sources.put("fxr.Publications", """
                package fxr;

                import io.github.graphmaquina.api.Outcome;
                import io.github.graphmaquina.runtime.StateMachines;
                import java.util.List;

                /** Publishes one ticket per declared route and prints where the failure of each one took it. */
                public final class Publications {

                    private Publications() {
                    }

                    public static void main(String[] arguments) {
                        Tickets tickets = new TicketsRuntime(
                                StateMachines.from(TicketsRuntime.graph(new TicketActions()))
                                        .state(Ticket::getStatus, Ticket::setStatus)
                                        .build());
                        for (String id : List.of("unavailable", "rejected", "invalid")) {
                            Ticket ticket = new Ticket(id);
                            Outcome<Ticket, TicketState, TicketChange> outcome = tickets.publish(ticket);
                            Outcome.Failed<Ticket, TicketState, TicketChange> failed = outcome.asFailed()
                                    .orElseThrow(() -> new IllegalStateException(id + " was not routed: " + outcome));
                            if (ticket.getStatus() != failed.movedTo()) {
                                throw new IllegalStateException(id + " moved to " + failed.movedTo()
                                        + " but the ticket is " + ticket.getStatus());
                            }
                            String kind = failed.failureKind().map(Class::getSimpleName).orElse("none");
                            String cause = failed.cause()
                                    .map((Throwable error) -> error.getClass().getSimpleName())
                                    .orElse("none");
                            System.out.println("ROUTED " + id + " " + failed.failureTransition() + " "
                                    + failed.movedTo() + " kind=" + kind + " cause=" + cause);
                        }
                    }
                }
                """);
        return sources;
    }

    /**
     * The billing domain of sub-machines, in a package of its own: {@code fxs.Bookings} binds {@code fxs.Payments} in
     * two states, {@code DEPOSIT} and {@code BALANCE}, and finds the booking of each payment through a lookup of
     * {@code fxs.Ledger}, an instance host; {@code fxs.Billing} is the set of both machines. {@code fxs.Bookkeeping}
     * books a stay, captures its deposit and its balance, captures the deposit of a booking that was never booked,
     * and prints one line {@code BILLING <call> <status> ...} per call, then one {@code BILLING states ...} line.
     *
     * @return a mutable copy of the domain, one entry per compilation unit, keyed by qualified name
     */
    public static Map<String, String> submachineSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fxs.PaymentStatus", """
                package fxs;

                /** The states a payment moves through. */
                public enum PaymentStatus { PENDING, CAPTURED, DECLINED }
                """);
        sources.put("fxs.PaymentTransition", """
                package fxs;

                /** The identified transitions of the payment graph. */
                public enum PaymentTransition { CAPTURE, DECLINE }
                """);
        sources.put("fxs.BookingStatus", """
                package fxs;

                /** The states a booking moves through. */
                public enum BookingStatus { REQUESTED, DEPOSIT, BALANCE, CONFIRMED, CANCELLED }
                """);
        sources.put("fxs.BookingTransition", """
                package fxs;

                /** The identified transitions of the booking graph. */
                public enum BookingTransition { BOOK, DEPOSIT_PAID, BALANCE_PAID, DECLINE }
                """);
        sources.put("fxs.Payment", """
                package fxs;

                import io.github.graphmaquina.annotation.GraphProperty;

                /** The entity of the payment graph, the sub-machine; it knows no booking. */
                public final class Payment {

                    private final String id;

                    @GraphProperty
                    private PaymentStatus status = PaymentStatus.PENDING;

                    public Payment(String id) {
                        this.id = id;
                    }

                    @GraphProperty
                    public String id() {
                        return id;
                    }

                    public PaymentStatus status() {
                        return status;
                    }

                    public void status(PaymentStatus value) {
                        status = value;
                    }

                    @Override
                    public String toString() {
                        return id;
                    }
                }
                """);
        sources.put("fxs.Booking", """
                package fxs;

                import io.github.graphmaquina.annotation.GraphProperty;
                import java.util.Optional;

                /** The entity of the booking graph, the parent: it holds its deposit and its balance. */
                public final class Booking {

                    private final String id;

                    @GraphProperty
                    private BookingStatus status = BookingStatus.REQUESTED;

                    private final Payment deposit;

                    private final Payment balance;

                    public Booking(String id, Payment deposit, Payment balance) {
                        this.id = id;
                        this.deposit = deposit;
                        this.balance = balance;
                    }

                    @GraphProperty
                    public String id() {
                        return id;
                    }

                    public BookingStatus status() {
                        return status;
                    }

                    public void status(BookingStatus value) {
                        status = value;
                    }

                    @GraphProperty
                    public Optional<Payment> deposit() {
                        return Optional.of(deposit);
                    }

                    @GraphProperty
                    public Optional<Payment> balance() {
                        return Optional.of(balance);
                    }
                }
                """);
        sources.put("fxs.Ledger", """
                package fxs;

                import io.github.graphmaquina.annotation.GraphLookup;
                import java.util.HashMap;
                import java.util.Map;
                import java.util.Optional;

                /** The lookup host: it finds the booking a payment is the deposit or the balance of. */
                public final class Ledger {

                    private final Map<String, Booking> deposits = new HashMap<>();

                    private final Map<String, Booking> balances = new HashMap<>();

                    public void register(Booking booking) {
                        deposits.put(booking.deposit().orElseThrow().id(), booking);
                        balances.put(booking.balance().orElseThrow().id(), booking);
                    }

                    @GraphLookup
                    public Optional<Booking> depositOf(Payment payment) {
                        return Optional.ofNullable(deposits.get(payment.id()));
                    }

                    @GraphLookup
                    public Optional<Booking> balanceOf(Payment payment) {
                        return Optional.ofNullable(balances.get(payment.id()));
                    }
                }
                """);
        sources.put("fxs.Payments", """
                package fxs;

                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;

                import static fxs.PaymentStatus.CAPTURED;
                import static fxs.PaymentStatus.DECLINED;
                import static fxs.PaymentStatus.PENDING;
                import static fxs.PaymentTransition.CAPTURE;
                import static fxs.PaymentTransition.DECLINE;

                /** The payment machine, the child: it ends captured or declined. */
                @GenerateGraphApi
                @PaymentsGraph.Definition(initial = PENDING, terminals = {CAPTURED, DECLINED},
                    state = PaymentRefs.Status.class, id = PaymentRefs.Id.class)
                @PaymentsGraph.StatePolicy(state = PENDING, otherwise = "a payment is captured or declined")
                public interface Payments extends Machine<Payment, PaymentStatus, PaymentTransition> {

                    @PaymentsGraph.Transition(id = CAPTURE, from = PENDING, to = CAPTURED)
                    Outcome<Payment, PaymentStatus, PaymentTransition> capture(Payment payment);

                    @PaymentsGraph.Transition(id = DECLINE, from = PENDING, to = DECLINED)
                    Outcome<Payment, PaymentStatus, PaymentTransition> decline(Payment payment);
                }
                """);
        sources.put("fxs.Bookings", """
                package fxs;

                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.annotation.GraphPeers;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;

                import static fxs.BookingStatus.BALANCE;
                import static fxs.BookingStatus.CANCELLED;
                import static fxs.BookingStatus.CONFIRMED;
                import static fxs.BookingStatus.DEPOSIT;
                import static fxs.BookingStatus.REQUESTED;
                import static fxs.BookingTransition.BALANCE_PAID;
                import static fxs.BookingTransition.BOOK;
                import static fxs.BookingTransition.DECLINE;
                import static fxs.BookingTransition.DEPOSIT_PAID;

                /**
                 * The booking machine, the parent: its deposit runs in DEPOSIT and its balance in BALANCE, and the end
                 * of each payment selects the exit of its node.
                 */
                @GenerateGraphApi
                @GraphPeers(Payments.class)
                @BookingsGraph.Definition(initial = REQUESTED, terminals = {CONFIRMED, CANCELLED},
                    state = BookingRefs.Status.class, id = BookingRefs.Id.class)
                @BookingsGraph.StatePolicy(state = REQUESTED, otherwise = "a booking is requested first")
                @BookingsGraph.StatePolicy(state = DEPOSIT, otherwise = "the deposit decides")
                @BookingsGraph.StatePolicy(state = BALANCE, otherwise = "the balance decides")
                @BookingsGraph.Transition(id = DEPOSIT_PAID, from = DEPOSIT, to = BALANCE)
                @BookingsGraph.Transition(id = BALANCE_PAID, from = BALANCE, to = CONFIRMED)
                @BookingsGraph.Transition(id = DECLINE, from = {DEPOSIT, BALANCE}, to = CANCELLED)
                @BookingsPayments.Submachine(state = DEPOSIT, child = BookingRefs.Deposit.class,
                    parentLookup = LedgerRefs.DepositOf.class,
                    exits = {@BookingsPayments.Exit(on = PaymentStatus.CAPTURED, transition = DEPOSIT_PAID),
                             @BookingsPayments.Exit(on = PaymentStatus.DECLINED, transition = DECLINE)})
                @BookingsPayments.Submachine(state = BALANCE, child = BookingRefs.Balance.class,
                    parentLookup = LedgerRefs.BalanceOf.class,
                    exits = {@BookingsPayments.Exit(on = PaymentStatus.CAPTURED, transition = BALANCE_PAID),
                             @BookingsPayments.Exit(on = PaymentStatus.DECLINED, transition = DECLINE)})
                public interface Bookings extends Machine<Booking, BookingStatus, BookingTransition> {

                    @BookingsGraph.Transition(id = BOOK, from = REQUESTED, to = DEPOSIT)
                    Outcome<Booking, BookingStatus, BookingTransition> book(Booking booking);
                }
                """);
        sources.put("fxs.Billing", """
                package fxs;

                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;

                /** The set of both machines, which builds the payment definition once for both bindings. */
                @StateGraphs
                public interface Billing extends Machines {

                    Bookings bookings();

                    Payments payments();
                }
                """);
        sources.put("fxs.Bookkeeping", """
                package fxs;

                import io.github.graphmaquina.api.Outcome;
                import io.github.graphmaquina.api.SubmachineStart;
                import io.github.graphmaquina.api.TransitionRecord;

                /** Runs a stay through both of its payments, then a payment whose booking was never booked. */
                public final class Bookkeeping {

                    private Bookkeeping() {
                    }

                    public static void main(String[] arguments) {
                        Ledger ledger = new Ledger();
                        Payment deposit = new Payment("deposit");
                        Payment balance = new Payment("balance");
                        Booking stay = new Booking("stay", deposit, balance);
                        Payment early = new Payment("early");
                        Booking unbooked = new Booking("unbooked", early, new Payment("late"));
                        ledger.register(stay);
                        ledger.register(unbooked);
                        try (Billing billing = BillingRuntime.builder(ledger).build()) {
                            print("book", billing.bookings().book(stay));
                            print("deposit", billing.payments().capture(deposit));
                            print("balance", billing.payments().capture(balance));
                            print("early", billing.payments().capture(early));
                        }
                        System.out.println("BILLING states stay=" + stay.status() + " deposit=" + deposit.status()
                                + " balance=" + balance.status() + " unbooked=" + unbooked.status()
                                + " early=" + early.status());
                    }

                    /** Prints the status of {@code outcome}, then its refusal, its hops and its starts. */
                    private static void print(String call, Outcome<?, ?, ?> outcome) {
                        StringBuilder line = new StringBuilder("BILLING ").append(call).append(' ')
                                .append(outcome.status());
                        if (outcome instanceof Outcome.Rejected<?, ?, ?> rejected) {
                            line.append(' ').append(rejected.kind()).append(' ').append(rejected.reason());
                        }
                        for (TransitionRecord<?, ?, ?> hop : outcome.records()) {
                            line.append(' ').append(hop.from()).append('-').append(hop.transition())
                                    .append("->").append(hop.to());
                        }
                        for (SubmachineStart<?, ?, ?> start : outcome.starts()) {
                            line.append(" start ").append(start.node()).append(':').append(start.child())
                                    .append(':').append(start.initial());
                        }
                        System.out.println(line);
                    }
                }
                """);
        return sources;
    }

    /**
     * Writes {@code sources} under {@code src/main/java}, one file per qualified name, replacing whatever the
     * main source root held before, which is what editing a source tree does.
     *
     * @param sources the compilation units, keyed by qualified name
     * @throws IOException if the sources cannot be written
     */
    public void writeSources(Map<String, String> sources) throws IOException {
        writeSources(sources, StandardCharsets.UTF_8);
    }

    /**
     * The same, with every source written in {@code encoding}, as a project that declares that encoding keeps them.
     *
     * @param sources the compilation units, keyed by qualified name
     * @param encoding the encoding the files are written in
     * @throws IOException if the sources cannot be written
     */
    public void writeSources(Map<String, String> sources, Charset encoding) throws IOException {
        Path root = directory.resolve("src/main/java");
        deleteRecursively(root);
        Files.createDirectories(root);
        for (Map.Entry<String, String> source : sources.entrySet()) {
            write(root, source.getKey(), source.getValue(), encoding);
        }
    }

    /**
     * Writes one compilation unit under {@code src/test/java}.
     *
     * @param qualifiedName the qualified name of the type
     * @param content the source text
     * @throws IOException if the source cannot be written
     */
    public void writeTestSource(String qualifiedName, String content) throws IOException {
        write(Files.createDirectories(directory.resolve("src/test/java")), qualifiedName, content,
                StandardCharsets.UTF_8);
    }

    private static void write(Path root, String qualifiedName, String content, Charset encoding)
            throws IOException {
        Path file = root.resolve(qualifiedName.replace('.', '/') + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, encoding);
    }

    /**
     * Replaces the sources with the one type that carries no StateGraph marker, so the next build generates no
     * vocabulary and publishes an empty index while still compiling the project.
     *
     * @throws IOException if the sources cannot be rewritten
     */
    public void removeAllStateGraphDeclarations() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.Unrelated", domainSources().get("fx.Unrelated"));
        writeSources(sources);
    }

    /**
     * Writes {@code lib/dep.jar}, a dependency whose only StateGraph content is the index it publishes, so a
     * test can change that resource alone and watch the generation react to it. The index is of the current
     * schema and of this very tool version, which is what a coordinated producer publishes.
     *
     * @param compilationId the identity the published index claims
     * @throws IOException if the jar cannot be written
     */
    public void writeDependencyJar(String compilationId) throws IOException {
        writeDependencyJar(compilationId, SCHEMA_VERSION, VERSION);
    }

    /**
     * The same dependency, publishing an index of {@code schemaVersion} written by {@code toolVersion}, so a
     * test can present a producer this tool must refuse instead of interpret.
     *
     * @param compilationId the identity the published index claims
     * @param schemaVersion the schema the index declares
     * @param toolVersion the tool version the index claims to have been written by
     * @throws IOException if the jar cannot be written
     */
    public void writeDependencyJar(String compilationId, int schemaVersion, String toolVersion)
            throws IOException {
        Path jar = Files.createDirectories(directory.resolve("lib")).resolve("dep.jar");
        String index = """
                {
                  "schemaVersion": %d,
                  "toolVersion": "%s",
                  "compilationId": "%s",
                  "model": {
                    "graphs": [],
                    "symbols": []
                  },
                  "annotationTypes": []
                }
                """.formatted(schemaVersion, toolVersion, compilationId);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar), StandardCharsets.UTF_8)) {
            out.putNextEntry(new ZipEntry(EXPORTS));
            out.write(index.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    /**
     * Runs a build expected to succeed.
     *
     * @param arguments the Gradle command-line arguments
     * @return the build result
     */
    public BuildResult run(String... arguments) {
        return runner(arguments).build();
    }

    /**
     * Runs a build expected to fail.
     *
     * @param arguments the Gradle command-line arguments
     * @return the build result
     */
    public BuildResult runFailing(String... arguments) {
        return runner(arguments).buildAndFail();
    }

    private GradleRunner runner(String... arguments) {
        GradleRunner runner = GradleRunner.create()
                .withProjectDir(directory.toFile())
                .withArguments(arguments);
        return injectedPluginClasspath ? runner.withPluginClasspath() : runner;
    }

    /**
     * The version every StateGraph artifact of the fixture repository is published under, which is also the
     * tool version a coordinated producer's index claims.
     *
     * @return the version this build resolved
     */
    public static String version() {
        return VERSION;
    }

    /**
     * The fixture's project directory.
     *
     * @return the project directory
     */
    public Path projectDirectory() {
        return directory;
    }

    /**
     * Whether {@code relativePath} exists under the project directory.
     *
     * @param relativePath a path relative to the project directory
     * @return {@code true} if the file or directory exists
     */
    public boolean exists(String relativePath) {
        return Files.exists(directory.resolve(relativePath));
    }

    /**
     * Whether the project's jar carries {@code entry}.
     *
     * @param entry the jar entry name
     * @return {@code true} if the jar declares that entry
     */
    public boolean jarContains(String entry) {
        return jarEntries().contains(entry);
    }

    /**
     * Whether the jar carries any binary the sealed inventory of the main compilation excluded, which is the
     * compilation carrier the generator emits for every run.
     *
     * @return {@code true} if one of the excluded binaries reached the jar
     * @throws IllegalStateException if the sealed inventory excluded no binary, which leaves nothing to look for
     */
    public boolean jarContainsGraphCompilationCarrier() {
        JsonObject inventory = readJson(directory.resolve("build/graphmaquina/main/owned-outputs.json"));
        JsonArray excluded = inventory.getAsJsonArray("excludedBinaries");
        if (excluded.isEmpty()) {
            throw new IllegalStateException("the sealed inventory excluded no binary, so this fixture cannot"
                    + " tell whether the carrier reached the jar");
        }
        List<String> entries = jarEntries();
        for (JsonElement binary : excluded) {
            if (entries.contains(binary.getAsString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether any entry of the project's runtime classpath carries {@code fragment} in its file name, as the
     * fixture's own reporting task prints it.
     *
     * @param fragment the fragment to look for
     * @return {@code true} if a runtime entry matches
     */
    public boolean runtimeClasspathContains(String fragment) {
        for (String line : run("printRuntimeClasspath").getOutput().lines().toList()) {
            if (line.startsWith(RUNTIME_ENTRY) && line.substring(RUNTIME_ENTRY.length()).contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The source directories the {@code main} source set declares, as the project model reports them — which is
     * what an IDE import and every consumer of {@code sourceSets.main.java} read.
     *
     * @return the absolute paths of the source roots, sorted
     */
    public List<String> mainSourceRoots() {
        List<String> roots = new ArrayList<>();
        for (String line : run("printSourceRoots").getOutput().lines().toList()) {
            if (line.startsWith(SOURCE_ROOT)) {
                roots.add(line.substring(SOURCE_ROOT.length()));
            }
        }
        return List.copyOf(roots);
    }

    /**
     * The compiler arguments {@code compileTestJava} runs with, argument providers included, as the fixture's
     * own reporting task prints them. Only the build of {@link #plainJUnitTests} declares that task.
     *
     * @return the effective compiler arguments of the test compilation
     */
    public List<String> testCompilerArguments() {
        List<String> arguments = new ArrayList<>();
        for (String line : run("printTestCompilerArgs").getOutput().lines().toList()) {
            if (line.startsWith(TEST_COMPILER_ARG)) {
                arguments.add(line.substring(TEST_COMPILER_ARG.length()));
            }
        }
        return List.copyOf(arguments);
    }

    /**
     * The names the index published inside the jar exports: every machine facade and every symbol.
     *
     * @return the exported names, in the order the index declares them
     */
    public List<String> localExports() {
        return exportsOf(readJarJson(EXPORTS));
    }

    /**
     * The same, for an index published at {@code relativePath} under the project directory, so a test can read
     * the index of a source set that is never packaged.
     *
     * @param relativePath the path of the published index, relative to the project directory
     * @return the exported names, in the order the index declares them
     */
    public List<String> localExportsOf(String relativePath) {
        return exportsOf(readJson(directory.resolve(relativePath)));
    }

    /**
     * The {@code origin.path} the index the project's jar publishes gives the machine {@code facade}: the path of its
     * source, relative to the project directory.
     *
     * @param facade the qualified name of the machine's facade
     * @return the published origin path
     * @throws IllegalStateException if that index declares no such machine
     */
    public String publishedOrigin(String facade) {
        for (JsonElement graph : readJarJson(EXPORTS).getAsJsonObject("model").getAsJsonArray("graphs")) {
            JsonObject machine = graph.getAsJsonObject();
            if (facade.equals(machine.get("facade").getAsString())) {
                return machine.getAsJsonObject("origin").get("path").getAsString();
            }
        }
        throw new IllegalStateException("the index the jar publishes declares no machine " + facade);
    }

    private static List<String> exportsOf(JsonObject index) {
        JsonObject model = index.getAsJsonObject("model");
        List<String> exports = new ArrayList<>();
        for (JsonElement graph : model.getAsJsonArray("graphs")) {
            exports.add(graph.getAsJsonObject().get("facade").getAsString());
        }
        for (JsonElement symbol : model.getAsJsonArray("symbols")) {
            exports.add(symbol.getAsJsonObject().get("qualifiedName").getAsString());
        }
        return List.copyOf(exports);
    }

    /**
     * Whether the compilation of {@code sourceSet} left a sealed inventory: the final one is there and no
     * pending one is.
     *
     * @param sourceSet the name of the source set
     * @return {@code true} if the inventory is sealed
     */
    public boolean inventoryIsSealed(String sourceSet) {
        Path work = directory.resolve("build/graphmaquina/" + sourceSet);
        return Files.isRegularFile(work.resolve("owned-outputs.json"))
                && !Files.exists(work.resolve("owned-outputs.pending.json"));
    }

    /**
     * The request the generation task of {@code sourceSet} wrote, as a JSON object.
     *
     * @param sourceSet the name of the source set
     * @return the parsed request
     */
    public JsonObject request(String sourceSet) {
        return readJson(directory.resolve("build/graphmaquina/" + sourceSet + "/request.json"));
    }

    /**
     * The identities of the dependency indexes the annotation manifest of {@code sourceSet} carries: the indexes
     * the generation read from the compile classpath of that source set.
     *
     * @param sourceSet the name of the source set
     * @return the compilation identities, in the order the manifest lists them
     */
    public List<String> manifestDependencies(String sourceSet) {
        JsonObject manifest = readJson(
                directory.resolve("build/graphmaquina/" + sourceSet + "/annotation-manifest.json"));
        List<String> identities = new ArrayList<>();
        for (JsonElement dependency : manifest.getAsJsonArray("dependencies")) {
            identities.add(dependency.getAsJsonObject().get("compilationId").getAsString());
        }
        return List.copyOf(identities);
    }

    /** The names of every entry of the project's jar. */
    private List<String> jarEntries() {
        return archiveEntries(directory.relativize(jar()).toString());
    }

    /**
     * The names of every entry of the archive at {@code relativePath}, such as the sources jar or the javadoc jar
     * a build assembles beside the binary one.
     *
     * @param relativePath the path of the archive, relative to the project directory
     * @return the entry names, in the order the archive lists them
     */
    public List<String> archiveEntries(String relativePath) {
        List<String> entries = new ArrayList<>();
        try (ZipFile jar = new ZipFile(directory.resolve(relativePath).toFile(), StandardCharsets.UTF_8)) {
            for (ZipEntry entry : jar.stream().toList()) {
                entries.add(entry.getName());
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return entries;
    }

    /** Reads one jar entry as a JSON object. */
    private JsonObject readJarJson(String entry) {
        try (ZipFile jar = new ZipFile(jar().toFile(), StandardCharsets.UTF_8)) {
            ZipEntry found = jar.getEntry(entry);
            if (found == null) {
                throw new IllegalStateException("the jar " + jar().getFileName() + " carries no " + entry);
            }
            try (InputStream stream = jar.getInputStream(found);
                    Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static JsonObject readJson(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** The single jar the project's {@code jar} task produced. */
    private Path jar() {
        return directory.resolve("build/libs/fixture-1.0.0.jar");
    }

    /** Removes {@code root} and everything below it, deepest entry first; an absent root is nothing to do. */
    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path entry : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    /**
     * The named system property, which the build always sets for this test JVM.
     *
     * @param name the property name
     * @return its value
     */
    private static String requireProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("The build must set the system property " + name);
        }
        return value;
    }
}
