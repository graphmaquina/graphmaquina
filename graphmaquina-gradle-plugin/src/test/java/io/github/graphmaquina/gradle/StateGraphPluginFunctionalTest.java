package io.github.graphmaquina.gradle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Functional tests driving the StateGraph Gradle plugin through TestKit over the shared adapter domain: one
 * clean build, and the lifecycle regressions a build that keeps generating, compiling and publishing must hold.
 */
class StateGraphPluginFunctionalTest {

    /** The file Maven repository the parent build published core, codegen and the processor to. */
    private static final Path FIXTURE_REPOSITORY = Path.of(System.getProperty("graphmaquina.fixtureRepository"));

    /** The published index of the main compilation, beside its classes. */
    private static final String MAIN_EXPORTS = "build/classes/java/main/META-INF/graphmaquina/exports.json";

    /** The published index of the test compilation, beside its own classes. */
    private static final String TEST_EXPORTS = "build/classes/java/test/META-INF/graphmaquina/exports.json";

    /** The inventory the preparation of the main compilation writes before it retires anything. */
    private static final String MAIN_PENDING_INVENTORY = "build/graphmaquina/main/owned-outputs.pending.json";

    /** Where a compilation publishes its index, relative to whatever class output it was given. */
    private static final String EXPORTS_ENTRY = "META-INF/graphmaquina/exports.json";

    /** The package the generator emits the carrier of every compilation into, as an archive path. */
    private static final String CARRIER_PACKAGE = "io/github/graphmaquina/generated/bootstrap/";

    /** The two annotation processors Lombok registers, as a {@code -processor} list names them. */
    private static final String LOMBOK_PROCESSORS = "lombok.launch.AnnotationProcessorHider$AnnotationProcessor,"
            + "lombok.launch.AnnotationProcessorHider$ClaimingProcessor";

    /** The StateGraph annotation processor, as a {@code -processor} list names it. */
    private static final String STATEGRAPH_PROCESSOR = "io.github.graphmaquina.processor.StateGraphProcessor";

    /**
     * An argument provider of the main compilation that reads its arguments from a file the task
     * {@code writeArguments} writes, which runs after the generation, and that prints a marker whenever it runs.
     */
    private static final String FILE_ARGUMENTS = """
            abstract class WriteArguments : DefaultTask() {
                @get:OutputFile abstract val output: RegularFileProperty
                @TaskAction fun write() { output.get().asFile.writeText("-parameters\\n") }
            }
            abstract class FileArguments : CommandLineArgumentProvider {
                @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val argumentsFile: RegularFileProperty
                override fun asArguments(): Iterable<String> {
                    println("FILE_ARGUMENTS_READ")
                    return argumentsFile.get().asFile.readLines()
                }
            }
            val writeArguments = tasks.register<WriteArguments>("writeArguments") {
                output = layout.buildDirectory.file("arguments/compiler.txt")
                mustRunAfter("generateStateGraphApi")
            }
            tasks.named<JavaCompile>("compileJava") {
                options.compilerArgumentProviders.add(objects.newInstance<FileArguments>().apply {
                    argumentsFile = writeArguments.flatMap { it.output }
                })
            }
            """;

    /** An argument provider of the main compilation that gives the arguments of its {@code values}. */
    private static final String FIXED_ARGUMENTS = """
            abstract class FixedArguments : CommandLineArgumentProvider {
                @get:Input abstract val values: ListProperty<String>
                override fun asArguments(): Iterable<String> = values.get()
            }
            tasks.named<JavaCompile>("compileJava") {
                options.compilerArgumentProviders.add(objects.newInstance<FixedArguments>().apply {
                    values = listOf("--release=17")
                })
            }
            """;

    @TempDir
    Path tempDirectory;

    @Test
    void appliesWithoutPublishingTheRootBuild() throws IOException {
        PluginFixture fixture = PluginFixture.applicationOnly(tempDirectory);
        BuildResult result = fixture.run("tasks", "--all", "--stacktrace");
        assertTrue(result.getOutput().contains("generateStateGraphApi"));
        assertTrue(result.getOutput().contains("generateTestStateGraphApi"));
    }

    /**
     * The jar carries the compiled vocabulary, an index that publishes the exports of this compilation alone and no
     * compilation carrier, while neither the generator nor the processor reaches the runtime classpath. The project
     * depends on the jar of another StateGraph compilation, whose index the generation reads as a dependency, as the
     * annotation manifest shows, so an index that published more than the exports of this compilation would show it.
     */
    @Test
    void compilesGeneratedVocabularyAndPublishesOnlyLocalExports() throws IOException {
        PluginFixture producer = PluginFixture.basic(
                Files.createDirectories(tempDirectory.resolve("producer")), FIXTURE_REPOSITORY);
        producer.run("jar");
        PluginFixture fixture = PluginFixture.basic(
                Files.createDirectories(tempDirectory.resolve("consumer")), FIXTURE_REPOSITORY);
        fixture.replaceInBuildScript("group = \"fx.build\"", "group = \"cx.build\"");
        fixture.writeSources(testDomain());
        fixture.writeFile("lib/producer.jar",
                Files.readAllBytes(producer.projectDirectory().resolve("build/libs/fixture-1.0.0.jar")));

        BuildResult result = fixture.run("clean", "jar", "--stacktrace");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        assertEquals(List.of("fx.build:fixture:main"), fixture.manifestDependencies("main"));
        assertEquals(List.of("fxt.Tickets", "fxt.TicketActionsRefs.Resolve", "fxt.TicketRefs.Id",
                "fxt.TicketRefs.Status"), fixture.localExports());
        assertTrue(fixture.jarContains("fxt/TicketsGraph.class"));
        assertFalse(fixture.jarContainsGraphCompilationCarrier());
        assertFalse(fixture.runtimeClasspathContains("graphmaquina-codegen"));
        assertFalse(fixture.runtimeClasspathContains("graphmaquina-processor"));
    }

    /**
     * The published artifacts are enough on their own: a build that declares {@code id("io.github.graphmaquina")}
     * with an explicit version resolves the plugin marker and the plugin jar from the repository, with nothing
     * injected by TestKit, and produces the same jar — typed vocabulary, published index, no compilation carrier.
     *
     * <p>{@code --refresh-dependencies} is deliberate: the plugin is republished under the same version
     * before every run, and without it Gradle may keep serving a build it cached earlier.</p>
     */
    @Test
    void publishedPluginMarkerResolves() throws IOException {
        PluginFixture fixture = PluginFixture.publishedPlugin(tempDirectory, FIXTURE_REPOSITORY);

        BuildResult result = fixture.run("jar", "--refresh-dependencies", "--stacktrace");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateStateGraphApi").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
        assertTrue(fixture.jarContains("fx/OrderRefs$Status.class"));
        assertTrue(fixture.jarContains("fx/ActionsRefs$Approve.class"));
        assertTrue(fixture.jarContains("fx/OrdersRuntime.class"));
        assertTrue(fixture.jarContains("fx/OrdersGraph$Transition.class"));
        assertEquals(List.of(), fixture.retiredVocabularyEntries());
        assertEquals(List.of("fx.Orders", "fx.ActionsRefs.Approve", "fx.OrderRefs.Id", "fx.OrderRefs.Status"),
                fixture.localExports());
        assertFalse(fixture.jarContainsGraphCompilationCarrier());
    }

    /**
     * The generated vocabulary is a source root of the source set, not extra source handed to the compile task:
     * that is the only form an IDE import, javadoc or a sources jar reads. The generator itself must still not
     * see it, or every build would hand javac the units it wrote twice and parse its own output as originals.
     */
    @Test
    void registersGeneratedSourcesInTheSourceSet() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);

        List<String> roots = fixture.mainSourceRoots();

        assertTrue(roots.stream().anyMatch(root -> root.endsWith("/src/main/java")), roots.toString());
        assertTrue(roots.stream().anyMatch(root ->
                root.endsWith("/build/generated/sources/graphmaquina/main")), roots.toString());

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        List<String> requested = new ArrayList<>();
        for (JsonElement source : fixture.request("main").getAsJsonArray("sources")) {
            requested.add(source.getAsString());
        }
        assertEquals(1, requested.size(), requested.toString());
        assertTrue(requested.get(0).endsWith("/src/main/java"), requested.toString());
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
    }

    /**
     * A build that changes nothing regenerates nothing and recompiles nothing, and the index the previous cycle
     * published is still there afterwards: the completion step writes into the shared work directory, and if
     * that directory were snapshotted as a whole every build would invalidate the one before it.
     */
    @Test
    void noOpBuildDoesNotInvalidateExports() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.run("jar");
        List<String> published = fixture.localExports();

        BuildResult second = fixture.run("jar");

        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":generateStateGraphApi").getOutcome());
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":compileJava").getOutcome());
        assertTrue(fixture.exists(MAIN_EXPORTS));
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExports());
        assertTrue(published.contains("fx.Orders"));
    }

    @Test
    void deletingLastExportPublishesEmptyIndex() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.run("jar");
        fixture.removeAllStateGraphDeclarations();
        fixture.run("jar");
        assertTrue(fixture.localExports().isEmpty());
        assertFalse(fixture.jarContains("fx/OrdersGraph.class"));
        assertFalse(fixture.jarContains("fx/ActionsRefs$Approve.class"));
        assertFalse(fixture.jarContains("fx/OrdersRuntime.class"));
        assertTrue(fixture.jarContains("fx/Unrelated.class"));
    }

    /**
     * Renaming the action a facade binds and the facade itself retires the generated symbol, the generated
     * vocabulary and the companion of the previous names, and leaves nothing of them in the jar.
     */
    @Test
    void renameRetiresNestedSymbolsAndFacades() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.run("jar");
        assertTrue(fixture.jarContains("fx/ActionsRefs$Approve.class"));
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
        assertTrue(fixture.jarContains("fx/OrdersRuntime.class"));

        fixture.writeSources(renamed());
        fixture.run("jar");

        assertFalse(fixture.jarContains("fx/ActionsRefs$Approve.class"));
        assertFalse(fixture.jarContains("fx/OrdersGraph.class"));
        assertFalse(fixture.jarContains("fx/OrdersRuntime.class"));
        assertTrue(fixture.jarContains("fx/ActionsRefs$Accept.class"));
        assertTrue(fixture.jarContains("fx/RequestsGraph.class"));
        assertTrue(fixture.jarContains("fx/RequestsRuntime.class"));
        assertTrue(fixture.jarContains("fx/Unrelated.class"));
        assertEquals(List.of("fx.Requests", "fx.ActionsRefs.Accept", "fx.OrderRefs.Id", "fx.OrderRefs.Status"),
                fixture.localExports());
    }

    /**
     * A compilation javac rejects publishes nothing: the preparation left its pending inventory behind, the
     * completion never ran, and the index of the previous cycle is gone rather than stale.
     */
    @Test
    void failedCompileDoesNotSealInventory() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.run("jar");
        assertTrue(fixture.inventoryIsSealed("main"));

        Map<String, String> broken = PluginFixture.domainSources();
        broken.put("fx.Broken", """
                package fx;

                /** A declaration javac refuses, so the compilation of this fixture fails. */
                public final class Broken {

                    private final int count = "not a number";
                }
                """);
        fixture.writeSources(broken);
        BuildResult result = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, result.task(":compileJava").getOutcome());
        assertTrue(fixture.exists("build/graphmaquina/main/owned-outputs.pending.json"));
        assertFalse(fixture.inventoryIsSealed("main"));
        assertFalse(fixture.exists(MAIN_EXPORTS));
    }

    /**
     * Each source set owns its own generation, its own work directory and its own index, so compiling the tests
     * publishes beside the test classes and leaves the index of the main compilation exactly as it was.
     */
    @Test
    void testSourceSetDoesNotOverwriteMainExports() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.run("classes");
        List<String> main = fixture.localExportsOf(MAIN_EXPORTS);

        for (Map.Entry<String, String> source : testDomain().entrySet()) {
            fixture.writeTestSource(source.getKey(), source.getValue());
        }
        BuildResult result = fixture.run("testClasses");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileTestJava").getOutcome());
        assertTrue(fixture.exists("build/graphmaquina/test/annotation-manifest.json"));
        assertTrue(fixture.inventoryIsSealed("test"));
        assertEquals(List.of("fxt.Tickets", "fxt.TicketActionsRefs.Resolve", "fxt.TicketRefs.Id",
                "fxt.TicketRefs.Status"), fixture.localExportsOf(TEST_EXPORTS));
        assertEquals(main, fixture.localExportsOf(MAIN_EXPORTS));
        assertTrue(main.contains("fx.Orders"));
    }

    /**
     * A source set that declares no StateGraph processor takes no part at all. The wiring is registered on its
     * compilation like on any other and every piece of it is empty: no generation runs for it, no source root
     * is added, no annotation manifest is passed and no lifecycle wraps javac — so its plain JUnit tests
     * compile under {@code -Xlint:all -Werror} without a processor claiming none of their annotations, while
     * the main source set still publishes its own index.
     */
    @Test
    void aSourceSetThatDeclaresNoProcessorIsLeftAlone() throws IOException {
        PluginFixture fixture = PluginFixture.plainJUnitTests(tempDirectory, FIXTURE_REPOSITORY);

        BuildResult result = fixture.run("build", "--stacktrace");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileTestJava").getOutcome());
        assertNull(result.task(":generateTestStateGraphApi"), "no generation is wired into that compilation");
        assertFalse(fixture.exists("build/graphmaquina/test"), "the test source set owns no work directory");
        assertFalse(fixture.exists("build/generated/sources/graphmaquina/test"));
        assertFalse(fixture.exists(TEST_EXPORTS));
        List<String> arguments = fixture.testCompilerArguments();
        assertFalse(arguments.stream().anyMatch(argument -> argument.contains("graphmaquina.annotationManifest")),
                arguments.toString());
        assertTrue(arguments.contains("-Werror"), arguments.toString());

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        assertTrue(fixture.exists(MAIN_EXPORTS));
        assertTrue(fixture.inventoryIsSealed("main"));
        assertTrue(fixture.localExportsOf(MAIN_EXPORTS).contains("fx.Orders"));

        // The task is still there to be named, and says which declaration it is waiting for.
        BuildResult asked = fixture.run("generateTestStateGraphApi", "--info");
        assertEquals(TaskOutcome.SKIPPED, asked.task(":generateTestStateGraphApi").getOutcome());
        assertTrue(asked.getOutput().contains(
                "the test source set declares io.github.graphmaquina:graphmaquina-processor in testAnnotationProcessor"),
                asked::getOutput);
        assertFalse(fixture.exists("build/graphmaquina/test"), "a skipped generation writes nothing");
    }

    /**
     * The wiring of a compilation must not depend on when that compilation was realized. A build script that
     * configures {@code compileTestJava} before it declares the processor of the test source set gets the same
     * build as one that does not: the generation runs, the manifest argument reaches javac and the lifecycle
     * seals the inventory, instead of a compilation that was generated for but never prepared.
     */
    @Test
    void wiresACompilationThatWasRealizedBeforeItsDependencies() throws IOException {
        PluginFixture fixture = PluginFixture.realizingCompilationsEarly(tempDirectory, FIXTURE_REPOSITORY);
        for (Map.Entry<String, String> source : testDomain().entrySet()) {
            fixture.writeTestSource(source.getKey(), source.getValue());
        }

        BuildResult result = fixture.run("testClasses", "--stacktrace");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateTestStateGraphApi").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileTestJava").getOutcome());
        assertTrue(fixture.exists("build/graphmaquina/test/annotation-manifest.json"));
        assertTrue(fixture.inventoryIsSealed("test"));
        assertEquals(List.of("fxt.Tickets", "fxt.TicketActionsRefs.Resolve", "fxt.TicketRefs.Id",
                "fxt.TicketRefs.Status"), fixture.localExportsOf(TEST_EXPORTS));
        assertTrue(fixture.inventoryIsSealed("main"));
    }

    /**
     * The class output and the processor's source output are outputs of {@code JavaCompile}, so the generation
     * task only names them in the request. A build that moves either of them must still write the request again,
     * or the next lifecycle step would retire and publish under the paths of the previous layout. This test moves
     * the processor's source output alone, then the class output.
     */
    @Test
    void movingEitherOutputOfTheCompilationRewritesTheRequest() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.run("classes");
        assertEquals(TaskOutcome.UP_TO_DATE, fixture.run("classes").task(":generateStateGraphApi").getOutcome());
        fixture.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    options.generatedSourceOutputDirectory = layout.buildDirectory.dir("elsewhere/annotations")
                }
                """);

        BuildResult movedSources = fixture.run("classes", "--stacktrace");

        assertEquals(TaskOutcome.SUCCESS, movedSources.task(":generateStateGraphApi").getOutcome());
        String processorSourcesOutput = fixture.request("main").get("processorSourcesOutput").getAsString();
        assertTrue(processorSourcesOutput.endsWith("/build/elsewhere/annotations"), processorSourcesOutput);
        assertTrue(fixture.exists("build/elsewhere/annotations/fx/OrdersRuntime.java"), processorSourcesOutput);
        assertTrue(fixture.inventoryIsSealed("main"));

        fixture.relocateClassOutput("elsewhere/classes");

        BuildResult movedClasses = fixture.run("classes", "--stacktrace");

        assertEquals(TaskOutcome.SUCCESS, movedClasses.task(":generateStateGraphApi").getOutcome());
        String classesOutput = fixture.request("main").get("classesOutput").getAsString();
        assertTrue(classesOutput.endsWith("/build/elsewhere/classes"), classesOutput);
        assertTrue(fixture.exists("build/elsewhere/classes/" + EXPORTS_ENTRY), classesOutput);
    }

    /**
     * The build is configuration-cache compatible: nothing the plugin puts on a task holds a {@code Project}, a
     * {@code Configuration} or a source set, so the whole cycle stores an entry and the next run reuses it.
     *
     * <p>Gradle keys an entry by the tasks that were requested, so after a {@code clean} run the test asks for
     * {@code jar} twice: the second of them is the one that both reuses the entry and finds every step of the
     * cycle up to date.</p>
     */
    @Test
    void reusesTheConfigurationCache() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        BuildResult clean = fixture.run("clean", "--configuration-cache", "--stacktrace");
        assertTrue(clean.getOutput().contains("Configuration cache entry stored."), clean::getOutput);

        BuildResult first = fixture.run("jar", "--configuration-cache", "--stacktrace");
        BuildResult second = fixture.run("jar", "--configuration-cache", "--stacktrace");

        assertEquals(TaskOutcome.SUCCESS, first.task(":generateStateGraphApi").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, first.task(":compileJava").getOutcome());
        assertTrue(first.getOutput().contains("Configuration cache entry stored."), first::getOutput);
        assertTrue(second.getOutput().contains("Reusing configuration cache."), second::getOutput);
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":generateStateGraphApi").getOutcome());
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":compileJava").getOutcome());
        assertTrue(fixture.exists(MAIN_EXPORTS));
        assertTrue(fixture.inventoryIsSealed("main"));
        assertTrue(fixture.localExports().contains("fx.Orders"));
    }

    /**
     * A dependency whose only change is the index it publishes still changes what must be generated, which is
     * why the generation task tracks its application classpath as a runtime classpath and not as an ABI.
     */
    @Test
    void resourceOnlyDependencyManifestChangeRegenerates() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.writeDependencyJar("dep:library:main");
        assertEquals(TaskOutcome.SUCCESS, fixture.run("classes").task(":generateStateGraphApi").getOutcome());
        assertEquals(TaskOutcome.UP_TO_DATE, fixture.run("classes").task(":generateStateGraphApi").getOutcome());

        fixture.writeDependencyJar("dep:another-library:main");

        assertEquals(TaskOutcome.SUCCESS, fixture.run("classes").task(":generateStateGraphApi").getOutcome());
    }

    /**
     * A producer of the retired release is refused, never adapted. A dependency publishing a semantic index of
     * schema 1 fails the generation with {@code SG602} and the demand to regenerate and recompile both sides,
     * and one published by another tool version fails the same way. Neither run falls back to a partial graph:
     * the generation stops before the preparation retires anything, so the index and the sealed inventory of
     * the previous cycle are exactly what they were.
     */
    @Test
    void refusesAProducerOfTheRetiredSchemaOrOfAnotherToolVersion() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.run("classes");
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);

        fixture.writeDependencyJar("dep:library:main", 1, PluginFixture.version());
        BuildResult retired = fixture.runFailing("classes", "--stacktrace");

        assertEquals(TaskOutcome.FAILED, retired.task(":generateStateGraphApi").getOutcome());
        assertTrue(retired.getOutput().contains("[SG602]"), retired::getOutput);
        assertTrue(retired.getOutput().contains("the schema 1 of"), retired::getOutput);
        assertTrue(retired.getOutput().contains("run clean"), retired::getOutput);
        assertTrue(retired.getOutput().contains(
                "regenerate and recompile the producer and the consumer with 2.0"), retired::getOutput);

        fixture.writeDependencyJar("dep:library:main", 2, "1.9.9-retired");
        BuildResult mixed = fixture.runFailing("classes", "--stacktrace");

        assertEquals(TaskOutcome.FAILED, mixed.task(":generateStateGraphApi").getOutcome());
        assertTrue(mixed.getOutput().contains("[SG602]"), mixed::getOutput);
        assertTrue(mixed.getOutput().contains("was written by tool version 1.9.9-retired"), mixed::getOutput);

        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));
    }

    /**
     * The generator runs under the launcher the project's Java toolchain selected: the generation task's own
     * launcher is the one {@code launcherFor(java.toolchain)} reports, of the toolchain's Java 21, and the process
     * the build started is that very executable. The daemon of the build may run on that same JDK, so this test
     * cannot tell the toolchain from the daemon's JVM; {@code launchesSelectedToolchainInsteadOfDaemonJdk} can.
     */
    @Test
    void launchesTheLauncherOfTheSelectedToolchain() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        BuildResult generation = fixture.run("generateStateGraphApi", "--info");
        Map<String, String> reported = report(fixture.run("printGeneratorLauncher"));

        String launcher = reported.get("GENERATOR_LAUNCHER");
        assertEquals(reported.get("TOOLCHAIN_LAUNCHER"), launcher);
        assertEquals("21", reported.get("GENERATOR_LANGUAGE_VERSION"));
        assertTrue(generation.getOutput().contains("Starting process 'command '" + launcher + "''"),
                "the generator did not run under the toolchain launcher " + launcher
                        + "; the daemon runs on " + reported.get("DAEMON_JAVA_HOME"));
        assertEquals("fx.build:fixture:main", fixture.request("main").get("compilationId").getAsString());
        assertTrue(fixture.exists("build/graphmaquina/main/annotation-manifest.json"));
    }

    /**
     * The generator runs under the launcher the project's Java toolchain selected, not under the JVM the Gradle
     * daemon runs on: with a toolchain of another language version than the daemon's, the generation task's own
     * launcher is the toolchain's, of that version and outside the daemon's JDK, and the process the build started
     * is that very executable. The test is skipped when Gradle detects no JDK 21 or later of another language version
     * than the daemon's: a second JDK of the daemon's own language version does not count.
     */
    @Test
    void launchesSelectedToolchainInsteadOfDaemonJdk() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        Toolchains toolchains = toolchains(fixture);
        Optional<Integer> other = toolchains.detected().stream()
                .filter((Integer version) -> version != toolchains.daemon())
                .findFirst();
        assumeTrue(other.isPresent(),
                "Gradle detects no JDK 21 or later of another language version than the one its daemon runs on");
        fixture.replaceInBuildScript("languageVersion = JavaLanguageVersion.of(21)",
                "languageVersion = JavaLanguageVersion.of(" + other.get() + ")");

        BuildResult generation = fixture.run("generateStateGraphApi", "--info");
        Map<String, String> reported = report(fixture.run("printGeneratorLauncher"));

        String launcher = reported.get("GENERATOR_LAUNCHER");
        assertEquals(reported.get("TOOLCHAIN_LAUNCHER"), launcher);
        assertEquals(String.valueOf(other.get()), reported.get("GENERATOR_LANGUAGE_VERSION"));
        assertFalse(Path.of(launcher).startsWith(Path.of(reported.get("DAEMON_JAVA_HOME"))), reported::toString);
        assertTrue(generation.getOutput().contains("Starting process 'command '" + launcher + "''"),
                "the generator did not run under the toolchain launcher " + launcher
                        + "; the daemon runs on " + reported.get("DAEMON_JAVA_HOME"));
    }

    @Test
    void definitionDefaultHookSurvivesConsumerBuildAndIsRemovedOnEdit() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        Map<String, String> sources = PluginFixture.domainSources();
        sources.put("fx.Persistence", """
                package fx;

                public final class Persistence {

                    @io.github.graphmaquina.annotation.GraphHook
                    public static void save(Order order) { }
                }
                """);
        String original = sources.get("fx.Orders");
        sources.put("fx.Orders", original.replace("id = OrderRefs.Id.class)",
                "id = OrderRefs.Id.class, afterCommit = PersistenceRefs.Save.class)"));
        fixture.writeSources(sources);
        fixture.run("jar", "--refresh-dependencies");
        Path runtime = Path.of(fixture.request("main").get("processorSourcesOutput").getAsString())
                .resolve("fx/OrdersRuntime.java");
        assertTrue(Files.readString(runtime).contains(".afterCommit(\"save\""));
        assertTrue(fixture.jarContains("fx/PersistenceRefs$Save.class"));
        sources.put("fx.Orders", original);
        fixture.writeSources(sources);
        fixture.run("jar");
        assertFalse(Files.readString(runtime).contains(".afterCommit("));
    }

    /**
     * Another task of the build generates sources into the same source set — the role ANTLR-, protobuf- or
     * OpenAPI-style generators play — and registers its output directory with the task that writes it. The
     * generation parses that directory too, so it runs after that task: Gradle refuses a task that reads another
     * one's output without depending on it, and a generation that ran first would parse nothing there.
     */
    @Test
    void generatesAfterAnotherTaskThatGeneratesIntoTheSourceSet() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.writeFile("other-generator/fx/other/Helper.java", """
                package fx.other;

                import fx.Order;
                import fx.Status;
                import fx.Transition;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;

                /** An action host another generator of the build writes. */
                public final class Helper {

                    @GraphAction
                    public ActionOutcome ship(TransitionContext<Order, Status, Transition> context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        fixture.appendToBuildScript("""
                val generateOther = tasks.register<Sync>("generateOther") {
                    from("other-generator")
                    into(layout.buildDirectory.dir("generated/sources/other/main"))
                }
                sourceSets.named("main") {
                    java.srcDir(generateOther)
                }
                """);

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateOther").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateStateGraphApi").getOutcome());
        assertTrue(fixture.localExports().contains("fx.other.HelperRefs.Ship"), fixture.localExports()::toString);
        assertTrue(fixture.jarContains("fx/other/HelperRefs$Ship.class"));
    }

    /**
     * The generator parses what javac compiles: the source set as its include and exclude patterns filter it. A
     * draft the build excludes may not even parse, and an excluded facade must not get a vocabulary javac then
     * finds no declaration for.
     */
    @Test
    void parsesOnlyTheSourcesTheSourceSetIncludes() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        Map<String, String> sources = PluginFixture.domainSources();
        sources.put("fx.drafts.Draft", """
                package fx.drafts;

                /** A draft the build leaves out; it does not parse. */
                public final class Draft {

                    this is not a declaration
                }
                """);
        sources.put("fx.legacy.OldOrders", """
                package fx.legacy;

                import fx.ActionsRefs;
                import fx.Order;
                import fx.OrderRefs;
                import fx.Status;
                import fx.Transition;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;

                import static fx.Status.DONE;
                import static fx.Status.NEW;
                import static fx.Transition.APPROVE;

                /** A machine the build leaves out. */
                @GenerateGraphApi
                @OldOrdersGraph.Definition(initial = NEW, terminals = DONE,
                    state = OrderRefs.Status.class, id = OrderRefs.Id.class)
                @OldOrdersGraph.StatePolicy(state = NEW, otherwise = "a new order is approved")
                public interface OldOrders extends Machine<Order, Status, Transition> {

                    @OldOrdersGraph.Transition(id = APPROVE, from = NEW, to = DONE, action = ActionsRefs.Approve.class)
                    Outcome<Order, Status, Transition> approve(Order order);
                }
                """);
        fixture.writeSources(sources);
        fixture.appendToBuildScript("""
                sourceSets.named("main") {
                    java {
                        exclude("fx/drafts/**")
                        exclude("fx/legacy/**")
                    }
                }
                """);

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateStateGraphApi").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        List<String> requested = requestedSources(fixture.request("main"));
        assertFalse(requested.stream().anyMatch((String source) -> source.startsWith("fx/drafts/")),
                requested::toString);
        assertFalse(requested.stream().anyMatch((String source) -> source.startsWith("fx/legacy/")),
                requested::toString);
        assertTrue(requested.contains("fx/Orders.java"), requested::toString);
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
        assertFalse(fixture.jarContains("fx/legacy/OldOrdersGraph.class"));
        assertFalse(fixture.localExports().contains("fx.legacy.OldOrders"));
    }

    /**
     * A source directory that neither the source set nor its compilation task filters is still handed to the
     * generator as a directory, so a hand-written type that shares its simple name with a generated one, in a package
     * that merely ends like the generated one's, is no collision: the generator compares a generated type with the
     * original sources by the path each stands at below its source root and by the types it declares, not by how that
     * path ends.
     */
    @Test
    void aWholeSourceDirectoryStaysADirectoryOfTheRequest() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        Map<String, String> sources = PluginFixture.domainSources();
        sources.put("legacy.fx.OrderRefs", """
                package legacy.fx;

                /** A hand-written type named like the generated fx.OrderRefs, in another package. */
                public final class OrderRefs {

                    private OrderRefs() {
                    }
                }
                """);
        fixture.writeSources(sources);

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateStateGraphApi").getOutcome());
        List<String> requested = new ArrayList<>();
        for (JsonElement source : fixture.request("main").getAsJsonArray("sources")) {
            requested.add(source.getAsString());
        }
        assertEquals(1, requested.size(), requested.toString());
        assertTrue(requested.get(0).endsWith("/src/main/java"), requested.toString());
        assertTrue(fixture.jarContains("fx/OrderRefs$Status.class"));
        assertTrue(fixture.jarContains("legacy/fx/OrderRefs.class"));
    }

    /**
     * The generated vocabulary is a source directory set of its own, nested in the java sources of the source
     * set, so the include and exclude patterns a build gives the source set filter its original sources only: a
     * source set that includes nothing but its own package still compiles everything the generation wrote, the
     * compilation carrier included, and neither the generator nor javac reads what it leaves out.
     */
    @Test
    void compilesEverythingGeneratedWhateverTheSourceSetIncludes() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        Map<String, String> sources = PluginFixture.domainSources();
        sources.put("other.Draft", """
                package other;

                /** A draft outside the included package; it does not parse. */
                public final class Draft {

                    this is not a declaration
                }
                """);
        fixture.writeSources(sources);
        fixture.appendToBuildScript("""
                sourceSets.named("main") {
                    java { include("fx/**") }
                }
                """);

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
        assertFalse(fixture.jarContainsGraphCompilationCarrier());
        assertTrue(fixture.localExports().contains("fx.Orders"));
        List<String> requested = requestedSources(fixture.request("main"));
        assertTrue(requested.contains("fx/Orders.java"), requested::toString);
        assertFalse(requested.stream().anyMatch((String source) -> source.startsWith("other/")),
                requested::toString);
    }

    /**
     * javac compiles the source set as the include and exclude patterns of the compilation task filter it too, so the
     * generator reads it that way: the task's include leaves out a draft outside it that does not parse, and its
     * excludes leave out a draft inside it and a facade, which then gets no vocabulary javac would find no
     * declaration for. The include also names the package of the compilation carrier, since it filters the
     * generated sources as well.
     */
    @Test
    void parsesOnlyTheSourcesTheCompilationTaskIncludes() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        Map<String, String> sources = PluginFixture.domainSources();
        sources.put("other.Draft", draft("other"));
        sources.put("fx.drafts.Draft", draft("fx.drafts"));
        sources.put("fx.Legacy", PluginFixture.orders("Legacy", "Approve", "approve"));
        fixture.writeSources(sources);
        fixture.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    include("fx/**", "io/github/graphmaquina/generated/**")
                    exclude("fx/drafts/**", "**/Legacy.java")
                }
                """);

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateStateGraphApi").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        List<String> requested = requestedSources(fixture.request("main"));
        assertTrue(requested.contains("fx/Orders.java"), requested::toString);
        assertFalse(requested.stream().anyMatch((String source) -> source.startsWith("other/")),
                requested::toString);
        assertFalse(requested.stream().anyMatch((String source) -> source.startsWith("fx/drafts/")),
                requested::toString);
        assertFalse(requested.contains("fx/Legacy.java"), requested::toString);
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
        assertFalse(fixture.jarContains("fx/LegacyGraph.class"));
        assertFalse(fixture.localExports().contains("fx.Legacy"), fixture.localExports()::toString);
    }

    /**
     * A filter the compilation task is given as a spec or a closure is one the generation cannot read, so the
     * generation refuses it before the generator runs, which would otherwise report the draft that filter leaves out
     * with {@code SG601}, and says to move it to the source set. The source set's own filters are followed, specs
     * included, so the build that follows that advice builds.
     */
    @Test
    void refusesAFilterOfTheCompilationTaskBeforeTheGeneratorRuns() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        Map<String, String> sources = PluginFixture.domainSources();
        sources.put("fx.drafts.Draft", draft("fx.drafts"));
        fixture.writeSources(sources);
        fixture.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    exclude { element -> element.file.name == "Draft.java" }
                }
                """);

        BuildResult refused = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, refused.task(":generateStateGraphApi").getOutcome());
        assertTrue(refused.getOutput().contains(
                "fx/drafts/Draft.java, which task ':compileJava' does not compile"), refused::getOutput);
        assertTrue(refused.getOutput().contains("When task ':compileJava' is given such a filter, give it to the java"
                + " sources of the source set instead"),
                refused::getOutput);
        assertFalse(refused.getOutput().contains("[SG601]"), refused::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        fixture.replaceInBuildScript("tasks.named<JavaCompile>(\"compileJava\") {\n    exclude {",
                "sourceSets.named(\"main\") {\n    java.exclude {");

        BuildResult moved = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, moved.task(":compileJava").getOutcome());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
    }

    /**
     * A filter or a source the compilation task is given after a generation leaves that generation up to date, since
     * it changes nothing the generation reads; the preparation then refuses it, before it retires anything and before
     * javac runs: a closure that leaves a facade out of the compilation, whose vocabulary javac would compile without
     * it; a directory added to the compilation alone, whose sources the generation never read, which the refusal names
     * as the directory to give the source set; and a source put in place of the source set's, which the refusal tells
     * to give the source set instead, just as the generation itself would have told.
     */
    @Test
    void refusesAFilterOrASourceOfTheCompilationTaskBeforeThePreparation() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        Map<String, String> sources = PluginFixture.domainSources();
        sources.put("fx.Legacy", PluginFixture.orders("Legacy", "Approve", "approve"));
        fixture.writeSources(sources);
        fixture.writeFile("src/extra/java/extra/Helper.java", """
                package extra;

                /** A class only the compilation task is given. */
                public final class Helper {

                    private Helper() {
                    }
                }
                """);
        fixture.run("jar");
        assertTrue(fixture.jarContains("fx/LegacyGraph.class"));
        fixture.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    exclude { element -> element.file.name == "Legacy.java" }
                }
                """);

        BuildResult filtered = fixture.runFailing("jar");

        assertEquals(TaskOutcome.UP_TO_DATE, filtered.task(":generateStateGraphApi").getOutcome());
        assertEquals(TaskOutcome.FAILED, filtered.task(":compileJava").getOutcome());
        assertTrue(filtered.getOutput().contains("fx/Legacy.java, which task ':compileJava' does not compile"),
                filtered::getOutput);
        assertFalse(filtered.getOutput().contains("cannot find symbol"), "javac never ran");
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        fixture.replaceInBuildScript("exclude { element -> element.file.name == \"Legacy.java\" }",
                "source(\"src/extra/java\")");

        BuildResult added = fixture.runFailing("jar");

        assertEquals(TaskOutcome.UP_TO_DATE, added.task(":generateStateGraphApi").getOutcome());
        assertEquals(TaskOutcome.FAILED, added.task(":compileJava").getOutcome());
        assertTrue(added.getOutput().contains("task ':compileJava' compiles "), added::getOutput);
        assertTrue(added.getOutput().contains(
                "extra/Helper.java, which the StateGraph generation generateStateGraphApi does not read"),
                added::getOutput);
        assertTrue(added.getOutput().contains("/src/extra/java, the directory task ':compileJava' takes that file"
                + " from, to the java sources of the source set instead, with java.srcDir(...)"), added::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        fixture.replaceInBuildScript("source(\"src/extra/java\")", "setSource(fileTree(\"src/extra/java\"))");

        BuildResult replaced = fixture.runFailing("jar");

        assertEquals(TaskOutcome.UP_TO_DATE, replaced.task(":generateStateGraphApi").getOutcome());
        assertEquals(TaskOutcome.FAILED, replaced.task(":compileJava").getOutcome());
        assertTrue(replaced.getOutput().contains(", which task ':compileJava' does not compile."),
                replaced::getOutput);
        assertTrue(replaced.getOutput().contains("/src/extra/java, in place of its own, add the generated directory"
                + " back after them, and remove that source from task ':compileJava'"), replaced::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");
    }

    /**
     * A source the compilation task is given in place of the source set's is refused before the generator runs, and
     * the refusal's fix builds as the refusal gives it: the source set takes the directories the task compiled, in
     * place of its own, the generated directory is added back after them, and the task's source goes. The legacy
     * class the replacement left out stays out. The fix also builds when the task was given no source at all, so
     * that the source set keeps no directory of its own.
     */
    @Test
    void refusesASourcePutInPlaceOfTheSourceSetsWithAFixThatBuilds() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.writeSources(Map.of("legacy.Old", """
                package legacy;

                /** A class only the source set holds. */
                public final class Old {

                    private Old() {
                    }
                }
                """));
        for (Map.Entry<String, String> source : PluginFixture.domainSources().entrySet()) {
            fixture.writeFile("src/other/java/" + source.getKey().replace('.', '/') + ".java", source.getValue());
        }
        fixture.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    setSource(fileTree("src/other/java"))
                }
                """);

        BuildResult refused = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, refused.task(":generateStateGraphApi").getOutcome());
        assertTrue(refused.getOutput().contains("legacy/Old.java, which task ':compileJava' does not compile"),
                refused::getOutput);
        Matcher remedy = Pattern.compile("Kotlin DSL: (.+)").matcher(refused.getOutput());
        assertTrue(remedy.find(), refused::getOutput);
        assertTrue(remedy.group(1).contains("/src/other/java"), remedy.group(1));
        fixture.replaceInBuildScript("setSource(fileTree(\"src/other/java\"))", "");
        fixture.appendToBuildScript("sourceSets.named(\"main\") {\n    " + remedy.group(1).strip() + "\n}\n");

        BuildResult fixed = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, fixed.task(":compileJava").getOutcome());
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
        assertFalse(fixture.jarContains("legacy/Old.class"));
        assertTrue(fixture.inventoryIsSealed("main"));

        PluginFixture nothing = PluginFixture.basic(
                Files.createDirectories(tempDirectory.resolve("nothing")), FIXTURE_REPOSITORY);
        nothing.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    setSource(files())
                }
                """);

        BuildResult refusedNothing = nothing.runFailing("jar");

        Matcher noDirectory = Pattern.compile("Kotlin DSL: (.+)").matcher(refusedNothing.getOutput());
        assertTrue(noDirectory.find(), refusedNothing::getOutput);
        nothing.replaceInBuildScript("setSource(files())", "");
        nothing.appendToBuildScript("sourceSets.named(\"main\") {\n    " + noDirectory.group(1).strip() + "\n}\n");

        BuildResult fixedNothing = nothing.run("jar");

        assertEquals(TaskOutcome.SUCCESS, fixedNothing.task(":compileJava").getOutcome());
        assertFalse(nothing.jarContains("fx/OrdersGraph.class"));
        assertTrue(nothing.inventoryIsSealed("main"));
    }

    /**
     * A location a build sets on a generation task wins whenever the build sets it: a block that configures
     * every generation task before a source set even exists still moves that source set's generated sources,
     * its manifest, its request and the inventory its compilation seals.
     */
    @Test
    void honorsLocationsABuildSetsBeforeTheSourceSetExists() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        for (Map.Entry<String, String> source : testDomain().entrySet()) {
            fixture.writeFile("src/extra/java/" + source.getKey().replace('.', '/') + ".java", source.getValue());
        }
        fixture.appendToBuildScript("""
                tasks.withType<GenerateGraphApi>().configureEach {
                    sourcesOutput = layout.buildDirectory.dir("custom/generated/" + name)
                    workDirectory = layout.buildDirectory.dir("custom/work/" + name)
                }
                sourceSets.create("extra")
                dependencies {
                    "extraImplementation"("io.github.graphmaquina:graphmaquina-core:%1$s")
                    "extraAnnotationProcessor"("io.github.graphmaquina:graphmaquina-processor:%1$s")
                }
                """.formatted(PluginFixture.version()));

        BuildResult result = fixture.run("extraClasses");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileExtraJava").getOutcome());
        assertTrue(fixture.exists("build/custom/generated/generateExtraStateGraphApi/fxt/TicketsGraph.java"));
        assertTrue(fixture.exists("build/custom/work/generateExtraStateGraphApi/annotation-manifest.json"));
        assertTrue(fixture.exists("build/custom/work/generateExtraStateGraphApi/request.json"));
        assertTrue(fixture.exists("build/custom/work/generateExtraStateGraphApi/owned-outputs.json"));
        assertFalse(fixture.exists("build/graphmaquina/extra"));
        assertFalse(fixture.exists("build/generated/sources/graphmaquina/extra"));
    }

    /**
     * What the generation task only names stays the compiler's or the generator's even when a build sets it
     * before the source set exists, which is before the plugin configured that source set's task: the value is
     * refused then too, instead of being silently replaced.
     */
    @Test
    void refusesADerivedLocationSetBeforeTheSourceSetExists() throws IOException {
        for (String property : List.of("annotationManifest", "classesOutput", "processorSourcesOutput")) {
            PluginFixture fixture = PluginFixture.basic(
                    Files.createDirectories(tempDirectory.resolve(property)), FIXTURE_REPOSITORY);
            String location = property.equals("annotationManifest")
                    ? "layout.buildDirectory.file(\"custom/annotation-manifest.json\")"
                    : "layout.buildDirectory.dir(\"custom/" + property + "\")";
            fixture.appendToBuildScript("""
                    tasks.withType<GenerateGraphApi>()
                            .matching { it.name == "generateExtraStateGraphApi" }
                            .configureEach {
                                %s = %s
                            }
                    sourceSets.create("extra")
                    """.formatted(property, location));

            BuildResult result = fixture.runFailing("generateExtraStateGraphApi");

            assertTrue(result.getOutput().contains(
                    "task ':generateExtraStateGraphApi' property '" + property + "' cannot be changed"),
                    result::getOutput);
        }
    }

    /**
     * A source set takes part when, and only when, its processor configuration declares the StateGraph
     * processor, so a declaration the build removes again no longer counts: a project that opts out of the
     * processor a shared convention declared for it is left alone, with no generation, no manifest argument
     * for a processor that is not there and no lifecycle around javac.
     */
    @Test
    void aSourceSetWhoseProcessorWasRemovedTakesNoPart() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.removeAllStateGraphDeclarations();
        fixture.appendToBuildScript("""
                configurations.named("annotationProcessor") {
                    dependencies.removeIf {
                        it.group == "io.github.graphmaquina" && it.name == "graphmaquina-processor"
                    }
                }
                """);

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        assertNull(result.task(":generateStateGraphApi"), "no generation is wired into that compilation");
        assertFalse(fixture.exists("build/graphmaquina/main"), "the main source set owns no work directory");
        assertTrue(fixture.jarContains("fx/Unrelated.class"));
    }

    /**
     * Whether a source set takes part is settled when the task graph is built, so a processor a
     * {@code withDependencies} action only contributes once the configuration is resolved — while javac's
     * inputs are fingerprinted — cannot switch the compilation's lifecycle on halfway through the build, after
     * the generation was left out of the graph.
     */
    @Test
    void aProcessorAddedWhileResolvingDoesNotChangeParticipationMidBuild() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.removeAllStateGraphDeclarations();
        fixture.replaceInBuildScript("annotationProcessor(\"io.github.graphmaquina:graphmaquina-processor:"
                + PluginFixture.version() + "\")", "");
        fixture.appendToBuildScript("""
                configurations.named("annotationProcessor") {
                    withDependencies {
                        add(project.dependencies.create("io.github.graphmaquina:graphmaquina-processor:%s"))
                    }
                }
                """.formatted(PluginFixture.version()));

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        assertNull(result.task(":generateStateGraphApi"), "no generation is wired into that compilation");
        assertFalse(fixture.exists("build/graphmaquina/main"), "no lifecycle step ran for that compilation");
        assertTrue(fixture.jarContains("fx/Unrelated.class"));
    }

    /**
     * A compilation that takes part cannot run its lifecycle without the request its generation writes, and
     * says so instead of handing the generator a file that is not there.
     */
    @Test
    void aCompilationWhoseGenerationDidNotRunSaysSo() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);

        BuildResult result = fixture.runFailing("compileJava", "-x", "generateStateGraphApi");

        assertEquals(TaskOutcome.FAILED, result.task(":compileJava").getOutcome());
        assertTrue(result.getOutput().contains(
                "does not exist, so 'prepare-compile' cannot run for task ':compileJava'"), result::getOutput);
    }

    /**
     * The generation task's own locations are the ones the build uses: moving where it writes the vocabulary
     * moves the source root javac compiles, and moving its work directory moves the manifest javac is pointed
     * at and the inventory the compilation seals.
     */
    @Test
    void followsTheLocationsTheGenerationTaskWasGiven() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.appendToBuildScript("""
                tasks.named<GenerateGraphApi>("generateStateGraphApi") {
                    sourcesOutput = layout.buildDirectory.dir("custom/generated")
                    workDirectory = layout.buildDirectory.dir("custom/work")
                }
                """);

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        assertTrue(fixture.exists("build/custom/generated/fx/OrdersGraph.java"));
        assertTrue(fixture.exists("build/custom/work/annotation-manifest.json"));
        assertTrue(fixture.exists("build/custom/work/owned-outputs.json"));
        assertFalse(fixture.exists("build/custom/work/owned-outputs.pending.json"));
        assertFalse(fixture.exists("build/generated/sources/graphmaquina/main"));
        assertTrue(fixture.mainSourceRoots().stream()
                .anyMatch((String root) -> root.endsWith("/build/custom/generated")));
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
        assertTrue(fixture.jarContains(EXPORTS_ENTRY));
    }

    /**
     * What the generation task only names is not its to move: the class output and the processor's source
     * output belong to javac, and the generator writes the annotation manifest into the work directory. A
     * build script that sets one of them on the generation task is refused, instead of a request that names
     * one place while javac uses another.
     */
    @Test
    void refusesToMoveWhatTheGenerationOnlyNames() throws IOException {
        for (String property : List.of("annotationManifest", "classesOutput", "processorSourcesOutput")) {
            PluginFixture fixture = PluginFixture.basic(
                    Files.createDirectories(tempDirectory.resolve(property)), FIXTURE_REPOSITORY);
            String location = property.equals("annotationManifest")
                    ? "layout.buildDirectory.file(\"custom/annotation-manifest.json\")"
                    : "layout.buildDirectory.dir(\"custom/" + property + "\")";
            fixture.appendToBuildScript("""
                    tasks.named<GenerateGraphApi>("generateStateGraphApi") {
                        %s = %s
                    }
                    """.formatted(property, location));

            BuildResult result = fixture.runFailing("jar");

            assertTrue(result.getOutput().contains("property '" + property + "' cannot be changed"),
                    result::getOutput);
        }
    }

    /**
     * The identity of a compilation is the group the build script settles on, even when something realizes the
     * generation task before the script sets that group. The same early block reads the source set's
     * directories before the dependencies block declares the processor, which must not settle whether the
     * source set takes part.
     */
    @Test
    void readsTheGroupTheBuildScriptSettlesOn() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.replaceInBuildScript("group = \"fx.build\"", """
                tasks.withType<GenerateGraphApi> {
                    group = "graphmaquina"
                }
                println("EARLY_MAIN_SOURCE_ROOTS=" + sourceSets.getByName("main").java.srcDirs.size)

                group = "fx.build"
                """);

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateStateGraphApi").getOutcome());
        assertEquals("fx.build:fixture:main", fixture.request("main").get("compilationId").getAsString());
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
    }

    /**
     * A generator that completes may still have written something on its error stream — a JVM warning, or a
     * warning of its own — and the build log is the only place that reaches anyone.
     */
    @Test
    void logsWhatACompletedGeneratorReportedOnItsErrorStream() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.replaceGeneratorWithOneThatReports("GENERATOR-STDERR-PROBE");

        BuildResult result = fixture.run("generateStateGraphApi");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateStateGraphApi").getOutcome());
        assertTrue(result.getOutput().contains(
                "the StateGraph generator reported while running 'generate': GENERATOR-STDERR-PROBE"),
                result::getOutput);
    }

    /**
     * The lifecycle completes only what javac wrote, so a compilation that can end without its classes is
     * refused before the preparation starts, which leaves no pending inventory, and so before javac runs:
     * {@code failOnError = false} lets a rejected compilation succeed, and {@code -proc:only} runs the processors
     * without writing a class at all.
     */
    @Test
    void refusesACompilationThatCanEndWithoutItsClasses() throws IOException {
        PluginFixture lenient = PluginFixture.basic(
                Files.createDirectories(tempDirectory.resolve("lenient")), FIXTURE_REPOSITORY);
        Map<String, String> broken = PluginFixture.domainSources();
        broken.put("fx.Broken", """
                package fx;

                /** A declaration javac refuses, so the compilation of this fixture fails. */
                public final class Broken {

                    private final int count = "not a number";
                }
                """);
        lenient.writeSources(broken);
        lenient.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    options.isFailOnError = false
                }
                """);

        BuildResult refusedLenient = lenient.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, refusedLenient.task(":compileJava").getOutcome());
        assertTrue(refusedLenient.getOutput().contains("cannot run with options.failOnError = false"),
                refusedLenient::getOutput);
        assertFalse(refusedLenient.getOutput().contains("-g:none"), refusedLenient::getOutput);
        assertFalse(refusedLenient.getOutput().contains("incompatible types"), "javac never ran");
        assertFalse(lenient.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        PluginFixture processorsOnly = PluginFixture.basic(
                Files.createDirectories(tempDirectory.resolve("processors-only")), FIXTURE_REPOSITORY);
        processorsOnly.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    options.compilerArgs.add("-proc:only")
                }
                """);

        BuildResult refusedProcessorsOnly = processorsOnly.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, refusedProcessorsOnly.task(":compileJava").getOutcome());
        assertTrue(refusedProcessorsOnly.getOutput().contains("cannot run with -proc:only"),
                refusedProcessorsOnly::getOutput);
        assertFalse(refusedProcessorsOnly.getOutput().contains("-g:none"), refusedProcessorsOnly::getOutput);
        assertFalse(processorsOnly.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");
    }

    /**
     * javac runs no annotation processor under {@code -proc:none}, and given a {@code -processor} list it runs only
     * the processors the last such list names. A compilation that would run without the StateGraph processor
     * either way is refused before the preparation starts, so it leaves no pending inventory and javac never runs;
     * a list that names the StateGraph processor builds and publishes the index the first build published.
     */
    @Test
    void refusesACompilationThatRunsWithoutTheStateGraphProcessor() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.run("jar");
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        fixture.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    options.compilerArgs.addAll(listOf("-proc:none"))
                }
                """);

        BuildResult off = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, off.task(":compileJava").getOutcome());
        assertTrue(off.getOutput().contains("cannot run with -proc:none"), off::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");
        assertFalse(off.getOutput().contains("complete-compile"), off::getOutput);

        String lombokOnly = LOMBOK_PROCESSORS.replace("$", "\\$");
        fixture.replaceInBuildScript("listOf(\"-proc:none\")", "listOf(\"-processor\", \"" + lombokOnly + "\")");

        BuildResult unlisted = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, unlisted.task(":compileJava").getOutcome());
        assertTrue(unlisted.getOutput().contains("cannot run with -processor '" + LOMBOK_PROCESSORS
                + "', which leaves out " + STATEGRAPH_PROCESSOR), unlisted::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");
        assertFalse(unlisted.getOutput().contains("No processor claimed"), "javac never ran");

        fixture.replaceInBuildScript(lombokOnly, lombokOnly + "," + STATEGRAPH_PROCESSOR);

        BuildResult listed = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, listed.task(":compileJava").getOutcome());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));
    }

    /**
     * Gradle gives javac a {@code -proc:none} of its own when the compilation's processor path is empty or null, so
     * javac would run no annotation processor and the StateGraph processor would write no index for the completion
     * step to publish: such a compilation is refused before the preparation starts. A later {@code -proc:full} changes
     * nothing for Gradle's own Java compiler, in process or in its compiler daemon, which runs only the processors of
     * the processor path, so it is refused too. Only a javac Gradle starts from the command line takes that later
     * {@code -proc:full} and looks for processors on the class path, so with the processors there it builds.
     */
    @Test
    void refusesAnEmptyProcessorPath() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    options.annotationProcessorPath = files()
                }
                """);

        BuildResult refused = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, refused.task(":compileJava").getOutcome());
        assertTrue(refused.getOutput().contains("cannot run with an options.annotationProcessorPath that is empty or"
                + " null: Gradle then gives javac -proc:none"), refused::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        fixture.replaceInBuildScript("options.annotationProcessorPath = files()",
                "options.annotationProcessorPath = files()\n    options.compilerArgs.add(\"-proc:full\")");

        BuildResult inProcess = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, inProcess.task(":compileJava").getOutcome());
        assertTrue(inProcess.getOutput().contains("cannot run with an options.annotationProcessorPath that is empty or"
                + " null: Gradle's Java compiler, in process or in its compiler daemon, runs only the annotation"
                + " processors of options.annotationProcessorPath"), inProcess::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        fixture.replaceInBuildScript("options.compilerArgs.add(\"-proc:full\")", """
                options.compilerArgs.add("-proc:full")
                    classpath += configurations.annotationProcessor.get()
                    options.isFork = true
                    options.forkOptions.javaHome = javaCompiler.get().metadata.installationPath.asFile""");

        BuildResult commandLine = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, commandLine.task(":compileJava").getOutcome());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
    }

    /**
     * javac takes the last {@code -proc:} option, and Gradle's Java compiler takes the last {@code -processor} list,
     * splits it on every comma, keeping empty names, and loads each name exactly as written; the preparation reads
     * both the same way. {@code -proc:none} followed by {@code -proc:full} builds, while {@code -proc:full} followed
     * by {@code -proc:none}, a {@code -processor} list that names the StateGraph processor followed by one that does
     * not, and a list holding a name with a space in it or an empty name after its last comma, neither of them a
     * valid qualified class name, are refused before the preparation starts.
     */
    @Test
    void readsTheProcessingOptionsTheWayTheCompilationTakesThem() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        String lombokOnly = LOMBOK_PROCESSORS.replace("$", "\\$");
        fixture.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    options.compilerArgs.addAll(listOf("-proc:none", "-proc:full"))
                }
                """);

        BuildResult full = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, full.task(":compileJava").getOutcome());
        assertTrue(fixture.inventoryIsSealed("main"));

        fixture.replaceInBuildScript("listOf(\"-proc:none\", \"-proc:full\")",
                "listOf(\"-proc:full\", \"-proc:none\")");

        BuildResult none = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, none.task(":compileJava").getOutcome());
        assertTrue(none.getOutput().contains("cannot run with -proc:none"), none::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        fixture.replaceInBuildScript("listOf(\"-proc:full\", \"-proc:none\")", "listOf(\"-processor\", \""
                + lombokOnly + "," + STATEGRAPH_PROCESSOR + "\", \"-processor\", \"" + lombokOnly + "\")");

        BuildResult lists = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, lists.task(":compileJava").getOutcome());
        assertTrue(lists.getOutput().contains("cannot run with -processor '" + LOMBOK_PROCESSORS
                + "', which leaves out " + STATEGRAPH_PROCESSOR), lists::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        String spaced = LOMBOK_PROCESSORS.substring(0, LOMBOK_PROCESSORS.indexOf(',')) + ", " + STATEGRAPH_PROCESSOR;
        fixture.replaceInBuildScript("listOf(\"-processor\", \"" + lombokOnly + "," + STATEGRAPH_PROCESSOR
                + "\", \"-processor\", \"" + lombokOnly + "\")",
                "listOf(\"-processor\", \"" + spaced.replace("$", "\\$") + "\")");

        BuildResult spaces = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, spaces.task(":compileJava").getOutcome());
        assertTrue(spaces.getOutput().contains("cannot run with -processor '" + spaced + "', whose name ' "
                + STATEGRAPH_PROCESSOR + "' is not a valid qualified class name"), spaces::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        fixture.replaceInBuildScript(spaced.replace("$", "\\$"), lombokOnly + "," + STATEGRAPH_PROCESSOR + ",");

        BuildResult trailing = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, trailing.task(":compileJava").getOutcome());
        assertTrue(trailing.getOutput().contains("cannot run with -processor '" + LOMBOK_PROCESSORS + ","
                + STATEGRAPH_PROCESSOR + ",', whose name '' is not a valid qualified class name"),
                trailing::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");
    }

    /**
     * javac leaves the {@code SourceFile} attribute out of every class file once it is given {@code -g:none} or a
     * {@code -g:} list and no {@code -g:} list it is given names {@code source}, and the completion step links each
     * class file to its source through that attribute. Gradle passes {@code options.debug = false} as {@code -g:none}
     * and {@code options.debugOptions.debugLevel} as a {@code -g:} list, ahead of the compiler arguments. Each of
     * those settings, and such a compiler argument, is refused before the preparation starts, so javac never runs; a
     * {@code -g:} list that names {@code source} anywhere keeps the attribute, so it builds even when {@code -g:none}
     * and a {@code -g:} list without {@code source} follow it, and a {@code -g:} option javac rejects is left to
     * javac.
     */
    @Test
    void refusesADebugSettingThatDropsTheSourceFileAttribute() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    options.isDebug = false
                }
                """);

        BuildResult noDebug = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, noDebug.task(":compileJava").getOutcome());
        assertTrue(noDebug.getOutput().contains("cannot run with options.debug = false: javac then leaves the"
                + " SourceFile attribute out of every class file"), noDebug::getOutput);
        assertFalse(noDebug.getOutput().contains("[SG602]"), noDebug::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        fixture.replaceInBuildScript("options.isDebug = false",
                "options.debugOptions.debugLevel = \"lines,vars\"");

        BuildResult level = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, level.task(":compileJava").getOutcome());
        assertTrue(level.getOutput().contains(
                "cannot run with options.debugOptions.debugLevel = \"lines,vars\": javac then leaves"),
                level::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        fixture.replaceInBuildScript("options.debugOptions.debugLevel = \"lines,vars\"",
                "options.compilerArgs.add(\"-g:lines,vars\")");

        BuildResult argument = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, argument.task(":compileJava").getOutcome());
        assertTrue(argument.getOutput().contains(
                "cannot run with the compiler argument -g:lines,vars: javac then leaves"), argument::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        fixture.replaceInBuildScript("options.compilerArgs.add(\"-g:lines,vars\")",
                "options.compilerArgs.add(\"-g:foo\")");

        BuildResult rejected = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, rejected.task(":compileJava").getOutcome());
        assertTrue(rejected.getOutput().contains("invalid flag: -g:foo"), rejected::getOutput);
        assertFalse(rejected.getOutput().contains("SourceFile attribute"), rejected::getOutput);

        fixture.replaceInBuildScript("options.compilerArgs.add(\"-g:foo\")",
                "options.debugOptions.debugLevel = \"source\"\n"
                        + "    options.compilerArgs.addAll(listOf(\"-g:none\", \"-g:lines,vars\"))");

        BuildResult kept = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, kept.task(":compileJava").getOutcome());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
    }

    /**
     * The generated directory is a source root the plugin adds to the source set when the source set is
     * created, so a build script that later replaces the source set's directories drops it. The compilation
     * then names that replacement, instead of javac failing on every generated type it cannot find, and the
     * remedy it prints works as printed, even for a source set whose include pattern leaves out the package of
     * the compilation carrier.
     */
    @Test
    void refusesASourceSetWhoseDirectoriesDroppedTheGeneratedRoot() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.appendToBuildScript("""
                sourceSets.named("main") {
                    java.setSrcDirs(listOf("src/main/java"))
                }
                """);

        BuildResult result = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, result.task(":compileJava").getOutcome());
        assertTrue(result.getOutput().contains("does not compile"), result::getOutput);
        assertTrue(result.getOutput().contains("java.setSrcDirs"), result::getOutput);
        assertFalse(result.getOutput().contains("package OrdersGraph does not exist"), result::getOutput);

        Matcher remedy = Pattern.compile("Kotlin DSL: (.+)").matcher(result.getOutput());
        assertTrue(remedy.find(), result::getOutput);
        fixture.appendToBuildScript("sourceSets.named(\"main\") {\n"
                + "    java { include(\"fx/**\") }\n"
                + "    " + remedy.group(1).strip() + "\n"
                + "}\n");

        BuildResult remedied = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, remedied.task(":compileJava").getOutcome());
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
        assertTrue(fixture.inventoryIsSealed("main"));
    }

    /**
     * javac reads the sources of a project in the encoding its compilation declares and at the release it
     * compiles for, so the generator is told both: an accented identifier of a project kept in ISO-8859-1 reaches
     * the generated vocabulary intact, and javac reads that vocabulary back in the same encoding.
     */
    @Test
    void passesTheEncodingAndTheReleaseOfTheCompilationToTheGenerator() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.replaceInBuildScript("options.encoding = \"UTF-8\"", "options.encoding = \"ISO-8859-1\"");
        fixture.writeSources(accentedDomain(), StandardCharsets.ISO_8859_1);

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        JsonObject request = fixture.request("main");
        assertEquals(21, request.get("release").getAsInt(), request::toString);
        assertEquals("ISO-8859-1", request.get("encoding").getAsString(), request::toString);
        assertTrue(fixture.localExports().contains("fx.ActionsRefs.ApproveRésumé"), fixture.localExports()::toString);
        assertTrue(fixture.jarContains("fx/ActionsRefs$ApproveRésumé.class"));
    }

    /**
     * A compilation that names no encoding gives the request none, and the generator then reads UTF-8. This one
     * names no release either, so the request carries 21, the language version of the fixture's toolchain, which a
     * constant 21 would match too; {@code passesTheReleaseOfTheToolchainWhenTheCompilationNamesNone} tells the two
     * apart.
     */
    @Test
    void passesNoEncodingWhenTheCompilationNamesNone() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.replaceInBuildScript("options.encoding = \"UTF-8\"\n", "");
        fixture.replaceInBuildScript("options.release = 21\n", "");

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        JsonObject request = fixture.request("main");
        assertEquals(21, request.get("release").getAsInt(), request::toString);
        assertFalse(request.has("encoding"), request::toString);
    }

    /**
     * A compilation that names no release compiles against the whole API of its toolchain's JDK, so the request
     * carries the language version of that toolchain: with a toolchain above Java 21, that version and not 21. The
     * test is skipped when Gradle detects no JDK above Java 21.
     */
    @Test
    void passesTheReleaseOfTheToolchainWhenTheCompilationNamesNone() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        Optional<Integer> later = toolchains(fixture).detected().stream()
                .filter((Integer version) -> version > 21)
                .findFirst();
        assumeTrue(later.isPresent(), "Gradle detects no JDK above Java 21");
        fixture.replaceInBuildScript("languageVersion = JavaLanguageVersion.of(21)",
                "languageVersion = JavaLanguageVersion.of(" + later.get() + ")");
        fixture.replaceInBuildScript("options.release = 21\n", "");

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        JsonObject request = fixture.request("main");
        assertEquals(later.get().intValue(), request.get("release").getAsInt(), request::toString);
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
    }

    /**
     * The generator is told the encoding javac reads the sources in. Gradle passes {@code options.encoding} as an
     * {@code -encoding} of its own ahead of the compiler arguments, and javac takes the last {@code -encoding} it is
     * given, but Gradle's own Java compiler, in process or in a compiler daemon, opens its file manager in
     * {@code options.encoding}, which then wins. So a project kept in ISO-8859-1 builds with its accented identifier
     * intact when the last {@code -encoding} of its compiler arguments names that encoding and it sets no
     * {@code options.encoding}, and, with the javac Gradle starts from the command line for
     * {@code forkOptions.javaHome}, when the last {@code -encoding} of the arguments names it, whatever
     * {@code options.encoding} says. In process, javac still writes the sources an annotation processor generates in
     * the last {@code -encoding}, and reads them back in {@code options.encoding}, so a last {@code -encoding} that
     * names another charset than {@code options.encoding} is refused before the preparation starts, in process and in
     * the compiler daemon alike, while one that names the same charset under another name, {@code latin1}, builds.
     */
    @Test
    void passesTheEncodingJavacReadsTheSourcesIn() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.replaceInBuildScript("options.encoding = \"UTF-8\"\n", "");
        fixture.writeSources(accentedDomain(), StandardCharsets.ISO_8859_1);
        String arguments = """
                tasks.named<JavaCompile>("compileJava") {
                    options.compilerArgs.addAll(listOf("-encoding", "UTF-16", "-encoding", "ISO-8859-1"))
                }
                """;
        fixture.appendToBuildScript(arguments);

        BuildResult lastArgument = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, lastArgument.task(":compileJava").getOutcome());
        assertEquals("ISO-8859-1", fixture.request("main").get("encoding").getAsString());
        assertTrue(fixture.localExports().contains("fx.ActionsRefs.ApproveRésumé"), fixture.localExports()::toString);
        assertTrue(fixture.jarContains("fx/ActionsRefs$ApproveRésumé.class"));

        String alias = """
                tasks.named<JavaCompile>("compileJava") {
                    options.encoding = "ISO-8859-1"
                    options.compilerArgs.addAll(listOf("-encoding", "latin1"))
                }
                """;
        fixture.replaceInBuildScript(arguments, alias);

        BuildResult sameCharset = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, sameCharset.task(":compileJava").getOutcome());
        assertEquals("ISO-8859-1", fixture.request("main").get("encoding").getAsString());
        assertTrue(fixture.jarContains("fx/ActionsRefs$ApproveRésumé.class"));

        String contradicting = alias.replace("\"latin1\"", "\"UTF-8\"");
        fixture.replaceInBuildScript(alias, contradicting);

        BuildResult refused = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, refused.task(":compileJava").getOutcome());
        assertTrue(refused.getOutput().contains("cannot run with options.encoding = \"ISO-8859-1\" and the compiler"
                + " argument -encoding UTF-8:"), refused::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        String daemon = contradicting.replace("options.encoding = \"ISO-8859-1\"",
                "options.encoding = \"ISO-8859-1\"\n    options.isFork = true");
        fixture.replaceInBuildScript(contradicting, daemon);

        BuildResult refusedInDaemon = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, refusedInDaemon.task(":compileJava").getOutcome());
        assertTrue(refusedInDaemon.getOutput().contains("cannot run with options.encoding = \"ISO-8859-1\" and the"
                + " compiler argument -encoding UTF-8:"), refusedInDaemon::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        fixture.replaceInBuildScript(daemon, """
                tasks.named<JavaCompile>("compileJava") {
                    options.encoding = "UTF-8"
                    options.compilerArgs.addAll(listOf("-encoding", "ISO-8859-1"))
                    options.isFork = true
                    options.forkOptions.javaHome = javaCompiler.get().metadata.installationPath.asFile
                }
                """);

        BuildResult commandLine = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, commandLine.task(":compileJava").getOutcome());
        assertEquals("ISO-8859-1", fixture.request("main").get("encoding").getAsString());
        assertTrue(fixture.localExports().contains("fx.ActionsRefs.ApproveRésumé"), fixture.localExports()::toString);
        assertTrue(fixture.jarContains("fx/ActionsRefs$ApproveRésumé.class"));
    }

    /**
     * The generation reads the release and the encoding from the compilation's options and
     * {@code options.compilerArgs} alone, never from its argument providers, which run only with the compilation: a
     * provider that reads a file another task writes does not run when the generation runs on its own.
     */
    @Test
    void aCompilerArgumentProviderDoesNotRunForTheGenerationAlone() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.appendToBuildScript(FILE_ARGUMENTS);

        BuildResult result = fixture.run("generateStateGraphApi");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateStateGraphApi").getOutcome());
        assertFalse(result.getOutput().contains("FILE_ARGUMENTS_READ"), result::getOutput);
    }

    /**
     * An argument provider that reads a file another task writes runs with the compilation, once that task wrote it,
     * even when that task runs after the generation; the generation never runs it.
     */
    @Test
    void aCompilerArgumentProviderRunsOnlyWithTheCompilation() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.appendToBuildScript(FILE_ARGUMENTS);

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateStateGraphApi").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        assertTrue(result.getOutput().contains("FILE_ARGUMENTS_READ"), result::getOutput);
        assertTrue(fixture.inventoryIsSealed("main"));
    }

    /**
     * Storing the configuration cache runs no argument provider of the compilation either: the generation's release
     * and encoding are stored without one, so a provider that reads a file another task writes runs only with the
     * compilation, and the next build reuses the entry.
     */
    @Test
    void aCompilerArgumentProviderDoesNotRunWhenTheConfigurationCacheIsStored() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.appendToBuildScript(FILE_ARGUMENTS);

        BuildResult result = fixture.run("jar", "--configuration-cache");

        assertTrue(result.getOutput().contains("Configuration cache entry stored."), result::getOutput);
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        assertTrue(fixture.inventoryIsSealed("main"));

        BuildResult reused = fixture.run("jar", "--configuration-cache");

        assertTrue(reused.getOutput().contains("Reusing configuration cache."), reused::getOutput);
        assertEquals(TaskOutcome.UP_TO_DATE, reused.task(":compileJava").getOutcome());
    }

    /**
     * The argument providers of a compilation run just before javac, so the preparation, which runs then, derives the
     * release and the encoding javac takes from all its compiler arguments, and refuses a compilation whose providers
     * change either from the one the generation was given: a provider's {@code --release=17} beside
     * {@code options.release = 21}, and a provider's {@code -encoding ISO-8859-1} where nothing else names an encoding,
     * so the generator reads UTF-8. A provider that repeats the effective value changes nothing and builds, and one
     * whose last {@code --release} javac refuses is left to javac, which names it.
     */
    @Test
    void refusesArgumentProvidersThatChangeTheReleaseOrTheEncoding() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.appendToBuildScript(FIXED_ARGUMENTS);

        BuildResult release = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, release.task(":compileJava").getOutcome());
        assertTrue(release.getOutput().contains("cannot run with the compiler arguments its argument providers give:"
                + " they make javac compile at release 17 instead of release 21, the one options.release gives and"
                + " the generation reads"), release::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        fixture.replaceInBuildScript("listOf(\"--release=17\")", "listOf(\"--release=21\")");

        BuildResult sameRelease = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, sameRelease.task(":compileJava").getOutcome());

        fixture.replaceInBuildScript("options.encoding = \"UTF-8\"\n", "");
        fixture.replaceInBuildScript("listOf(\"--release=21\")", "listOf(\"-encoding\", \"ISO-8859-1\")");

        BuildResult encoding = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, encoding.task(":compileJava").getOutcome());
        assertTrue(encoding.getOutput().contains("cannot run with the compiler arguments its argument providers give:"
                + " they make javac read the sources in ISO-8859-1 instead of UTF-8, which the generator reads when"
                + " options.encoding and options.compilerArgs name no encoding"), encoding::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        fixture.replaceInBuildScript("listOf(\"-encoding\", \"ISO-8859-1\")", "listOf(\"-encoding\", \"UTF-8\")");

        BuildResult sameEncoding = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, sameEncoding.task(":compileJava").getOutcome());
        assertTrue(fixture.inventoryIsSealed("main"));

        fixture.replaceInBuildScript("listOf(\"-encoding\", \"UTF-8\")", "listOf(\"--release=1.8\")");
        fixture.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    options.compilerArgs.add("--release=17")
                }
                """);

        BuildResult refusedRelease = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, refusedRelease.task(":compileJava").getOutcome());
        assertTrue(refusedRelease.getOutput().contains("release version 1.8 not supported"),
                refusedRelease::getOutput);
        assertFalse(refusedRelease.getOutput().contains("its argument providers give"), refusedRelease::getOutput);
    }

    /**
     * A refusal of argument providers that change the release names where the release the generation reads comes
     * from: the language version of the compilation's toolchain when neither {@code options.release} nor
     * {@code options.compilerArgs} gives one, else the last {@code --release} of {@code options.compilerArgs}.
     */
    @Test
    void aReleaseRefusalNamesWhereTheGenerationsReleaseComesFrom() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.replaceInBuildScript("options.release = 21\n", "");
        fixture.appendToBuildScript(FIXED_ARGUMENTS);

        BuildResult toolchain = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, toolchain.task(":compileJava").getOutcome());
        assertTrue(toolchain.getOutput().contains("they make javac compile at release 17 instead of release 21, the"
                + " language version of the compilation's toolchain, which the generation reads when options.release"
                + " and options.compilerArgs give none"), toolchain::getOutput);

        fixture.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    options.compilerArgs.addAll(listOf("--release", "21"))
                }
                """);

        BuildResult arguments = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, arguments.task(":compileJava").getOutcome());
        assertTrue(arguments.getOutput().contains("they make javac compile at release 17 instead of release 21, the"
                + " one options.compilerArgs give and the generation reads"), arguments::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");
    }

    /**
     * javac takes the last {@code --release} it is given, spelled {@code --release N} or {@code --release=N}, and
     * Gradle passes {@code options.release} ahead of the compiler arguments, so the last {@code --release} among those
     * arguments is the release javac compiles at; the generator is told that one, whether the compilation also sets
     * {@code options.release} or not. A last value that is not a plain decimal number, which javac refuses, is passed
     * over, so the generation still runs, at the language version of the toolchain. Only the generation runs: javac
     * would compile the domain at release 17 against a library built for 21.
     */
    @Test
    void passesTheReleaseJavacTakesFromTheCompilerArguments() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    options.compilerArgs.addAll(listOf("--release=17"))
                }
                """);

        fixture.run("generateStateGraphApi");

        JsonObject overriding = fixture.request("main");
        assertEquals(17, overriding.get("release").getAsInt(), overriding::toString);

        fixture.replaceInBuildScript("options.release = 21\n", "");
        fixture.replaceInBuildScript("listOf(\"--release=17\")",
                "listOf(\"--release\", \"21\", \"--release\", \"17\")");

        fixture.run("generateStateGraphApi");

        JsonObject last = fixture.request("main");
        assertEquals(17, last.get("release").getAsInt(), last::toString);

        fixture.replaceInBuildScript("listOf(\"--release\", \"21\", \"--release\", \"17\")",
                "listOf(\"--release\", \"21\", \"--release\", \"1.8\")");

        fixture.run("generateStateGraphApi");

        JsonObject refused = fixture.request("main");
        assertEquals(21, refused.get("release").getAsInt(), refused::toString);
    }

    /**
     * Gradle leaves out of every source directory the files the Ant-style default excludes name, such as the
     * AppleDouble companion {@code ._Orders.java} a copy from macOS leaves beside a source, or anything inside
     * the directory of a version control system. The generator leaves them out too, whether the source set hands
     * it a filtered directory file by file or a whole directory to walk.
     */
    @Test
    void skipsTheFilesABuildExcludesByDefault() throws IOException {
        byte[] appleDouble = {0x00, 0x05, 0x16, 0x07, 0x00, 0x02, 0x00, 0x00, 'M', 'a', 'c', ' ', 'O', 'S', ' ', 'X',
            (byte) 0xff, (byte) 0xfe, 0x00, 0x01, '{', '}', (byte) 0x80};
        for (String filter : List.of("exclude(\"fx/drafts/**\")", "")) {
            PluginFixture fixture = PluginFixture.basic(Files.createDirectories(
                    tempDirectory.resolve(filter.isEmpty() ? "whole" : "filtered")), FIXTURE_REPOSITORY);
            fixture.writeFile("src/main/java/fx/._Orders.java", appleDouble);
            fixture.writeFile("src/main/java/fx/CVS/Junk.java", appleDouble);
            if (!filter.isEmpty()) {
                fixture.appendToBuildScript("sourceSets.named(\"main\") {\n    java { " + filter + " }\n}\n");
            }

            BuildResult result = fixture.run("jar");

            assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome(), filter);
            assertTrue(fixture.jarContains("fx/OrdersGraph.class"), filter);
        }
    }

    /**
     * A source set filtered by a pattern hands the generator its files one by one. A hand-written type that only
     * shares the file name of a generated source, in a package that merely ends like the generated one's, is no
     * collision there either: a listed file stands where its package declaration puts it.
     */
    @Test
    void aHandWrittenTypeNamedLikeAGeneratedOneIsNoCollisionInAFilteredSourceSet() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        Map<String, String> sources = PluginFixture.domainSources();
        sources.put("legacy.fx.OrderRefs", """
                package legacy.fx;

                /** A hand-written type named like the generated fx.OrderRefs, in another package. */
                public final class OrderRefs {

                    private OrderRefs() {
                    }
                }
                """);
        fixture.writeSources(sources);
        fixture.appendToBuildScript("""
                sourceSets.named("main") {
                    java { exclude("fx/drafts/**") }
                }
                """);

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateStateGraphApi").getOutcome());
        List<String> requested = requestedSources(fixture.request("main"));
        assertTrue(requested.contains("legacy/fx/OrderRefs.java"), requested::toString);
        assertTrue(fixture.jarContains("fx/OrderRefs$Status.class"));
        assertTrue(fixture.jarContains("legacy/fx/OrderRefs.class"));
    }

    /**
     * A source or a pattern set on the compile task itself filters the generated sources too, and no line added
     * to the source set can undo that, so the refusal also says to remove that setting from the task.
     */
    @Test
    void refusesACompilationTaskThatFiltersOutTheGeneratedSourcesAndSaysToRemoveThatFilter() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    exclude("io/github/graphmaquina/generated/**")
                }
                """);

        BuildResult result = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, result.task(":compileJava").getOutcome());
        assertTrue(result.getOutput().contains("does not compile"), result::getOutput);
        assertTrue(result.getOutput().contains("remove that setting from task ':compileJava'"), result::getOutput);
    }

    /**
     * The request file is a convention as well: a build that sets it on every generation task before a source set
     * exists has that source set's request written there, and the compilation's lifecycle reads it from there.
     */
    @Test
    void honorsARequestFileABuildSetsBeforeTheSourceSetExists() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        for (Map.Entry<String, String> source : testDomain().entrySet()) {
            fixture.writeFile("src/extra/java/" + source.getKey().replace('.', '/') + ".java", source.getValue());
        }
        fixture.appendToBuildScript("""
                tasks.withType<GenerateGraphApi>().configureEach {
                    requestFile = layout.buildDirectory.file("custom/requests/" + name + ".json")
                }
                sourceSets.create("extra")
                dependencies {
                    "extraImplementation"("io.github.graphmaquina:graphmaquina-core:%1$s")
                    "extraAnnotationProcessor"("io.github.graphmaquina:graphmaquina-processor:%1$s")
                }
                """.formatted(PluginFixture.version()));

        BuildResult result = fixture.run("extraClasses");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileExtraJava").getOutcome());
        assertTrue(fixture.exists("build/custom/requests/generateExtraStateGraphApi.json"));
        assertFalse(fixture.exists("build/graphmaquina/extra/request.json"));
        assertTrue(fixture.exists("build/graphmaquina/extra/owned-outputs.json"));
        assertFalse(fixture.exists("build/graphmaquina/extra/owned-outputs.pending.json"));
    }

    /**
     * A launcher a build gives the generation task runs every step of that compilation: the preparation and the
     * completion around javac as well as the generation, so the three never run under two different JDKs. The
     * launcher of the test is a POSIX shell script, so the test is skipped on Windows.
     */
    @Test
    void runsEveryStepUnderTheLauncherTheGenerationTaskWasGiven() throws IOException {
        assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"),
                "the logging launcher of this test is a POSIX shell script");
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        Path log = fixture.projectDirectory().resolve("launcher.log");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        fixture.writeFile("logging-java", "#!/bin/sh\necho \"$*\" >> '" + log + "'\nexec '" + java + "' \"$@\"\n");
        if (!fixture.projectDirectory().resolve("logging-java").toFile().setExecutable(true)) {
            throw new IllegalStateException("cannot make the logging launcher executable");
        }
        fixture.appendToBuildScript("""
                val toolchainLauncher = javaToolchains.launcherFor(java.toolchain)
                val loggingJava = layout.projectDirectory.file("logging-java")
                tasks.named<GenerateGraphApi>("generateStateGraphApi") {
                    javaLauncher = toolchainLauncher.map { real ->
                        object : JavaLauncher {
                            override fun getMetadata(): JavaInstallationMetadata = real.metadata
                            override fun getExecutablePath(): RegularFile = loggingJava
                        }
                    }
                }
                """);

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        List<String> launched = Files.readAllLines(log, StandardCharsets.UTF_8);
        for (String operation : List.of("generate", "prepare-compile", "complete-compile")) {
            assertTrue(launched.stream().anyMatch((String line) -> line.contains(" " + operation + " --request ")),
                    operation + " did not run under the launcher of the generation task: " + launched);
        }
    }

    /**
     * A generator classpath a build gives the generation task runs every step of that compilation, as its launcher
     * does: the preparation and the completion around javac run over the very classpath the generation ran over.
     */
    @Test
    void runsEveryStepOverTheGeneratorClasspathTheGenerationTaskWasGiven() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        try (ZipOutputStream marker = new ZipOutputStream(
                Files.newOutputStream(fixture.projectDirectory().resolve("marker.jar")))) {
            marker.putNextEntry(new ZipEntry("marker.txt"));
            marker.closeEntry();
        }
        fixture.appendToBuildScript("""
                tasks.named<GenerateGraphApi>("generateStateGraphApi") {
                    generatorClasspath.from(files("marker.jar"))
                }
                """);

        BuildResult result = fixture.run("jar", "--info");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        for (String operation : List.of("generate", "prepare-compile", "complete-compile")) {
            List<String> launches = result.getOutput().lines()
                    .filter((String line) -> line.contains("CodegenMain " + operation + " --request"))
                    .toList();
            assertFalse(launches.isEmpty(), "no launch of " + operation + " was logged");
            assertTrue(launches.stream().allMatch((String line) -> line.contains("marker.jar")),
                    operation + " did not run over the generator classpath of the generation task: " + launches);
        }
    }

    /**
     * Whether a generation takes part and where its compilation writes are conventions of the task too, so values
     * a build sets for them before the source set exists are kept rather than silently replaced.
     */
    @Test
    void keepsTheParticipationAndTheOutputLocationsABuildSetsBeforeTheSourceSetExists() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.appendToBuildScript("""
                tasks.withType<GenerateGraphApi>()
                        .matching { it.name == "generateExtraStateGraphApi" }
                        .configureEach {
                            participates = false
                            compilationOutputLocations = "set by the build"
                        }
                sourceSets.create("extra")
                dependencies {
                    "extraImplementation"("io.github.graphmaquina:graphmaquina-core:%1$s")
                    "extraAnnotationProcessor"("io.github.graphmaquina:graphmaquina-processor:%1$s")
                }
                tasks.register("printExtraGeneration") {
                    val generation = tasks.named<GenerateGraphApi>("generateExtraStateGraphApi")
                    val participates = generation.flatMap { it.participates }
                    val locations = generation.flatMap { it.compilationOutputLocations }
                    doLast {
                        println("EXTRA_PARTICIPATES=" + participates.get())
                        println("EXTRA_LOCATIONS=" + locations.get())
                    }
                }
                """.formatted(PluginFixture.version()));

        Map<String, String> reported = report(fixture.run("printExtraGeneration"));

        assertEquals("false", reported.get("EXTRA_PARTICIPATES"), reported::toString);
        assertEquals("set by the build", reported.get("EXTRA_LOCATIONS"), reported::toString);
    }

    /**
     * The generator classpath is a convention of the generation task too: a classpath a build sets on the
     * generation task of a source set before that source set exists is the whole classpath, which the plugin's
     * default does not join afterwards, while a generation task the build leaves alone keeps that default.
     */
    @Test
    void keepsAGeneratorClasspathABuildSetsBeforeTheSourceSetExists() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.appendToBuildScript("""
                tasks.withType<GenerateGraphApi>()
                        .matching { it.name == "generateExtraStateGraphApi" }
                        .configureEach {
                            generatorClasspath.setFrom(files("set-by-the-build.jar"))
                        }
                sourceSets.create("extra")
                tasks.register("printGeneratorClasspaths") {
                    val extra = tasks.named<GenerateGraphApi>("generateExtraStateGraphApi")
                    val main = tasks.named<GenerateGraphApi>("generateStateGraphApi")
                    doLast {
                        println("EXTRA_CLASSPATH=" + extra.get().generatorClasspath.files.map { it.name })
                        println("MAIN_CLASSPATH=" + main.get().generatorClasspath.files.map { it.name })
                    }
                }
                """);

        Map<String, String> reported = report(fixture.run("printGeneratorClasspaths"));

        assertEquals("[set-by-the-build.jar]", reported.get("EXTRA_CLASSPATH"), reported::toString);
        assertTrue(reported.get("MAIN_CLASSPATH").contains("graphmaquina-codegen-" + PluginFixture.version() + ".jar"),
                reported::toString);
    }

    /**
     * The compilation carrier is tool-internal: the completion keeps its class out of the jar, and the plugin
     * keeps its source out of the sources jar and the API documentation of the source set as well, so a
     * published project never shows it.
     */
    @Test
    void keepsTheCarrierOutOfThePublishedSourcesAndDocumentation() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.appendToBuildScript("""
                java {
                    withSourcesJar()
                    withJavadocJar()
                }
                """);

        BuildResult result = fixture.run("jar", "sourcesJar", "javadocJar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":javadoc").getOutcome());
        List<String> sources = fixture.archiveEntries("build/libs/fixture-1.0.0-sources.jar");
        List<String> documentation = fixture.archiveEntries("build/libs/fixture-1.0.0-javadoc.jar");
        assertTrue(sources.contains("fx/OrdersGraph.java"), sources::toString);
        assertTrue(documentation.contains("fx/OrdersGraph.html"), documentation::toString);
        assertFalse(sources.stream().anyMatch((String entry) -> entry.startsWith(CARRIER_PACKAGE)),
                sources::toString);
        assertFalse(documentation.stream().anyMatch((String entry) -> entry.startsWith(CARRIER_PACKAGE)),
                documentation::toString);
        assertFalse(fixture.jarContainsGraphCompilationCarrier());
    }

    /** A class of {@code packageName} that does not parse, so the generator fails with {@code SG601} if it reads it. */
    private static String draft(String packageName) {
        return """
                package %s;

                /** A draft the build leaves out; it does not parse. */
                public final class Draft {

                    this is not a declaration
                }
                """.formatted(packageName);
    }

    /** The domain with its action spelled with an accent, as a project kept in ISO-8859-1 may spell it. */
    private static Map<String, String> accentedDomain() {
        Map<String, String> sources = PluginFixture.domainSources();
        sources.put("fx.Actions", """
                package fx;

                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;

                /** The action host of the graph, whose action approves a résumé. */
                public final class Actions {

                    @GraphAction
                    public ActionOutcome approveRésumé(TransitionContext<Order, Status, Transition> context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        sources.put("fx.Orders", PluginFixture.orders("Orders", "ApproveRésumé", "approveRésumé"));
        return sources;
    }

    /**
     * The sources a request lists, each relative to the {@code src/main/java} root it lies under; a source that
     * lies under no such root fails the calling test.
     *
     * @param request the parsed request
     * @return the relative paths, sorted
     */
    private static List<String> requestedSources(JsonObject request) {
        String root = "/src/main/java/";
        List<String> requested = new ArrayList<>();
        for (JsonElement source : request.getAsJsonArray("sources")) {
            String path = source.getAsString();
            int index = path.indexOf(root);
            assertTrue(index >= 0, path);
            requested.add(path.substring(index + root.length()));
        }
        requested.sort(String::compareTo);
        return requested;
    }

    /** The domain with the action and the facade renamed, so the previous names must be retired. */
    private static Map<String, String> renamed() {
        Map<String, String> sources = PluginFixture.domainSources();
        sources.remove("fx.Orders");
        sources.put("fx.Actions", """
                package fx;

                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;

                /** The action host of the graph, with its action renamed. */
                public final class Actions {

                    @GraphAction
                    public ActionOutcome accept(TransitionContext<Order, Status, Transition> context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        sources.put("fx.Requests", PluginFixture.orders("Requests", "Accept", "accept"));
        return sources;
    }

    /** A self-contained domain for the test source set, in a package of its own. */
    private static Map<String, String> testDomain() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fxt.Level", """
                package fxt;

                /** The states a ticket moves through. */
                public enum Level { OPEN, CLOSED }
                """);
        sources.put("fxt.Step", """
                package fxt;

                /** The identified transitions of the ticket graph. */
                public enum Step { RESOLVE }
                """);
        sources.put("fxt.Ticket", """
                package fxt;

                import io.github.graphmaquina.annotation.GraphProperty;

                /** The entity of the test graph, with hand-written accessors. */
                public final class Ticket {

                    @GraphProperty
                    private Level status = Level.OPEN;

                    @GraphProperty
                    public String getId() {
                        return "ticket";
                    }

                    public Level getStatus() {
                        return status;
                    }

                    public void setStatus(Level value) {
                        status = value;
                    }
                }
                """);
        sources.put("fxt.TicketActions", """
                package fxt;

                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;

                /** The action host of the test graph. */
                public final class TicketActions {

                    @GraphAction
                    public ActionOutcome resolve(TransitionContext<Ticket, Level, Step> context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        sources.put("fxt.Tickets", """
                package fxt;

                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;

                import static fxt.Level.CLOSED;
                import static fxt.Level.OPEN;
                import static fxt.Step.RESOLVE;

                /** The machine of the test source set. */
                @GenerateGraphApi
                @TicketsGraph.Definition(initial = OPEN, terminals = CLOSED,
                    state = TicketRefs.Status.class, id = TicketRefs.Id.class)
                @TicketsGraph.StatePolicy(state = OPEN, otherwise = "an open ticket is resolved")
                public interface Tickets extends Machine<Ticket, Level, Step> {

                    @TicketsGraph.Transition(id = RESOLVE, from = OPEN, to = CLOSED,
                        action = TicketActionsRefs.Resolve.class)
                    Outcome<Ticket, Level, Step> resolve(Ticket ticket);
                }
                """);
        return sources;
    }

    /**
     * The JDKs Gradle detects on this machine for the build of {@code fixture}, as its {@code javaToolchains} report
     * lists them, and the language version of the JVM the daemon of that build runs on. An installation counts as a
     * JDK when its {@code bin} directory holds {@code javac}; a runtime without one is left out.
     */
    private static Toolchains toolchains(PluginFixture fixture) {
        BuildResult result = fixture.run("javaToolchains", "printGeneratorLauncher");
        Pattern location = Pattern.compile("\\| Location:\\s+(.+)");
        Pattern version = Pattern.compile("\\| Language Version:\\s+(\\d+)");
        Set<Integer> detected = new TreeSet<>();
        Path installation = null;
        for (String line : result.getOutput().lines().toList()) {
            Matcher installed = location.matcher(line);
            Matcher language = version.matcher(line);
            if (installed.find()) {
                installation = Path.of(installed.group(1).strip());
            } else if (language.find() && installation != null) {
                int feature = Integer.parseInt(language.group(1));
                Path bin = installation.resolve("bin");
                if (feature >= 21 && (Files.isRegularFile(bin.resolve("javac"))
                        || Files.isRegularFile(bin.resolve("javac.exe")))) {
                    detected.add(feature);
                }
            }
        }
        return new Toolchains(Integer.parseInt(report(result).get("DAEMON_LANGUAGE_VERSION")),
                List.copyOf(detected));
    }

    /**
     * The JDKs a build of a fixture can select as its toolchain.
     *
     * @param daemon the language version of the JVM the daemon of the build runs on
     * @param detected the language versions, 21 or later, of the JDKs Gradle detects, those whose {@code bin}
     *     directory holds {@code javac}, in ascending order
     */
    private record Toolchains(int daemon, List<Integer> detected) {
    }

    /** The {@code KEY=value} lines a reporting task of the fixture printed. */
    private static Map<String, String> report(BuildResult result) {
        Map<String, String> reported = new LinkedHashMap<>();
        for (String line : result.getOutput().lines().toList()) {
            int separator = line.indexOf('=');
            if (separator > 0 && line.substring(0, separator).matches("[A-Z_]+")) {
                reported.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return reported;
    }
}
