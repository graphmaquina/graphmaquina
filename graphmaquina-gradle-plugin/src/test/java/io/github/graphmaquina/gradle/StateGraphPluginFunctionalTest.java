package io.github.graphmaquina.gradle;

import com.google.gson.JsonElement;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.graphmaquina.gradle.PluginFixture.FIXTURE_REPOSITORY;
import static io.github.graphmaquina.gradle.PluginFixture.MAIN_EXPORTS;
import static io.github.graphmaquina.gradle.PluginFixture.testDomain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Functional tests driving the StateGraph Gradle plugin through TestKit over the shared adapter domain: one
 * clean build, and the lifecycle regressions a build that keeps generating, compiling and publishing must hold.
 */
class StateGraphPluginFunctionalTest {

    /** The published index of the test compilation, beside its own classes. */
    private static final String TEST_EXPORTS = "build/classes/java/test/META-INF/graphmaquina/exports.json";

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

    /**
     * The generator publishes the origin of every declaration relative to the project directory, while the generation
     * fingerprints each original source relative to its own source directory. A machine's source moved unchanged from
     * one populated source directory of the source set to another still regenerates, so the incremental build
     * publishes the origin a clean build publishes, and a build after it changes nothing.
     */
    @Test
    void movingASourceToAnotherSourceDirectoryPublishesItsNewOrigin() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.appendToBuildScript("""
                sourceSets { main { java.srcDir("src/extra/java") } }
                """);
        fixture.writeFile("src/extra/java/fx/Extra.java", """
                package fx;

                /** A plain class that keeps the second source directory populated. */
                final class Extra {

                    private Extra() {
                    }
                }
                """);
        fixture.run("jar");
        assertEquals("src/main/java/fx/Orders.java", fixture.publishedOrigin("fx.Orders"));
        Path project = fixture.projectDirectory();
        Files.move(project.resolve("src/main/java/fx/Orders.java"), project.resolve("src/extra/java/fx/Orders.java"));

        BuildResult moved = fixture.run("jar");
        BuildResult again = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, moved.task(":generateStateGraphApi").getOutcome());
        assertEquals("src/extra/java/fx/Orders.java", fixture.publishedOrigin("fx.Orders"));
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":generateStateGraphApi").getOutcome());
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":compileJava").getOutcome());
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
                "the test source set declares io.github.graphmaquina:graphmaquina-processor"
                        + " in testAnnotationProcessor"),
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
     * A dependency published by another tool version is refused, never adapted. The generation fails with
     * {@code SG602} and never falls back to a partial graph: it stops before the preparation retires anything,
     * so the index and the sealed inventory of the previous cycle are exactly what they were.
     */
    @Test
    void refusesAProducerOfAnotherToolVersion() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.run("classes");
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);

        fixture.writeDependencyJar("dep:library:main", 2, "0.0.0-other");
        BuildResult mixed = fixture.runFailing("classes", "--stacktrace");

        assertEquals(TaskOutcome.FAILED, mixed.task(":generateStateGraphApi").getOutcome());
        assertTrue(mixed.getOutput().contains("[" + DiagnosticCode.SG602 + "]"), mixed::getOutput);
        assertTrue(mixed.getOutput().contains("was written by tool version 0.0.0-other"), mixed::getOutput);

        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));
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
     * The published processor runs inside a real Gradle JavaCompile task and marks the later occurrence of a
     * throwable shared by a recovery rule and the fallback, at the exact source line and caret column in both
     * annotation member orders.
     */
    @Test
    void publishedProcessorLocatesTheLaterDuplicateRecoveryType() throws IOException {
        for (boolean fallbackFirst : List.of(false, true)) {
            Path project = Files.createDirectories(tempDirectory.resolve(
                    fallbackFirst ? "fallback first" : "rules first"));
            PluginFixture fixture = PluginFixture.publishedPlugin(project, FIXTURE_REPOSITORY);
            Map<String, String> sources = PluginFixture.recoverySources();
            String original = sources.get("fxr.Tickets");
            String source = fallbackFirst
                    ? original.replace("recoveries = {", "failsOn = ServiceUnavailableException.class,\n"
                            + "                        recoveries = {")
                            .replace("failsOn = InvalidResponseException.class,", "")
                    : original.replace("failsOn = InvalidResponseException.class,",
                            "failsOn = ServiceUnavailableException.class,");
            assertFalse(original.equals(source), "the fallback type must be replaced");
            sources.put("fxr.Tickets", source);
            fixture.writeSources(sources);

            String token = "ServiceUnavailableException.class";
            int occurrence = source.lastIndexOf(token);
            assertTrue(source.indexOf(token) < occurrence, "the type must occur in the rule and fallback");
            int preferred = occurrence + token.lastIndexOf('.');
            int lineNumber = (int) source.substring(0, preferred).chars()
                    .filter((int character) -> character == '\n').count() + 1;
            int columnNumber = preferred - source.lastIndexOf('\n', preferred);
            String sourceLine = source.lines().skip(lineNumber - 1L).findFirst().orElseThrow();

            BuildResult result = fixture.runFailing("compileJava", "--refresh-dependencies", "--stacktrace");

            assertEquals(TaskOutcome.FAILED, result.task(":compileJava").getOutcome(), result::getOutput);
            String[] lines = result.getOutput().split("\\R");
            int diagnostic = indexOfDiagnostic(lines, lineNumber);
            assertTrue(diagnostic >= 0, result::getOutput);
            assertEquals(sourceLine, lines[diagnostic + 1], result::getOutput);
            assertEquals(" ".repeat(columnNumber - 1) + "^", lines[diagnostic + 2], result::getOutput);
        }
    }

    /**
     * A facade that declares {@code recoveries}, built by the published plugin from the artifacts of this build: the
     * generated vocabulary carries {@code Recovery}, the generated companion one {@code recover} call per rule, and
     * the program the build then runs sees each failure of PUBLISH take the route its kind or its type selects.
     * {@code --refresh-dependencies} is deliberate, for the reason {@link #publishedPluginMarkerResolves()} gives.
     */
    @Test
    void routesTheFailuresOfAFacadeThatDeclaresRecoveries() throws IOException {
        PluginFixture fixture = PluginFixture.publishedPlugin(tempDirectory, FIXTURE_REPOSITORY);
        fixture.writeSources(PluginFixture.recoverySources());
        fixture.appendToBuildScript("""
                tasks.register<JavaExec>("runPublications") {
                    classpath = sourceSets.getByName("main").runtimeClasspath
                    mainClass = "fxr.Publications"
                }
                """);

        BuildResult result = fixture.run("jar", "runPublications", "--refresh-dependencies", "--stacktrace");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateStateGraphApi").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":runPublications").getOutcome());
        assertTrue(fixture.jarContains("fxr/TicketsGraph$Recovery.class"));
        String companion = Files.readString(Path.of(fixture.request("main").get("processorSourcesOutput")
                .getAsString()).resolve("fxr/TicketsRuntime.java"), StandardCharsets.UTF_8);
        assertTrue(companion.contains(".recover(fxr.PublicationFailures.Unavailable.class,"
                + " TicketChange.SCHEDULE_RETRY, fxr.ServiceUnavailableException.class)"), companion);
        assertTrue(companion.contains(".recover(fxr.PublicationFailures.Rejected.class,"
                + " TicketChange.RECORD_REJECTION, fxr.ProviderRejectedException.class)"), companion);
        assertEquals(List.of(
                "ROUTED unavailable SCHEDULE_RETRY RETRY_PENDING kind=Unavailable cause=none",
                "ROUTED rejected RECORD_REJECTION REJECTED_BY_PROVIDER kind=Rejected cause=ProviderRejectedException",
                "ROUTED invalid MARK_INTEGRATION_FAILED INTEGRATION_FAILED kind=none cause=InvalidResponseException"),
                result.getOutput().lines().filter((String line) -> line.startsWith("ROUTED ")).toList(),
                result::getOutput);
    }

    /**
     * A parent that binds one child in two states, through the lookups of an instance host, built by the published
     * plugin from the artifacts of this build: the jar carries the pair vocabulary and the lookup symbols, its index
     * exports those symbols, the parent's companion binds the child's definition twice and the set builds that
     * definition once. The program the build then runs sees each payment start when the booking enters its node, the
     * booking leave each node when its payment is captured, and a payment whose booking is elsewhere refused as
     * detached. {@code --refresh-dependencies} is deliberate, for the reason {@link #publishedPluginMarkerResolves()}
     * gives.
     */
    @Test
    void runsTheSubmachinesOfAParentThatBindsOneChildInTwoStates() throws IOException {
        PluginFixture fixture = PluginFixture.publishedPlugin(tempDirectory, FIXTURE_REPOSITORY);
        fixture.writeSources(PluginFixture.submachineSources());
        fixture.appendToBuildScript("""
                tasks.register<JavaExec>("runBookkeeping") {
                    classpath = sourceSets.getByName("main").runtimeClasspath
                    mainClass = "fxs.Bookkeeping"
                }
                """);

        BuildResult result = fixture.run("jar", "runBookkeeping", "--refresh-dependencies", "--stacktrace");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateStateGraphApi").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":runBookkeeping").getOutcome());
        assertTrue(fixture.jarContains("fxs/BookingsPayments$Submachine.class"));
        assertTrue(fixture.jarContains("fxs/BookingsPayments$Exit.class"));
        assertTrue(fixture.jarContains("fxs/LedgerRefs$DepositOf.class"));
        assertEquals(List.of("fxs.Bookings", "fxs.Payments", "fxs.BookingRefs.Balance", "fxs.BookingRefs.Deposit",
                "fxs.BookingRefs.Id", "fxs.BookingRefs.Status", "fxs.LedgerRefs.BalanceOf", "fxs.LedgerRefs.DepositOf",
                "fxs.PaymentRefs.Id", "fxs.PaymentRefs.Status"), fixture.localExports());
        Path generated = Path.of(fixture.request("main").get("processorSourcesOutput").getAsString());
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
        assertEquals(List.of(
                "BILLING book APPLIED REQUESTED-BOOK->DEPOSIT start DEPOSIT:deposit:PENDING",
                "BILLING deposit APPLIED PENDING-CAPTURE->CAPTURED DEPOSIT-DEPOSIT_PAID->BALANCE"
                        + " start BALANCE:balance:PENDING",
                "BILLING balance APPLIED PENDING-CAPTURE->CAPTURED BALANCE-BALANCE_PAID->CONFIRMED",
                "BILLING early REJECTED DETACHED no parent holds it in DEPOSIT",
                "BILLING states stay=CONFIRMED deposit=CAPTURED balance=CAPTURED unbooked=REQUESTED early=PENDING"),
                result.getOutput().lines().filter((String line) -> line.startsWith("BILLING ")).toList(),
                result::getOutput);
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

    /** The index of the first line that reports {@code SG304} at {@code lineNumber} of {@code Tickets.java}, or -1. */
    private static int indexOfDiagnostic(String[] lines, int lineNumber) {
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].contains("Tickets.java:" + lineNumber + ": error: [" + DiagnosticCode.SG304 + "]")) {
                return index;
            }
        }
        return -1;
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
}
