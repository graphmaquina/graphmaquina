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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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

    /** Where a compilation publishes its index, relative to whatever class output it was given. */
    private static final String EXPORTS_ENTRY = "META-INF/graphmaquina/exports.json";

    /** The package the generator emits the carrier of every compilation into, as an archive path. */
    private static final String CARRIER_PACKAGE = "io/github/graphmaquina/generated/bootstrap/";

    @TempDir
    Path tempDirectory;

    @Test
    void appliesWithoutPublishingTheRootBuild() throws IOException {
        PluginFixture fixture = PluginFixture.applicationOnly(tempDirectory);
        BuildResult result = fixture.run("tasks", "--all", "--stacktrace");
        assertTrue(result.getOutput().contains("generateStateGraphApi"));
        assertTrue(result.getOutput().contains("generateTestStateGraphApi"));
    }

    @Test
    void compilesGeneratedVocabularyAndPublishesOnlyLocalExports() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        BuildResult result = fixture.run("clean", "jar", "--stacktrace");
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        assertTrue(fixture.exists("build/graphmaquina/main/annotation-manifest.json"));
        assertTrue(fixture.jarContains("META-INF/graphmaquina/exports.json"));
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
     * task only names them in the request. A build that moves either of them must still write the request
     * again, or the next lifecycle step would retire and publish under the paths of the previous layout. This
     * test moves the class output.
     */
    @Test
    void movingTheClassOutputRewritesTheRequest() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.run("classes");
        assertEquals(TaskOutcome.UP_TO_DATE, fixture.run("classes").task(":generateStateGraphApi").getOutcome());

        fixture.relocateClassOutput("elsewhere/classes");

        BuildResult result = fixture.run("classes", "--stacktrace");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateStateGraphApi").getOutcome());
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
     * The generator runs under the launcher the project's Java toolchain selected, not under whatever JVM the
     * Gradle daemon happens to be on: the task's own launcher is the one {@code launcherFor(java.toolchain)}
     * reports, and the process the build started is that very executable.
     */
    @Test
    void launchesSelectedToolchainInsteadOfDaemonJdk() throws IOException {
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
     * A source directory the source set takes whole is still handed to the generator as a directory, so a
     * hand-written type that shares its simple name with a generated one, in a package that merely ends like
     * the generated one's, is no collision: the generator compares a generated type with the original sources by
     * the path each stands at below its source root and by the types it declares, not by how that path ends.
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
     * refused before javac runs: {@code failOnError = false} lets a rejected compilation succeed, and
     * {@code -proc:only} runs the processors without writing a class at all.
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
     * A compilation that names no release compiles against the whole API of its toolchain's JDK, so the request
     * carries the language version of that toolchain; one that names no encoding gives the request none, and the
     * generator then reads UTF-8.
     */
    @Test
    void passesTheReleaseOfTheToolchainWhenTheCompilationNamesNone() throws IOException {
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
