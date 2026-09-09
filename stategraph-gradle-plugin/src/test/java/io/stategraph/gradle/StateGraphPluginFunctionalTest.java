package io.stategraph.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Functional tests driving the StateGraph Gradle plugin through TestKit over the shared adapter domain: one
 * clean build, and the lifecycle regressions a build that keeps generating, compiling and publishing must hold.
 */
class StateGraphPluginFunctionalTest {

    /** The file Maven repository the parent build published core, codegen and the processor to. */
    private static final Path FIXTURE_REPOSITORY = Path.of(System.getProperty("stategraph.fixtureRepository"));

    /** The published index of the main compilation, beside its classes. */
    private static final String MAIN_EXPORTS = "build/classes/java/main/META-INF/stategraph/exports.json";

    /** The published index of the test compilation, beside its own classes. */
    private static final String TEST_EXPORTS = "build/classes/java/test/META-INF/stategraph/exports.json";

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
        assertTrue(fixture.exists("build/stategraph/main/annotation-manifest.json"));
        assertTrue(fixture.jarContains("META-INF/stategraph/exports.json"));
        assertFalse(fixture.jarContainsGraphCompilationCarrier());
        assertFalse(fixture.runtimeClasspathContains("stategraph-codegen"));
        assertFalse(fixture.runtimeClasspathContains("stategraph-processor"));
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
        assertFalse(fixture.jarContains("fx/Orders_.class"));
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
        assertTrue(fixture.jarContains("fx/Orders_.class"));

        fixture.writeSources(renamed());
        fixture.run("jar");

        assertFalse(fixture.jarContains("fx/ActionsRefs$Approve.class"));
        assertFalse(fixture.jarContains("fx/OrdersGraph.class"));
        assertFalse(fixture.jarContains("fx/Orders_.class"));
        assertTrue(fixture.jarContains("fx/ActionsRefs$Accept.class"));
        assertTrue(fixture.jarContains("fx/RequestsGraph.class"));
        assertTrue(fixture.jarContains("fx/Requests_.class"));
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
        assertTrue(fixture.exists("build/stategraph/main/owned-outputs.pending.json"));
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
        assertTrue(fixture.exists("build/stategraph/test/annotation-manifest.json"));
        assertTrue(fixture.inventoryIsSealed("test"));
        assertEquals(List.of("fxt.Tickets", "fxt.TicketActionsRefs.Resolve", "fxt.TicketRefs.Id",
                "fxt.TicketRefs.Status"), fixture.localExportsOf(TEST_EXPORTS));
        assertEquals(main, fixture.localExportsOf(MAIN_EXPORTS));
        assertTrue(main.contains("fx.Orders"));
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
        assertTrue(fixture.exists("build/stategraph/main/annotation-manifest.json"));
    }

    /** The domain with the action and the facade renamed, so the previous names must be retired. */
    private static Map<String, String> renamed() {
        Map<String, String> sources = PluginFixture.domainSources();
        sources.remove("fx.Orders");
        sources.put("fx.Actions", """
                package fx;

                import io.stategraph.annotation.GraphAction;
                import io.stategraph.api.ActionOutcome;
                import io.stategraph.api.TransitionContext;

                /** The action host of the graph, with its action renamed. */
                public final class Actions {

                    @GraphAction
                    public ActionOutcome accept(TransitionContext<Order, Status> context) {
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
        sources.put("fxt.Ticket", """
                package fxt;

                import io.stategraph.annotation.GraphProperty;

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

                import io.stategraph.annotation.GraphAction;
                import io.stategraph.api.ActionOutcome;
                import io.stategraph.api.TransitionContext;

                /** The action host of the test graph. */
                public final class TicketActions {

                    @GraphAction
                    public ActionOutcome resolve(TransitionContext<Ticket, Level> context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        sources.put("fxt.Tickets", """
                package fxt;

                import io.stategraph.annotation.GenerateGraphApi;
                import io.stategraph.api.Machine;
                import io.stategraph.api.Outcome;

                import static fxt.Level.CLOSED;
                import static fxt.Level.OPEN;

                /** The machine of the test source set. */
                @GenerateGraphApi
                @TicketsGraph.Definition(initial = OPEN, terminals = CLOSED,
                    state = TicketRefs.Status.class, id = TicketRefs.Id.class)
                @TicketsGraph.Row(state = OPEN, otherwise = "an open ticket is resolved")
                @TicketsGraph.Row(state = CLOSED, otherwise = "a closed ticket does not move")
                public interface Tickets extends Machine<Ticket, Level> {

                    @TicketsGraph.Action(from = OPEN, to = CLOSED,
                        action = TicketActionsRefs.Resolve.class)
                    Outcome<Ticket, Level> resolve(Ticket ticket);
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
