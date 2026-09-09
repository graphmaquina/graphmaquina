package io.stategraph.maven;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the packaged Maven plugin the way a consumer does: a real {@code mvn} process resolves it from the file
 * repository this build published to, reads its descriptor and runs its goals. Nothing here instantiates a Mojo.
 */
class MavenPluginIntegrationTest {

    /** The version every StateGraph artifact of the fixture repository is published under. */
    private static final String VERSION = "0.3.0-SNAPSHOT";

    /** Where the published plugin lives inside the fixture repository. */
    private static final String MODULE = "io/stategraph/stategraph-maven-plugin/" + VERSION;

    /** The descriptor Maven looks a plugin's goals up in. */
    private static final String DESCRIPTOR = "META-INF/maven/plugin.xml";

    /** The published index of the main compilation, beside its classes. */
    private static final String MAIN_EXPORTS = "target/classes/META-INF/stategraph/exports.json";

    /** The published index of the test compilation, beside its own classes. */
    private static final String TEST_EXPORTS = "target/test-classes/META-INF/stategraph/exports.json";

    /** The file repository the Gradle build published every StateGraph artifact of this version to. */
    private final Path fixtureRepository = Path.of(requireProperty("stategraph.fixtureRepository"));

    @TempDir
    Path tempDirectory;

    @Test
    void publishedArtifactContainsAnExecutableDescriptor() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        MavenResult result = fixture.run(
                "io.stategraph:stategraph-maven-plugin:0.3.0-SNAPSHOT:generate");
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.exists("target/stategraph/main/annotation-manifest.json"));
        assertTrue(fixture.generatedSourcesContainGraphCompilation());
    }

    @Test
    void compileSealsInventoryWithoutRequiringPackage() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        MavenResult result = fixture.run("compile");
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertTrue(fixture.exists("target/classes/META-INF/stategraph/exports.json"));
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
        assertFalse(fixture.jarContains("fx/Orders_.class"));
        assertTrue(fixture.jarContains("fx/Unrelated.class"));
    }

    /**
     * The order of the lifecycle is the whole contract of this plugin: the goals of the packaging keep their
     * place in the execution plan, so the compiler of a phase runs before the StateGraph goal bound to it, and
     * completion sees the classes javac has just written.
     */
    @Test
    void completionRunsAfterTheCompilerOfItsOwnPhase() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);

        MavenResult result = fixture.run("test-compile");

        assertEquals(0, result.exitCode(), result.output());
        List<String> executed = goalExecutions(result.output());
        assertOrder(executed, ":generate (sg-generate)", ":prepare (sg-prepare)");
        assertOrder(executed, ":prepare (sg-prepare)", ":compile (default-compile)");
        assertOrder(executed, ":compile (default-compile)", ":complete (sg-complete)");
        assertOrder(executed, ":generate-test (sg-generate-test)", ":prepare-test (sg-prepare-test)");
        assertOrder(executed, ":prepare-test (sg-prepare-test)", ":testCompile (default-testCompile)");
        assertOrder(executed, ":testCompile (default-testCompile)", ":complete-test (sg-complete-test)");
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
        assertTrue(fixture.jarContains("fx/Orders_.class"));
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
        assertTrue(fixture.jarContains("fx/Orders_.class"));

        fixture.writeSources(renamed());
        MavenResult result = fixture.run("package");

        assertEquals(0, result.exitCode(), result.output());
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
     * A generation the sources make impossible stops the build where it happens: no manifest is written for
     * javac to consume, nothing is retired, and no artifact is produced.
     */
    @Test
    void failedGenerationLeavesNoManifestAndNoJar() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        Map<String, String> confused = MavenFixture.domainSources();
        confused.put("fx.Confused", """
                package fx;

                import io.stategraph.annotation.GraphAction;
                import io.stategraph.annotation.GraphProperty;
                import io.stategraph.api.ActionOutcome;
                import io.stategraph.api.TransitionContext;

                /** A host whose member claims two graph roles at once, which the generator refuses. */
                public final class Confused {

                    @GraphAction
                    @GraphProperty
                    public ActionOutcome reject(TransitionContext<Order, Status> context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        fixture.writeSources(confused);

        MavenResult result = fixture.run("package");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("[SG604]"), result.output());
        assertFalse(fixture.exists("target/stategraph/main/annotation-manifest.json"));
        assertFalse(fixture.exists("target/classes/META-INF/stategraph/exports.json"));
        assertFalse(fixture.exists("target/fixture-1.0.0.jar"));
    }

    /**
     * A compilation javac rejects publishes nothing: the preparation left its pending inventory behind, the
     * completion never ran, the index is gone rather than stale, and Maven produced no jar.
     */
    @Test
    void failedCompilationLeavesAPendingInventoryAndNoJar() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
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
        assertTrue(fixture.exists("target/stategraph/main/owned-outputs.pending.json"));
        assertFalse(fixture.inventoryIsSealed("main"));
        assertFalse(fixture.exists("target/classes/META-INF/stategraph/exports.json"));
        assertFalse(fixture.exists("target/fixture-1.0.0.jar"));
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
        assertTrue(fixture.exists("target/stategraph/test/annotation-manifest.json"));
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
                import io.stategraph.annotation.GenerateGraphApi;
                import io.stategraph.api.Machine;
                import io.stategraph.api.Outcome;

                import static fx.Status.DONE;
                import static fx.Status.NEW;

                /** A machine of the downstream project over the vocabulary the producer jar publishes. */
                @GenerateGraphApi
                @ReviewsGraph.Definition(initial = NEW, terminals = DONE,
                    state = OrderRefs.Status.class, id = OrderRefs.Id.class)
                @ReviewsGraph.Row(state = NEW, otherwise = "a new order is reviewed")
                @ReviewsGraph.Row(state = DONE, otherwise = "a reviewed order does not move")
                public interface Reviews extends Machine<Order, Status> {

                    @ReviewsGraph.Action(from = NEW, to = DONE, action = ActionsRefs.Approve.class)
                    Outcome<Order, Status> review(Order order);
                }
                """));

        MavenResult result = consumer.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(consumer.inventoryIsSealed("main"));
        assertEquals(List.of("cx.Reviews"), consumer.localExports());
        assertTrue(consumer.jarContains("cx/ReviewsGraph.class"));
        assertTrue(consumer.jarContains("cx/Reviews_.class"));
        assertFalse(consumer.jarContainsGraphCompilationCarrier());
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

    /** The generator runs under the {@code java} the build selected for it, not under the JVM running Maven. */
    @Test
    void runsTheGeneratorUnderTheSelectedJavaExecutable() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        String selected = Path.of(System.getProperty("java.home"), "bin", "java").toString();

        MavenResult result = fixture.run("-X", "-Dstategraph.javaExecutable=" + selected, "generate-sources");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("running the StateGraph generator: " + selected + " -cp"),
                "the generator did not run under " + selected);
        assertTrue(fixture.exists("target/stategraph/main/annotation-manifest.json"));
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

        MavenResult result = fixture.run("io.stategraph:stategraph-maven-plugin:" + VERSION + ":complete");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("StateGraph completes nothing for fx.build:fixture:main"),
                result.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));
    }

    /**
     * A build that switched a compilation off is refused before the first deletion: the index and the sealed
     * inventory of the previous build are untouched, because retiring them and compiling nothing would leave
     * the project published with nothing at all.
     */
    @Test
    void skippingTheCompilerFailsBeforeAnythingIsRetired() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);

        MavenResult main = fixture.run("-Dmaven.main.skip=true", "compile");

        assertNotEquals(0, main.exitCode(), main.output());
        assertTrue(main.output().contains("maven.main.skip switches the default-compile compilation off"),
                main.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));

        MavenResult test = fixture.run("-Dmaven.test.skip=true", "test-compile");

        assertNotEquals(0, test.exitCode(), test.output());
        assertTrue(test.output().contains("maven.test.skip switches the default-testCompile compilation off"),
                test.output());
    }

    /**
     * A compiler that would not run under the JDK of the generator, or under a release the generated
     * vocabulary does not compile with, stops the build with what disagrees and how to fix it.
     */
    @Test
    void aCompilerThatDisagreesWithTheGeneratorFailsTheBuild() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);

        fixture.editPom("<release>21</release>", "<release>17</release>");
        MavenResult release = fixture.run("generate-sources");
        assertNotEquals(0, release.exitCode(), release.output());
        assertTrue(release.output().contains("compiles with <release>17</release>"), release.output());
        fixture.editPom("<release>17</release>", "<release>21</release>");

        String forked = "<fork>true</fork><executable>/opt/other-jdk/bin/javac</executable>";
        fixture.editPom("<release>21</release>", "<release>21</release>" + forked);
        MavenResult forkedResult = fixture.run("generate-sources");
        assertNotEquals(0, forkedResult.exitCode(), forkedResult.output());
        assertTrue(forkedResult.output().contains("forks javac at /opt/other-jdk/bin/javac"),
                forkedResult.output());
        fixture.editPom(forked, "");

        fixture.editPom("<release>21</release>",
                "<release>21</release><jdkToolchain><version>77</version></jdkToolchain>");
        MavenResult toolchain = fixture.run("generate-sources");
        assertNotEquals(0, toolchain.exitCode(), toolchain.output());
        assertTrue(toolchain.output().contains("the jdk toolchain {version=77}"), toolchain.output());
        assertFalse(fixture.exists("target/stategraph/main/annotation-manifest.json"));
    }

    /**
     * A compiler configured to read another annotation manifest than the one the generator writes is refused
     * before anything is generated, with the argument that execution must declare.
     */
    @Test
    void aCompilerThatReadsAnotherManifestFailsTheBuild() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("stategraph/main/annotation-manifest.json",
                "stategraph/elsewhere/annotation-manifest.json");

        MavenResult result = fixture.run("generate-sources");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("stategraph/elsewhere/annotation-manifest.json"), result.output());
        assertTrue(result.output().contains("Declare exactly this compiler argument for that execution:"
                + " -Astategraph.annotationManifest="), result.output());
        assertFalse(fixture.exists("target/stategraph/main/annotation-manifest.json"));
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
        assertEquals(List.of("io.stategraph:stategraph-codegen:runtime"), scopedDependencies(pom));
        assertEquals(List.of("io.stategraph:stategraph-codegen:" + VERSION), dependencies(descriptor));
    }

    /** The domain with the action and the facade renamed, so the previous names must be retired. */
    private static Map<String, String> renamed() {
        Map<String, String> sources = MavenFixture.domainSources();
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
        sources.put("fx.Requests", MavenFixture.orders("Requests", "Accept", "accept"));
        return sources;
    }

    /** The entity of the shared domain with one more exported property, which a later build deletes again. */
    private static String orderWithNote() {
        return """
                package fx;

                import io.stategraph.annotation.GraphProperty;
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

    /** The goal executions of one build, as {@code plugin:version:goal (executionId)}, in the order Maven ran them. */
    private static List<String> goalExecutions(String output) {
        List<String> executions = new ArrayList<>();
        for (String line : output.lines().toList()) {
            int start = line.indexOf("--- ");
            int end = line.indexOf(" @ ");
            if (line.startsWith("[INFO] ") && start > 0 && end > start) {
                executions.add(line.substring(start + 4, end));
            }
        }
        return List.copyOf(executions);
    }

    /** Asserts that the execution ending with {@code first} ran before the one ending with {@code second}. */
    private static void assertOrder(List<String> executions, String first, String second) {
        int before = indexOf(executions, first);
        int after = indexOf(executions, second);
        assertTrue(before >= 0, first + " did not run: " + executions);
        assertTrue(after >= 0, second + " did not run: " + executions);
        assertTrue(before < after, first + " did not run before " + second + ": " + executions);
    }

    /** The position of the single execution whose name ends with {@code suffix}, or {@code -1}. */
    private static int indexOf(List<String> executions, String suffix) {
        for (int index = 0; index < executions.size(); index++) {
            if (executions.get(index).endsWith(suffix)) {
                return index;
            }
        }
        return -1;
    }

    /** The goals the descriptor declares, in the order it declares them. */
    private static List<String> goals(Element descriptor) {
        List<String> goals = new ArrayList<>();
        for (Element mojo : children(child(descriptor, "mojos"), "mojo")) {
            goals.add(text(mojo, "goal"));
        }
        return goals;
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
                    + " ./gradlew :stategraph-maven-plugin:publishMavenPublicationToAdapterTestsRepository");
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

    /** The value of {@code name}, which the Gradle test task sets and these tests cannot run without. */
    private static String requireProperty(String name) {
        String value = System.getProperty(name);
        if (value == null) {
            throw new IllegalStateException(name + " is not set; run these tests through"
                    + " ./gradlew :stategraph-maven-plugin:test");
        }
        return value;
    }

    /** One document to parse, so the two sources of XML share the builder and its failure handling. */
    @FunctionalInterface
    private interface Parser {

        Document parse(DocumentBuilder builder) throws IOException, SAXException;
    }
}
