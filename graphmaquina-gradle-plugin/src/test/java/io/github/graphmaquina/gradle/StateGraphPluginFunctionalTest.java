package io.github.graphmaquina.gradle;

import com.google.gson.JsonElement;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
     * again, or the next lifecycle step would retire and publish under the paths of the previous layout.
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
     * <p>Gradle keys an entry by the tasks that were requested, so the clean build and the runs that follow it
     * are asked for the same task twice: the second of them is the one that both reuses the entry and finds
     * every step of the cycle up to date.</p>
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
        assertTrue(retired.getOutput().contains("the semantic schema 1 of"), retired::getOutput);
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
