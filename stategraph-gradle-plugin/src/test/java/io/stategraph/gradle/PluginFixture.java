package io.stategraph.gradle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
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
 * <p>The project directory carries a space and a non-ASCII character in its name, so every functional test also
 * exercises the request encoding and the process invocation over a path that is not plain ASCII.</p>
 */
public final class PluginFixture {

    /** The version every StateGraph artifact of the fixture repository is published under. */
    private static final String VERSION = "0.3.0-SNAPSHOT";

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
    private static final String EXPORTS = "META-INF/stategraph/exports.json";

    private final Path directory;

    /** Whether TestKit injects the plugin under development, instead of the build resolving a published one. */
    private final boolean injectedPluginClasspath;

    private PluginFixture(Path directory, boolean injectedPluginClasspath) {
        this.directory = directory;
        this.injectedPluginClasspath = injectedPluginClasspath;
    }

    /**
     * Writes a settings file and a build script that applies only the {@code java} and {@code io.stategraph}
     * plugins, with no further configuration.
     *
     * @param directory the project directory to materialize the build into
     * @return a fixture bound to the written project
     * @throws IOException if the build files cannot be written
     */
    public static PluginFixture applicationOnly(Path directory) throws IOException {
        Files.writeString(directory.resolve("settings.gradle.kts"), """
                rootProject.name = "stategraph-plugin-fixture"
                """);
        Files.writeString(directory.resolve("build.gradle.kts"), """
                plugins {
                    java
                    id("io.stategraph")
                }
                """);
        return new PluginFixture(directory, true);
    }

    /**
     * Writes the shared adapter domain — {@code fx.Status}, {@code fx.Order}, {@code fx.Actions},
     * {@code fx.Orders} and the unmarked {@code fx.Unrelated} — into a build that resolves StateGraph from
     * {@code repository}, compiles with Lombok before the StateGraph processor and uses the project's own strict
     * compiler options.
     *
     * @param directory the temporary directory to materialize the project under
     * @param repository the file Maven repository the StateGraph artifacts were published to
     * @return a fixture bound to the written project
     * @throws IOException if the project cannot be written
     */
    public static PluginFixture basic(Path directory, Path repository) throws IOException {
        return materialize(directory, repository, "", true,
                "    testAnnotationProcessor(\"io.stategraph:stategraph-processor:" + VERSION + "\")\n", "");
    }

    /**
     * The same domain, in a build that declares the plugin with a version and resolves it — plugin marker and
     * plugin jar — from the fixture repository, with nothing injected by TestKit. It is the shape of a consumer
     * build outside this repository.
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

    /** Writes the shared project with {@code testProcessors} and {@code extraConfiguration} spliced into it. */
    private static PluginFixture materialize(Path directory, Path repository, String pluginVersion,
            boolean injectedPluginClasspath, String testProcessors, String extraConfiguration) throws IOException {
        if (!Files.isDirectory(repository)) {
            throw new IllegalStateException("the fixture repository " + repository + " does not exist; run"
                    + " ./gradlew :stategraph-core:publishMavenPublicationToAdapterTestsRepository"
                    + " :stategraph-codegen:publishMavenPublicationToAdapterTestsRepository"
                    + " :stategraph-processor:publishMavenPublicationToAdapterTestsRepository first");
        }
        Path project = Files.createDirectories(directory.resolve("fixture project ñ"));
        Files.writeString(project.resolve("settings.gradle.kts"), settings(repository));
        Files.writeString(project.resolve("build.gradle.kts"),
                buildScript(pluginVersion, testProcessors, extraConfiguration));
        PluginFixture fixture = new PluginFixture(project, injectedPluginClasspath);
        fixture.writeSources(domainSources());
        return fixture;
    }

    /** The settings file: both the plugin and the dependency repositories point at the fixture repository. */
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
     * StateGraph processor, and the two reporting tasks the assertions read.
     */
    private static String buildScript(String pluginVersion, String testProcessors,
            String extraConfiguration) {
        return """
                import io.stategraph.gradle.GenerateGraphApi

                plugins {
                    java
                    id("io.stategraph")%7$s
                }

                group = "fx.build"
                version = "1.0.0"

                java {
                    toolchain { languageVersion = JavaLanguageVersion.of(21) }
                }

                configurations.all {
                    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
                }

                dependencies {
                    implementation("io.stategraph:stategraph-core:%1$s")
                    implementation(fileTree("lib") { include("*.jar") })
                    compileOnly("org.projectlombok:lombok:%2$s")
                    // Lombok first: the processor reads the accessors Lombok generates on Order.
                    annotationProcessor("org.projectlombok:lombok:%2$s")
                    annotationProcessor("io.stategraph:stategraph-processor:%1$s")
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
                    doLast {
                        println("GENERATOR_LAUNCHER=" + generator.get().executablePath.asFile.absolutePath)
                        println("GENERATOR_LANGUAGE_VERSION="
                                + generator.get().metadata.languageVersion.asInt())
                        println("TOOLCHAIN_LAUNCHER=" + toolchain.get().executablePath.asFile.absolutePath)
                        println("DAEMON_JAVA_HOME=" + System.getProperty("java.home"))
                    }
                }

                %6$s
                """.formatted(VERSION, LOMBOK, RUNTIME_ENTRY, SOURCE_ROOT, testProcessors, extraConfiguration,
                pluginVersion);
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
        sources.put("fx.Order", """
                package fx;

                import io.stategraph.annotation.GraphProperty;
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

                import io.stategraph.annotation.GraphAction;
                import io.stategraph.api.ActionOutcome;
                import io.stategraph.api.TransitionContext;

                /** The action host of the graph. */
                public final class Actions {

                    @GraphAction
                    public ActionOutcome approve(TransitionContext<Order, Status> context) {
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

                import io.stategraph.annotation.GenerateGraphApi;
                import io.stategraph.api.Machine;
                import io.stategraph.api.Outcome;

                import static fx.Status.DONE;
                import static fx.Status.NEW;

                /** The machine of the fixture. */
                @GenerateGraphApi
                @%1$sGraph.Definition(initial = NEW, terminals = DONE,
                    state = OrderRefs.Status.class, id = OrderRefs.Id.class)
                @%1$sGraph.Row(state = NEW, otherwise = "a new order is approved")
                @%1$sGraph.Row(state = DONE, otherwise = "a finished order does not move")
                public interface %1$s extends Machine<Order, Status> {

                    @%1$sGraph.Action(from = NEW, to = DONE, action = ActionsRefs.%2$s.class)
                    Outcome<Order, Status> %3$s(Order order);
                }
                """.formatted(facade, symbol, operation);
    }

    /**
     * Writes {@code sources} under {@code src/main/java}, one file per qualified name, replacing whatever the
     * main source root held before, which is what editing a source tree does.
     *
     * @param sources the compilation units, keyed by qualified name
     * @throws IOException if the sources cannot be written
     */
    public void writeSources(Map<String, String> sources) throws IOException {
        Path root = directory.resolve("src/main/java");
        deleteRecursively(root);
        Files.createDirectories(root);
        for (Map.Entry<String, String> source : sources.entrySet()) {
            write(root, source.getKey(), source.getValue());
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
        write(Files.createDirectories(directory.resolve("src/test/java")), qualifiedName, content);
    }

    private static void write(Path root, String qualifiedName, String content) throws IOException {
        Path file = root.resolve(qualifiedName.replace('.', '/') + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /**
     * Replaces the sources with the one type that carries no StateGraph marker, so the next build generates and
     * publishes nothing while still compiling the project.
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
     * test can change that resource alone and watch the generation react to it.
     *
     * @param compilationId the identity the published index claims
     * @throws IOException if the jar cannot be written
     */
    public void writeDependencyJar(String compilationId) throws IOException {
        Path jar = Files.createDirectories(directory.resolve("lib")).resolve("dep.jar");
        String index = """
                {
                  "schemaVersion": 1,
                  "toolVersion": "%s",
                  "compilationId": "%s",
                  "model": {
                    "graphs": [],
                    "symbols": []
                  },
                  "annotationTypes": []
                }
                """.formatted(VERSION, compilationId);
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
     */
    public boolean jarContainsGraphCompilationCarrier() {
        JsonObject inventory = readJson(directory.resolve("build/stategraph/main/owned-outputs.json"));
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
     * @return the absolute paths of the source roots
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
     * own reporting task prints them.
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
        Path work = directory.resolve("build/stategraph/" + sourceSet);
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
        return readJson(directory.resolve("build/stategraph/" + sourceSet + "/request.json"));
    }

    /** The names of every entry of the project's jar. */
    private List<String> jarEntries() {
        List<String> entries = new ArrayList<>();
        try (ZipFile jar = new ZipFile(jar().toFile(), StandardCharsets.UTF_8)) {
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
}
