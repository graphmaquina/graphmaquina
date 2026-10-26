package io.github.graphmaquina.maven;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static io.github.graphmaquina.maven.BuildAssertions.assertNothingWasRetired;
import static io.github.graphmaquina.maven.MavenFixture.MAIN_EXPORTS;
import static io.github.graphmaquina.maven.MavenFixture.TEST_EXPORTS;
import static io.github.graphmaquina.maven.MavenFixture.goalPrefix;
import static io.github.graphmaquina.maven.MavenFixture.graphmaquinaVersion;
import static io.github.graphmaquina.maven.MavenFixture.requireProperty;
import static io.github.graphmaquina.maven.MavenFixture.testDomain;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the packaged Maven plugin the way a consumer does: a real {@code mvn} process resolves it from the file
 * repository this build published to, reads its descriptor and runs its goals. Nothing here instantiates a Mojo.
 *
 * <p>These tests cover the published artifact and its descriptor, the cycle of generation, compilation and
 * completion over repeated, edited and failing builds, and the runtime features of the code the plugin generates.</p>
 */
class MavenPluginIntegrationTest {

    /** Where the published plugin lives inside the fixture repository. */
    private static final String MODULE = "io/github/graphmaquina/graphmaquina-maven-plugin/" + graphmaquinaVersion();

    /** The descriptor Maven looks a plugin's goals up in. */
    private static final String DESCRIPTOR = "META-INF/maven/plugin.xml";

    /** The program the interrupted fixture runner waits for: it reports its PID, then blocks. */
    private static final String WAITING_PUBLICATIONS = """
            package fxr;

            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.util.concurrent.CountDownLatch;

            /** Reports its PID, then waits for the parent fixture runner to stop this JVM. */
            public final class WaitingPublications {
                private WaitingPublications() { }

                public static void main(String[] arguments) throws Exception {
                    Path written = Files.writeString(Path.of("publications.pid.tmp"),
                            Long.toString(ProcessHandle.current().pid()));
                    Files.move(written, Path.of("publications.pid"));
                    new CountDownLatch(1).await();
                }
            }
            """;

    /** The file repository the Gradle build published every StateGraph artifact of this version to. */
    private final Path fixtureRepository = Path.of(requireProperty("graphmaquina.fixtureRepository"));

    @TempDir
    Path tempDirectory;

    @Test
    void publishedArtifactContainsAnExecutableDescriptor() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        MavenResult result = fixture.run(goalPrefix() + "generate");
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.exists("target/graphmaquina/main/annotation-manifest.json"));
        assertTrue(fixture.generatedSourcesContainGraphCompilation());
    }

    @Test
    void compileSealsInventoryWithoutRequiringPackage() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        MavenResult result = fixture.run("compile");
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertTrue(fixture.exists("target/classes/META-INF/graphmaquina/exports.json"));
    }

    @Test
    void deletingLastExportDoesNotPublishStaleGeneratedClasses() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        fixture.removeAllStateGraphDeclarations();
        assertEquals(0, fixture.run("package").exitCode());
        assertTrue(fixture.localExports().isEmpty());
        assertFalse(fixture.jarContains("fx/OrdersGraph.class"));
        assertFalse(fixture.jarContains("fx/ActionsRefs$Approve.class"));
        assertFalse(fixture.jarContains("fx/OrdersRuntime.class"));
        assertTrue(fixture.jarContains("fx/Unrelated.class"));
    }

    /**
     * A project directory that carries a space and a non-ASCII character is built, requested and compiled as
     * written: the request the generator reads names that very directory, and the compilation completes.
     */
    @Test
    void buildsFromADirectoryWithSpacesAndNonAsciiCharacters() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals("fixture project ñ", fixture.projectDirectory().getFileName().toString());

        MavenResult result = fixture.run("compile");

        assertEquals(0, result.exitCode(), result.output());
        // Maven resolves the base directory of a project to its real path, which on this platform can differ
        // from the temporary directory the fixture was written to by a symbolic link of the system.
        assertEquals(fixture.projectDirectory().toRealPath().toString(),
                fixture.requestValue("main", "projectDirectory"));
        assertEquals("fx.build:fixture:main", fixture.requestValue("main", "compilationId"));
        assertTrue(fixture.exists("target/classes/fx/OrdersGraph.class"));
    }

    /**
     * A second {@code package} over the outputs of the first one produces the same artifacts: the retirement
     * removes what the previous cycle owned, the compiler recompiles what it was left without, and the
     * inventory is sealed again with no carrier in the jar.
     */
    @Test
    void repeatedPackageWithoutCleanProducesTheSameArtifacts() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> first = fixture.localExports();

        MavenResult second = fixture.run("package");

        assertEquals(0, second.exitCode(), second.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(first, fixture.localExports());
        assertTrue(first.contains("fx.Orders"));
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
        assertTrue(fixture.jarContains("fx/OrdersRuntime.class"));
        assertTrue(fixture.jarContains("fx/Unrelated.class"));
        assertFalse(fixture.jarContainsGraphCompilationCarrier());
    }

    /**
     * Renaming the action a facade binds and the facade itself retires the generated symbol, the vocabulary and
     * the companion of the previous names, and leaves nothing of them in the jar.
     */
    @Test
    void renameRetiresNestedSymbolsAndFacades() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        assertTrue(fixture.jarContains("fx/ActionsRefs$Approve.class"));
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
        assertTrue(fixture.jarContains("fx/OrdersRuntime.class"));

        fixture.writeSources(renamed());
        MavenResult result = fixture.run("package");

        assertEquals(0, result.exitCode(), result.output());
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

    /** Deleting one property retires the symbol generated for it and leaves the other properties alone. */
    @Test
    void deletingAPropertyRetiresItsGeneratedSymbol() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        Map<String, String> withNote = MavenFixture.domainSources();
        withNote.put("fx.Order", orderWithNote());
        fixture.writeSources(withNote);
        assertEquals(0, fixture.run("package").exitCode());
        assertTrue(fixture.jarContains("fx/OrderRefs$Note.class"));
        assertTrue(fixture.localExports().contains("fx.OrderRefs.Note"));

        fixture.writeSources(MavenFixture.domainSources());
        MavenResult result = fixture.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertFalse(fixture.jarContains("fx/OrderRefs$Note.class"));
        assertTrue(fixture.jarContains("fx/OrderRefs$Id.class"));
        assertTrue(fixture.jarContains("fx/OrderRefs$Status.class"));
        assertFalse(fixture.localExports().contains("fx.OrderRefs.Note"));
    }

    /**
     * A generation the sources make impossible stops the build where it happens, before the preparation: its
     * diagnostic is in the output, no preparation started another cycle, the index and the sealed inventory of the
     * previous build are as that build left them, and neither its manifest nor its jar is rewritten.
     */
    @Test
    void failedGenerationLeavesThePreviousCycleAsItWas() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        Path manifest = fixture.projectDirectory().resolve("target/graphmaquina/main/annotation-manifest.json");
        Path jar = fixture.projectDirectory().resolve("target/fixture-1.0.0.jar");
        byte[] manifestBefore = Files.readAllBytes(manifest);
        byte[] jarBefore = Files.readAllBytes(jar);
        Map<String, String> confused = MavenFixture.domainSources();
        confused.put("fx.Confused", """
                package fx;

                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.annotation.GraphProperty;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;

                /** A host whose member claims two graph roles at once, which the generator refuses. */
                public final class Confused {

                    @GraphAction
                    @GraphProperty
                    public ActionOutcome reject(TransitionContext<Order, Status, Transition> context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        fixture.writeSources(confused);

        MavenResult result = fixture.run("package");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("[" + DiagnosticCode.SG604 + "]"), result.output());
        assertNothingWasRetired(fixture, published);
        assertArrayEquals(manifestBefore, Files.readAllBytes(manifest));
        assertArrayEquals(jarBefore, Files.readAllBytes(jar));
    }

    /**
     * A compilation javac rejects publishes nothing and leaves no stale index behind: the preparation retired the
     * index the previous build published and left its pending inventory, the completion never ran, and Maven
     * packaged nothing, so the jar is the one the previous build wrote.
     */
    @Test
    void failedCompilationLeavesAPendingInventoryAndNoStaleIndex() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        assertTrue(fixture.exists(MAIN_EXPORTS));
        Path jar = fixture.projectDirectory().resolve("target/fixture-1.0.0.jar");
        byte[] jarBefore = Files.readAllBytes(jar);
        Map<String, String> broken = MavenFixture.domainSources();
        broken.put("fx.Broken", """
                package fx;

                /** A declaration javac refuses, so the compilation of this fixture fails. */
                public final class Broken {

                    private final int count = "not a number";
                }
                """);
        fixture.writeSources(broken);

        MavenResult result = fixture.run("package");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.exists("target/graphmaquina/main/owned-outputs.pending.json"));
        assertFalse(fixture.inventoryIsSealed("main"));
        assertFalse(fixture.exists(MAIN_EXPORTS));
        assertArrayEquals(jarBefore, Files.readAllBytes(jar));
    }

    /**
     * Each source set owns its own generation, its own work directory and its own index, so compiling the tests
     * publishes beside the test classes and leaves the index of the main compilation exactly as it was.
     */
    @Test
    void testSourceSetKeepsItsOwnManifestAndIndex() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("compile").exitCode());
        List<String> main = fixture.localExportsOf(MAIN_EXPORTS);
        for (Map.Entry<String, String> source : testDomain().entrySet()) {
            fixture.writeTestSource(source.getKey(), source.getValue());
        }

        MavenResult result = fixture.run("test-compile");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.exists("target/graphmaquina/test/annotation-manifest.json"));
        assertTrue(fixture.inventoryIsSealed("test"));
        assertEquals("fx.build:fixture:test", fixture.requestValue("test", "compilationId"));
        assertTrue(fixture.exists("target/test-classes/fxt/TicketsGraph.class"));
        assertEquals(List.of("fxt.Tickets", "fxt.TicketActionsRefs.Resolve", "fxt.TicketRefs.Id",
                "fxt.TicketRefs.Status"), fixture.localExportsOf(TEST_EXPORTS));
        assertEquals(main, fixture.localExportsOf(MAIN_EXPORTS));
        assertTrue(main.contains("fx.Orders"));
    }

    /**
     * A downstream project resolves the vocabulary of its dependency from the index that dependency published
     * inside its jar: no source of the producer is on its source path, and its own machine binds the producer's
     * generated symbols.
     */
    @Test
    void consumerCompilesAgainstAProducerJarWithoutSources() throws IOException {
        MavenFixture producer = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, producer.run("install").exitCode());
        MavenFixture consumer = MavenFixture.consumer(tempDirectory, fixtureRepository);
        consumer.writeSources(Map.of("cx.Reviews", """
                package cx;

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

                /** A machine of the downstream project over the vocabulary the producer jar publishes. */
                @GenerateGraphApi
                @ReviewsGraph.Definition(initial = NEW, terminals = DONE,
                    state = OrderRefs.Status.class, id = OrderRefs.Id.class)
                @ReviewsGraph.StatePolicy(state = NEW, otherwise = "a new order is reviewed")
                public interface Reviews extends Machine<Order, Status, Transition> {

                    @ReviewsGraph.Transition(id = APPROVE, from = NEW, to = DONE,
                        action = ActionsRefs.Approve.class)
                    Outcome<Order, Status, Transition> review(Order order);
                }
                """));

        MavenResult result = consumer.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(consumer.inventoryIsSealed("main"));
        assertEquals(List.of("cx.Reviews"), consumer.localExports());
        assertTrue(consumer.jarContains("cx/ReviewsGraph.class"));
        assertTrue(consumer.jarContains("cx/ReviewsRuntime.class"));
        assertFalse(consumer.jarContainsGraphCompilationCarrier());
        // The downstream machine identifies its edges with the producer's own enum.
        assertEquals(List.of("fx.Transition.APPROVE"), producer.transitionsOf("fx.Orders"));
        assertEquals(List.of("fx.Transition.APPROVE"), consumer.transitionsOf("cx.Reviews"));
    }

    /**
     * Generating sources is not preparing a compilation: {@code generate-sources} never retires anything, so
     * the classes and the index of the previous build are still there and still usable afterwards.
     */
    @Test
    void sourceOnlyGenerationLeavesCompiledOutputUsable() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        Path compiled = fixture.projectDirectory().resolve("target/classes/fx/OrdersGraph.class");
        FileTime before = Files.getLastModifiedTime(compiled);

        MavenResult result = fixture.run("generate-sources");

        assertEquals(0, result.exitCode(), result.output());
        assertFalse(result.output().contains("sg-prepare"), result.output());
        assertTrue(fixture.exists(MAIN_EXPORTS));
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(before, Files.getLastModifiedTime(compiled));
    }

    /**
     * Completion without a compilation to complete does nothing and says so: javac writes the provisional index
     * the completion promotes, so its absence means javac did not run for this source set in this build, and
     * completing anyway would seal an inventory of a compilation that did not happen.
     */
    @Test
    void completionWithoutACompilationIsANoOp() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);

        MavenResult result = fixture.run(goalPrefix() + "complete");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("StateGraph completes nothing for fx.build:fixture:main"),
                result.output());
        assertNothingWasRetired(fixture, published);
    }

    /**
     * A compilation this build prepared but javac never published for is a failure, not a no-op: the outputs of
     * the previous cycle are already retired, so leaving the build green would package a jar with the carrier
     * of the compilation in it and no index at all.
     */
    @Test
    void completionOfAPreparedButUnpublishedCompilationFails() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        String goal = goalPrefix();

        MavenResult result = fixture.run(goal + "generate", goal + "prepare", goal + "complete");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("The StateGraph annotation processor did not run"),
                result.output());
        assertTrue(fixture.exists("target/graphmaquina/main/owned-outputs.pending.json"));
        assertFalse(fixture.inventoryIsSealed("main"));
        assertFalse(fixture.exists(MAIN_EXPORTS));
    }

    /**
     * Every generation emits the carrier of its compilation, and the completion needs the class javac compiles from
     * it. A preparation that finds no carrier under the generated sources, here because it was deleted after the
     * generation, fails and says so, and asks for the generation to run again.
     */
    @Test
    void aPreparationThatFindsNoCarrierAsksForTheGenerationAgain() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        String goal = goalPrefix();
        assertEquals(0, fixture.run(goal + "generate").exitCode());
        Files.delete(fixture.carrierSource());

        MavenResult result = fixture.run(goal + "prepare");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("there is no compilation carrier of fx.build:fixture:main under "),
                result.output());
        assertTrue(result.output().contains(", although every generation emits one there: javac would compile this"
                + " compilation without it, and the completion, which needs the class of the carrier, would fail."
                + " Run the generation of this compilation again."), result.output());
    }

    /**
     * The preparation dates the carrier of this generation past everything the previous compilation left in the
     * class output, even classes dated an hour ahead, and leaves the bytes of the carrier untouched, which is what
     * keeps the generation deterministic.
     */
    @Test
    void preparationDatesTheCarrierPastTheNewestPreviousOutput() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        String before = Files.readString(fixture.carrierSource(), StandardCharsets.UTF_8);
        Path classes = fixture.projectDirectory().resolve("target/classes");
        FileTime future = FileTime.fromMillis(System.currentTimeMillis() + 3_600_000L);
        for (Path file : filesUnder(classes)) {
            Files.setLastModifiedTime(file, future);
        }

        MavenResult result = fixture.run("process-sources");

        assertEquals(0, result.exitCode(), result.output());
        Path carrier = fixture.carrierSource();
        FileTime dated = Files.getLastModifiedTime(carrier);
        assertTrue(dated.compareTo(future) > 0,
                "the carrier is dated " + dated + ", not past the outputs dated " + future);
        for (Path file : filesUnder(classes)) {
            assertTrue(dated.compareTo(Files.getLastModifiedTime(file)) > 0,
                    "the carrier is dated " + dated + ", not past " + file);
        }
        assertEquals(before, Files.readString(carrier, StandardCharsets.UTF_8));
    }

    /**
     * The descriptor names the Java release the classes of the plugin need, so Maven refuses an older JVM with
     * its own message before it loads a goal, rather than with a class-version error.
     */
    @Test
    void theDescriptorRequiresJava21() throws IOException {
        Element descriptor = descriptorOf(newest(fixtureRepository.resolve(MODULE), ".jar"));

        assertEquals("21", text(descriptor, "requiredJavaVersion"));
    }

    @Test
    void publishedArtifactIsAMavenPluginThatCarriesOnlyTheGenerator() throws IOException {
        Path module = fixtureRepository.resolve(MODULE);
        Element pom = parse(newest(module, ".pom"));
        assertEquals("maven-plugin", text(pom, "packaging"));

        Element descriptor = descriptorOf(newest(module, ".jar"));
        assertEquals(text(pom, "groupId"), text(descriptor, "groupId"));
        assertEquals(text(pom, "artifactId"), text(descriptor, "artifactId"));
        assertEquals(text(pom, "version"), text(descriptor, "version"));
        assertEquals(List.of("generate", "prepare", "complete", "generate-test", "prepare-test",
                "complete-test"), goals(descriptor));

        // Maven's own API and core are supplied by Maven at execution time and must not be published; the
        // generator is a dependency of the plugin alone, so nothing it carries reaches a consumer's classpath.
        assertEquals(List.of("io.github.graphmaquina:graphmaquina-codegen:runtime"), scopedDependencies(pom));
        assertEquals(List.of("io.github.graphmaquina:graphmaquina-codegen:" + graphmaquinaVersion()),
                dependencies(descriptor));

        // The goals resolve toolchains through the public component alone, the private one being Maven internals
        // this plugin has no reason to require, and the phases an execution of the compiler can run in through the
        // lifecycles Maven exports from its core.
        assertEquals(List.of("org.apache.maven.toolchain.ToolchainManager",
                "org.apache.maven.lifecycle.DefaultLifecycles"), requirements(descriptor));
    }

    @Test
    void definitionDefaultHookSurvivesConsumerBuildAndIsRemovedOnEdit() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        Map<String, String> sources = MavenFixture.domainSources();
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
        MavenResult first = fixture.run("package");
        assertEquals(0, first.exitCode(), first.output());
        Path runtime = fixture.projectDirectory().resolve("target/generated-sources/annotations/fx/OrdersRuntime.java");
        assertTrue(Files.readString(runtime).contains(".afterCommit(\"save\""));
        sources.put("fx.Orders", original);
        fixture.writeSources(sources);
        MavenResult second = fixture.run("package");
        assertEquals(0, second.exitCode(), second.output());
        assertFalse(Files.readString(runtime).contains(".afterCommit("));
    }

    /**
     * A facade that declares {@code recoveries}, built by the published plugin from the artifacts of this build: the
     * generated vocabulary carries {@code Recovery}, the generated companion one {@code recover} call per rule, and
     * the program compiled with them sees each failure of PUBLISH take the route its kind or its type selects.
     */
    @Test
    void routesTheFailuresOfAFacadeThatDeclaresRecoveries() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.writeSources(MavenFixture.recoverySources());

        MavenResult result = fixture.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.jarContains("fxr/TicketsGraph$Recovery.class"), result.output());
        String companion = Files.readString(fixture.projectDirectory()
                .resolve("target/generated-sources/annotations/fxr/TicketsRuntime.java"), StandardCharsets.UTF_8);
        assertTrue(companion.contains(".recover(fxr.PublicationFailures.Unavailable.class,"
                + " TicketChange.SCHEDULE_RETRY, fxr.ServiceUnavailableException.class)"), companion);
        assertTrue(companion.contains(".recover(fxr.PublicationFailures.Rejected.class,"
                + " TicketChange.RECORD_REJECTION, fxr.ProviderRejectedException.class)"), companion);
        String output = fixture.runMain("fxr.Publications");
        assertEquals(List.of(
                "ROUTED unavailable SCHEDULE_RETRY RETRY_PENDING kind=Unavailable cause=none",
                "ROUTED rejected RECORD_REJECTION REJECTED_BY_PROVIDER kind=Rejected cause=ProviderRejectedException",
                "ROUTED invalid MARK_INTEGRATION_FAILED INTEGRATION_FAILED kind=none cause=InvalidResponseException"),
                output.lines().filter((String line) -> line.startsWith("ROUTED ")).toList(), output);
    }

    /**
     * A parent that binds one child in two states, through the lookups of an instance host, built by the published
     * plugin from the artifacts of this build: the jar carries the pair vocabulary and the lookup symbols, its index
     * exports those symbols, the parent's companion binds the child's definition twice and the set builds that
     * definition once. The program compiled with them sees each payment start when the booking enters its node, the
     * booking leave each node when its payment is captured, and a payment whose booking is elsewhere refused as
     * detached.
     */
    @Test
    void runsTheSubmachinesOfAParentThatBindsOneChildInTwoStates() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.writeSources(MavenFixture.submachineSources());

        MavenResult result = fixture.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.jarContains("fxs/BookingsPayments$Submachine.class"), result.output());
        assertTrue(fixture.jarContains("fxs/BookingsPayments$Exit.class"), result.output());
        assertTrue(fixture.jarContains("fxs/LedgerRefs$DepositOf.class"), result.output());
        assertEquals(List.of("fxs.Bookings", "fxs.Payments", "fxs.BookingRefs.Balance", "fxs.BookingRefs.Deposit",
                "fxs.BookingRefs.Id", "fxs.BookingRefs.Status", "fxs.LedgerRefs.BalanceOf", "fxs.LedgerRefs.DepositOf",
                "fxs.PaymentRefs.Id", "fxs.PaymentRefs.Status"), fixture.localExports());
        Path generated = fixture.projectDirectory().resolve("target/generated-sources/annotations");
        String bookings = Files.readString(generated.resolve("fxs/BookingsRuntime.java"), StandardCharsets.UTF_8);
        assertTrue(bookings.contains("graph(GraphDefinition<Payment, PaymentStatus, PaymentTransition> paymentsGraph,"
                + " Ledger ledger)"), bookings);
        assertTrue(bookings.contains(".submachine(BookingStatus.DEPOSIT, paymentsGraph, (Booking booking) ->"
                + " booking.deposit(), (Payment payment) -> ledger.depositOf(payment))"), bookings);
        assertTrue(bookings.contains(".submachine(BookingStatus.BALANCE, paymentsGraph, (Booking booking) ->"
                + " booking.balance(), (Payment payment) -> ledger.balanceOf(payment))"), bookings);
        String set = Files.readString(generated.resolve("fxs/BillingRuntime.java"), StandardCharsets.UTF_8);
        assertTrue(set.contains("GraphDefinition<Payment, PaymentStatus, PaymentTransition> paymentsGraph"
                + " = PaymentsRuntime.graph();"), set);
        assertTrue(set.contains("StateMachines.from(BookingsRuntime.graph(paymentsGraph, ledger))"), set);
        assertTrue(set.contains("StateMachines.from(paymentsGraph)"), set);
        assertEquals(1, set.split("PaymentsRuntime\\.graph\\(", -1).length - 1, set);
        String output = fixture.runMain("fxs.Bookkeeping");
        assertEquals(List.of(
                "BILLING book APPLIED REQUESTED-BOOK->DEPOSIT start DEPOSIT:deposit:PENDING",
                "BILLING deposit APPLIED PENDING-CAPTURE->CAPTURED DEPOSIT-DEPOSIT_PAID->BALANCE"
                        + " start BALANCE:balance:PENDING",
                "BILLING balance APPLIED PENDING-CAPTURE->CAPTURED BALANCE-BALANCE_PAID->CONFIRMED",
                "BILLING early REJECTED DETACHED no parent holds it in DEPOSIT",
                "BILLING states stay=CONFIRMED deposit=CAPTURED balance=CAPTURED unbooked=REQUESTED early=PENDING"),
                output.lines().filter((String line) -> line.startsWith("BILLING ")).toList(), output);
    }

    /** An interrupted fixture runner must stop and reap the exact child JVM it launched. */
    @Test
    void interruptedPublicationProgramDoesNotLeaveItsChildRunning() throws Exception {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        Map<String, String> sources = MavenFixture.recoverySources();
        sources.put("fxr.WaitingPublications", WAITING_PUBLICATIONS);
        fixture.writeSources(sources);
        MavenResult build = fixture.run("package");
        assertEquals(0, build.exitCode(), build.output());

        assertTimeoutPreemptively(Duration.ofSeconds(90), () -> assertInterruptedRunnerReapsItsChild(fixture));
    }

    /** Every regular file under {@code root}, or nothing when the build has not written it yet. */
    private static List<Path> filesUnder(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    /** The domain with the action and the facade renamed, so the previous names must be retired. */
    private static Map<String, String> renamed() {
        Map<String, String> sources = MavenFixture.domainSources();
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
        sources.put("fx.Requests", MavenFixture.orders("Requests", "Accept", "accept"));
        return sources;
    }

    /** The entity of the shared domain with one more exported property, which a later build deletes again. */
    private static String orderWithNote() {
        return """
                package fx;

                import io.github.graphmaquina.annotation.GraphProperty;
                import lombok.Getter;
                import lombok.Setter;

                /** The entity of the graph, with a property this fixture deletes again. */
                @Getter
                @Setter
                public final class Order {

                    @GraphProperty
                    private String id = "order";

                    @GraphProperty
                    private Status status = Status.NEW;

                    @GraphProperty
                    private String note = "note";
                }
                """;
    }

    /**
     * Interrupts the fixture runner while it waits for {@code fxr.WaitingPublications}, and asserts that the runner
     * stops and reaps the very child JVM it launched, restores its interrupt status and leaves no transcript.
     */
    private static void assertInterruptedRunnerReapsItsChild(MavenFixture fixture) throws Exception {
        Path directory = fixture.projectDirectory();
        Path pidFile = directory.resolve("publications.pid");
        InterruptedRunner runner = new InterruptedRunner(fixture);
        try (WatchService watch = FileSystems.getDefault().newWatchService()) {
            directory.register(watch, StandardWatchEventKinds.ENTRY_CREATE);
            runner.start();
            awaitPidFile(watch, pidFile);
            long childPid = Long.parseLong(Files.readString(pidFile));
            Process owned = runner.awaitLaunchedChild();
            assertEquals(childPid, owned.pid(), "the PID file must come from the owned child");
            runner.assertReportsInterruption();
            assertFalse(owned.isAlive(), "the interrupted runner left its child JVM alive: " + owned.pid());
            assertNoTranscriptLeft(directory);
        } finally {
            runner.stop();
        }
    }

    /** Waits for the child to publish its PID file, up to thirty seconds for each change of the directory. */
    private static void awaitPidFile(WatchService watch, Path pidFile) throws InterruptedException {
        while (!Files.isRegularFile(pidFile)) {
            WatchKey event = watch.poll(30, TimeUnit.SECONDS);
            assertNotNull(event, "the fixture child did not publish its PID");
            event.pollEvents();
            assertTrue(event.reset(), "the watched fixture directory became unavailable");
        }
    }

    /** Asserts that the interrupted runner removed the transcript of the child it ran. */
    private static void assertNoTranscriptLeft(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            assertFalse(entries.anyMatch((Path path) -> path.getFileName().toString()
                    .startsWith("publications-")), "the runner left its transcript behind");
        }
    }

    /** The goals the descriptor declares, in the order it declares them. */
    private static List<String> goals(Element descriptor) {
        List<String> goals = new ArrayList<>();
        for (Element mojo : children(child(descriptor, "mojos"), "mojo")) {
            goals.add(text(mojo, "goal"));
        }
        return goals;
    }

    /** The distinct component roles the mojos of the descriptor require, in the order they first appear. */
    private static List<String> requirements(Element descriptor) {
        List<String> roles = new ArrayList<>();
        for (Element mojo : children(child(descriptor, "mojos"), "mojo")) {
            for (Element requirement : children(child(mojo, "requirements"), "requirement")) {
                String role = text(requirement, "role");
                if (!roles.contains(role)) {
                    roles.add(role);
                }
            }
        }
        return List.copyOf(roles);
    }

    /** The dependencies of the published POM, as {@code groupId:artifactId:scope}. */
    private static List<String> scopedDependencies(Element pom) {
        List<String> dependencies = new ArrayList<>();
        for (Element dependency : children(child(pom, "dependencies"), "dependency")) {
            dependencies.add(text(dependency, "groupId") + ':' + text(dependency, "artifactId")
                    + ':' + text(dependency, "scope"));
        }
        return dependencies;
    }

    /** The dependencies the descriptor names, as {@code groupId:artifactId:version}. */
    private static List<String> dependencies(Element descriptor) {
        List<String> dependencies = new ArrayList<>();
        for (Element dependency : children(child(descriptor, "dependencies"), "dependency")) {
            dependencies.add(text(dependency, "groupId") + ':' + text(dependency, "artifactId")
                    + ':' + text(dependency, "version"));
        }
        return dependencies;
    }

    /** The descriptor packaged inside {@code jar}, which is what Maven reads to find a goal. */
    private static Element descriptorOf(Path jar) throws IOException {
        try (ZipFile archive = new ZipFile(jar.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = archive.getEntry(DESCRIPTOR);
            if (entry == null) {
                throw new IllegalStateException("the published jar " + jar.getFileName() + " carries no "
                        + DESCRIPTOR);
            }
            try (InputStream stream = archive.getInputStream(entry)) {
                return read(builder -> builder.parse(stream), jar + "!/" + DESCRIPTOR);
            }
        }
    }

    /** The newest published file of {@code module} whose name ends with {@code suffix}. */
    private static Path newest(Path module, String suffix) throws IOException {
        if (!Files.isDirectory(module)) {
            throw new IllegalStateException("the plugin was not published to " + module + "; run"
                    + " ./gradlew :graphmaquina-maven-plugin:publishMavenPublicationToAdapterTestsRepository");
        }
        try (Stream<Path> files = Files.list(module)) {
            // Snapshot file names carry the publication timestamp, so their order is chronological.
            return files.filter(file -> file.getFileName().toString().endsWith(suffix))
                    .max(Comparator.comparing(file -> file.getFileName().toString()))
                    .orElseThrow(() -> new IllegalStateException("no " + suffix + " was published to " + module));
        }
    }

    private static Element parse(Path file) throws IOException {
        return read(builder -> builder.parse(file.toFile()), file.toString());
    }

    /** Parses one document with a plain, namespace-unaware builder and returns its root element. */
    private static Element read(Parser parser, String what) throws IOException {
        try {
            Document document = parser.parse(DocumentBuilderFactory.newInstance().newDocumentBuilder());
            return document.getDocumentElement();
        } catch (ParserConfigurationException | SAXException failure) {
            throw new IllegalStateException("cannot read " + what, failure);
        }
    }

    /** The single child element of {@code parent} named {@code name}. */
    private static Element child(Element parent, String name) {
        List<Element> children = children(parent, name);
        if (children.size() != 1) {
            throw new IllegalStateException(parent.getTagName() + " declares " + children.size() + " <" + name
                    + "> elements, not one");
        }
        return children.get(0);
    }

    /** Every child element of {@code parent} named {@code name}, in document order. */
    private static List<Element> children(Element parent, String name) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element element && element.getTagName().equals(name)) {
                children.add(element);
            }
        }
        return children;
    }

    /** The text of the single child element of {@code parent} named {@code name}. */
    private static String text(Element parent, String name) {
        return child(parent, name).getTextContent().strip();
    }

    /**
     * The fixture runner of {@code fxr.WaitingPublications} on a thread of its own, with what it reported: the
     * child it launched, the failure it ended with and the interrupt status it ended with.
     */
    private static final class InterruptedRunner {

        private final AtomicReference<Throwable> observed = new AtomicReference<>();
        private final AtomicReference<Boolean> interruptStatus = new AtomicReference<>();
        private final AtomicReference<Process> launched = new AtomicReference<>();
        private final CountDownLatch launchObserved = new CountDownLatch(1);
        private final Thread thread;

        InterruptedRunner(MavenFixture fixture) {
            thread = new Thread(() -> run(fixture), "maven-publications-interruption");
            thread.setDaemon(true);
        }

        private void run(MavenFixture fixture) {
            try {
                fixture.runMain("fxr.WaitingPublications", (Process child) -> {
                    launched.set(child);
                    launchObserved.countDown();
                });
            } catch (Throwable failure) {
                observed.set(failure);
                interruptStatus.set(Thread.currentThread().isInterrupted());
            }
        }

        void start() {
            thread.start();
        }

        /** Waits for the runner to report the child it launched, and fails the test when it does not. */
        Process awaitLaunchedChild() throws InterruptedException {
            assertTrue(launchObserved.await(30, TimeUnit.SECONDS), "the runner did not report its launched child");
            Process owned = launched.get();
            assertNotNull(owned, "the fixture did not report the Process it launched");
            return owned;
        }

        /** Interrupts the runner, then asserts that it finished its cleanup and reported the interruption. */
        void assertReportsInterruption() throws InterruptedException {
            thread.interrupt();
            thread.join(10_000);
            assertFalse(thread.isAlive(), "the interrupted Maven fixture runner did not finish cleanup");
            Throwable failure = observed.get();
            assertTrue(failure instanceof IllegalStateException, "the runner returned " + failure);
            assertTrue(failure.getCause() instanceof InterruptedException,
                    "the original interruption was lost: " + failure);
            assertEquals(Boolean.TRUE, interruptStatus.get(), "the runner did not restore interrupt status");
        }

        /** Interrupts the runner and destroys the child it launched, if it is still alive. */
        void stop() throws InterruptedException {
            thread.interrupt();
            Process owned = launched.get();
            if (owned != null && owned.isAlive()) {
                owned.destroyForcibly();
                try {
                    owned.waitFor(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            thread.join(10_000);
        }
    }

    /** One document to parse, so the two sources of XML share the builder and its failure handling. */
    @FunctionalInterface
    private interface Parser {

        Document parse(DocumentBuilder builder) throws IOException, SAXException;
    }
}
