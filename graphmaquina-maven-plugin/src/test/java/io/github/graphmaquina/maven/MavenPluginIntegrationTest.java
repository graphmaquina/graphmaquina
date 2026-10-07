package io.github.graphmaquina.maven;

import com.google.gson.Gson;
import org.junit.jupiter.api.Assumptions;
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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drives the packaged Maven plugin the way a consumer does: a real {@code mvn} process resolves it from the file
 * repository this build published to, reads its descriptor and runs its goals. Nothing here instantiates a Mojo.
 */
class MavenPluginIntegrationTest {

    /** The version every StateGraph artifact of the fixture repository is published under. */
    private static final String VERSION = requireProperty("graphmaquina.version");

    /** Where the published plugin lives inside the fixture repository. */
    private static final String MODULE = "io/github/graphmaquina/graphmaquina-maven-plugin/" + VERSION;

    /** The descriptor Maven looks a plugin's goals up in. */
    private static final String DESCRIPTOR = "META-INF/maven/plugin.xml";

    /** The published index of the main compilation, beside its classes. */
    private static final String MAIN_EXPORTS = "target/classes/META-INF/graphmaquina/exports.json";

    /** The published index of the test compilation, beside its own classes. */
    private static final String TEST_EXPORTS = "target/test-classes/META-INF/graphmaquina/exports.json";

    /** The two annotation processors Lombok registers, as a {@code -processor} list names them. */
    private static final List<String> LOMBOK_PROCESSORS = List.of(
            "lombok.launch.AnnotationProcessorHider$AnnotationProcessor",
            "lombok.launch.AnnotationProcessorHider$ClaimingProcessor");

    /** The StateGraph annotation processor, as a {@code -processor} list names it. */
    private static final String STATEGRAPH_PROCESSOR = "io.github.graphmaquina.processor.StateGraphProcessor";

    /** The processor path of the fixture POM, as {@code it/basic/pom.xml} spells it, Lombok first. */
    private static final String PROCESSOR_PATHS = """
                      <annotationProcessorPaths>
                        <!-- Lombok first: the processor reads the accessors Lombok generates on Order. -->
                        <path>
                          <groupId>org.projectlombok</groupId>
                          <artifactId>lombok</artifactId>
                          <version>${lombok.version}</version>
                        </path>
                        <path>
                          <groupId>io.github.graphmaquina</groupId>
                          <artifactId>graphmaquina-processor</artifactId>
                          <version>${graphmaquina.version}</version>
                        </path>
                      </annotationProcessorPaths>
            """.strip();

    /** The file repository the Gradle build published every StateGraph artifact of this version to. */
    private final Path fixtureRepository = Path.of(requireProperty("graphmaquina.fixtureRepository"));

    @TempDir
    Path tempDirectory;

    @Test
    void publishedArtifactContainsAnExecutableDescriptor() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        MavenResult result = fixture.run(
                "io.github.graphmaquina:graphmaquina-maven-plugin:" + VERSION + ":generate");
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
        assertTrue(result.output().contains("[SG604]"), result.output());
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
     * The generator parses exactly the sources javac compiles: a source the {@code <excludes>} of the compiler
     * leave out — here a draft that does not even parse — is not read by the main generation, and one the
     * {@code <testExcludes>} leave out is not read by the test generation.
     */
    @Test
    void theGeneratorReadsOnlyTheSourcesTheCompilerCompiles() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<release>21</release>", "<release>21</release>\n"
                + "          <excludes><exclude>fx/drafts/**</exclude></excludes>\n"
                + "          <testExcludes><exclude>fxt/drafts/**</exclude></testExcludes>");
        Map<String, String> sources = MavenFixture.domainSources();
        sources.put("fx.drafts.Draft", draft("fx.drafts"));
        fixture.writeSources(sources);
        fixture.writeTestSource("fxt.drafts.Draft", draft("fxt.drafts"));

        MavenResult result = fixture.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
        assertFalse(fixture.jarContains("fx/drafts/Draft.class"));
        assertTrue(fixture.inventoryIsSealed("test"));
        assertFalse(fixture.exists("target/test-classes/fxt/drafts/Draft.class"));
    }

    /**
     * The test compilation of maven-compiler-plugin adds its {@code <testIncrementalExcludes>} to its
     * {@code <testExcludes>} when it selects the sources javac compiles, and so does the test generation: a draft they
     * leave out, which does not even parse, is not read, and a pattern of theirs that leaves out the carrier of the
     * test compilation is refused before anything is retired.
     */
    @Test
    void theTestIncrementalExcludesLeaveOutTheTestSourcesJavacLeavesOut() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        for (Map.Entry<String, String> source : testDomain().entrySet()) {
            fixture.writeTestSource(source.getKey(), source.getValue());
        }
        fixture.writeTestSource("fxt.drafts.Draft", draft("fxt.drafts"));
        String drafts = "<testIncrementalExclude>fxt/drafts/**</testIncrementalExclude>";
        fixture.editPom("<release>21</release>", "<release>21</release>\n"
                + "          <testIncrementalExcludes>" + drafts + "</testIncrementalExcludes>");

        MavenResult left = fixture.run("package");

        assertEquals(0, left.exitCode(), left.output());
        assertTrue(fixture.inventoryIsSealed("test"));
        assertFalse(fixture.exists("target/test-classes/fxt/drafts/Draft.class"));
        List<String> published = fixture.localExportsOf(TEST_EXPORTS);
        assertTrue(published.contains("fxt.Tickets"), published::toString);

        fixture.editPom(drafts, drafts + "<testIncrementalExclude>io/github/**</testIncrementalExclude>");

        MavenResult carrier = fixture.run("package");

        assertNotEquals(0, carrier.exitCode(), carrier.output());
        assertTrue(carrier.output().contains("the <testIncrementalExcludes> pattern io/github/** of the"
                + " default-testCompile execution of maven-compiler-plugin leaves out"
                + " io/github/graphmaquina/generated/bootstrap/GraphCompilation"), carrier.output());
        assertTrue(fixture.inventoryIsSealed("test"));
        assertEquals(published, fixture.localExportsOf(TEST_EXPORTS));
    }

    /**
     * The main compilation of maven-compiler-plugin compiles the sources its {@code <incrementalExcludes>} match, but
     * leaves them out of its check for stale sources, which decides whether it runs javac at all. The carrier, whose
     * class file the completion deletes, is what makes it compile the module again after the preparation: a pattern
     * that hides the carrier, here in a project whose generation emits nothing else, would let it skip javac. Such a
     * pattern is refused before anything is retired, while one that leaves the carrier in builds.
     */
    @Test
    void anIncrementalExcludeThatHidesTheCarrierIsRefusedBeforeAnythingIsRetired() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.removeAllStateGraphDeclarations();
        assertEquals(0, fixture.run("compile").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        String generated = "<incrementalExclude>**/generated/**</incrementalExclude>";
        fixture.editPom("<release>21</release>", "<release>21</release>\n"
                + "          <incrementalExcludes>" + generated + "</incrementalExcludes>");

        MavenResult hidden = fixture.run("compile");

        assertNotEquals(0, hidden.exitCode(), hidden.output());
        assertTrue(hidden.output().contains("the <incrementalExcludes> pattern **/generated/** of the default-compile"
                + " execution of maven-compiler-plugin leaves io/github/graphmaquina/generated/bootstrap/"
                + "GraphCompilation"), hidden.output());
        assertNothingWasRetired(fixture, published);

        fixture.editPom(generated, "<incrementalExclude>fx/drafts/**</incrementalExclude>");

        MavenResult left = fixture.run("compile");

        assertEquals(0, left.exitCode(), left.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));
    }

    /**
     * maven-compiler-plugin applies its includes and excludes to the directory the generator writes into as well,
     * so patterns that leave out the carrier of a compilation would let javac compile the vocabulary without it.
     * They are refused by the generation, before anything is retired, naming the pattern and what mends it: main
     * includes that select only the packages of the project, and a test exclude that drops the carrier package.
     */
    @Test
    void patternsThatLeaveOutTheCarrierAreRefusedBeforeAnythingIsRetired() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        // The carrier the generator emitted, named relative to its root as the compiler matches it.
        String carrier = fixture.projectDirectory().resolve("target/generated-sources/graphmaquina")
                .relativize(fixture.carrierSource()).toString();
        fixture.editPom("<release>21</release>",
                "<release>21</release>\n          <includes><include>fx/**</include></includes>");

        MavenResult includes = fixture.run("package");

        assertNotEquals(0, includes.exitCode(), includes.output());
        assertTrue(includes.output().contains("the <includes> of the default-compile execution of"
                + " maven-compiler-plugin, [fx/**], select no " + carrier), includes.output());
        assertTrue(includes.output().contains("Add <include>io/github/graphmaquina/generated/**</include> to the"
                + " <includes> of that execution."), includes.output());
        assertNothingWasRetired(fixture, published);

        fixture.editPom("<includes><include>fx/**</include></includes>",
                "<testExcludes><exclude>io/github/**</exclude></testExcludes>");

        MavenResult excludes = fixture.run("package");

        assertNotEquals(0, excludes.exitCode(), excludes.output());
        assertTrue(excludes.output().contains("the <testExcludes> pattern io/github/** of the default-testCompile"
                + " execution of maven-compiler-plugin leaves out io/github/graphmaquina/generated/bootstrap/"
                + "GraphCompilation"), excludes.output());
        assertTrue(fixture.inventoryIsSealed("test"));
    }

    /**
     * Includes that also select the sources the generator emits outside the packages of the project build and
     * package: the generator reads only the included sources — a draft outside them does not even parse — and
     * javac compiles the generated vocabulary together with its carrier.
     */
    @Test
    void includesThatSelectTheGeneratedSourcesBuildAndPackage() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<release>21</release>", "<release>21</release>\n"
                + "          <includes>\n"
                + "            <include>fx/**</include>\n"
                + "            <include>io/github/graphmaquina/generated/**</include>\n"
                + "          </includes>");
        Map<String, String> sources = MavenFixture.domainSources();
        sources.put("misc.Draft", draft("misc"));
        fixture.writeSources(sources);

        MavenResult result = fixture.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
        assertFalse(fixture.jarContains("misc/Draft.class"));
        assertFalse(fixture.jarContainsGraphCompilationCarrier());
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
        // The downstream machine identifies its edges with the producer's own enum, and neither jar carries a
        // nested type of the retired matrix vocabulary for it to have inherited instead.
        assertEquals(List.of("fx.Transition.APPROVE"), producer.transitionsOf("fx.Orders"));
        assertEquals(List.of("fx.Transition.APPROVE"), consumer.transitionsOf("cx.Reviews"));
        assertEquals(List.of(), producer.retiredVocabularyEntries());
        assertEquals(List.of(), consumer.retiredVocabularyEntries());
    }

    /**
     * A plugin configured once in a {@code pom} parent is inherited by its modules and runs in the parent too,
     * which compiles nothing: there every goal does nothing and says so, and the module builds as it would
     * on its own.
     */
    @Test
    void aPomParentSkipsTheExecutionsItsModulesInherit() throws IOException {
        MavenFixture parent = MavenFixture.basic(tempDirectory, fixtureRepository);
        MavenFixture module = parent.module();

        MavenResult result = parent.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("StateGraph skips fx.build:fixture:main: the project is packaged as"
                + " pom, which compiles no Java"), result.output());
        assertTrue(result.output().contains("StateGraph skips fx.build:fixture:test: the project is packaged as"
                + " pom, which compiles no Java"), result.output());
        assertFalse(parent.exists("target/graphmaquina"));
        assertTrue(module.inventoryIsSealed("main"));
        assertTrue(module.jarContains("fx/OrdersGraph.class"));
        assertEquals(List.of("fx.Orders", "fx.ActionsRefs.Approve", "fx.OrderRefs.Id", "fx.OrderRefs.Status"),
                module.localExports());
    }

    /**
     * {@code clean} is a complete migration path out of the retired release. Over stand-ins for the outputs a 1.x
     * build left, a schema 1 manifest and a binary of the retired matrix vocabulary, the build stops with
     * {@code SG602} before it generates anything and asks for {@code clean}; after {@code clean} the build
     * regenerates the vocabulary, compiles it and publishes the index of the shared domain, the jar carries nothing
     * of the retired vocabulary, and the hand-written sources are byte for byte what they were, since nothing of the
     * deletion ever reaches {@code src}.
     */
    @Test
    void cleanIsACompleteMigrationPath() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.writeFile("target/graphmaquina/main/annotation-manifest.json",
                "{\n  \"schemaVersion\": 1\n}\n".getBytes(StandardCharsets.UTF_8));
        fixture.writeFile("target/classes/fx/OrdersGraph$Row.class",
                new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
        Map<String, String> sources = handWrittenSources(fixture);

        MavenResult retired = fixture.run("package");

        assertNotEquals(0, retired.exitCode(), retired.output());
        assertTrue(retired.output().contains("[SG602]"), retired.output());
        assertTrue(retired.output().contains("is the retired format of GraphMaquina 1.x; run clean"),
                retired.output());
        assertFalse(fixture.exists("target/generated-sources/graphmaquina"), retired.output());

        MavenResult result = fixture.run("clean", "package");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(List.of("fx.Orders", "fx.ActionsRefs.Approve", "fx.OrderRefs.Id", "fx.OrderRefs.Status"),
                fixture.localExports());
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
        assertTrue(fixture.jarContains("fx/OrdersRuntime.class"));
        assertEquals(List.of(), fixture.retiredVocabularyEntries());
        assertFalse(fixture.jarContainsGraphCompilationCarrier());
        assertEquals(sources, handWrittenSources(fixture));
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
     * The generator runs under the {@code java} the build selected for it, not under the default the JVM running
     * Maven provides. The selection is that very JDK reached through a link of its own — javac compiles inside
     * that JVM, so the generator must be on the same JDK — and only its path tells it apart from the default.
     */
    @Test
    void runsTheGeneratorUnderTheSelectedJavaExecutable() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        Path linked = Files.createSymbolicLink(tempDirectory.resolve("linked-jdk"),
                Path.of(System.getProperty("java.home")));
        String selected = linked.resolve("bin").resolve("java").toString();

        MavenResult result = fixture.run("-X", "-Dgraphmaquina.javaExecutable=" + selected, "generate-sources");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("running the StateGraph generator: " + selected + " -cp"),
                "the generator did not run under " + selected);
        assertTrue(fixture.exists("target/graphmaquina/main/annotation-manifest.json"));
        // The link leads out of the temporary directory, which JUnit warns about when it deletes that directory.
        Files.delete(linked);
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

        MavenResult result = fixture.run("io.github.graphmaquina:graphmaquina-maven-plugin:" + VERSION + ":complete");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("StateGraph completes nothing for fx.build:fixture:main"),
                result.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));
    }

    /**
     * The annotation processor is not optional: it is the only thing that publishes the index of what javac
     * accepted, so a compilation that would run without it — because annotation processing is off, or because
     * the processor is on no path javac reads — is refused by {@code generate}, before the preparation retires
     * the outputs of the previous cycle.
     */
    @Test
    void theAnnotationProcessorMustBeOnTheCompilerPath() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);

        MavenResult off = fixture.run("-Dmaven.compiler.proc=none", "package");

        assertNotEquals(0, off.exitCode(), off.output());
        assertTrue(off.output().contains("maven.compiler.proc=none switches annotation processing off"),
                off.output());
        assertNothingWasRetiredOrPublished(fixture);

        fixture.editPom("""
                            <path>
                              <groupId>io.github.graphmaquina</groupId>
                              <artifactId>graphmaquina-processor</artifactId>
                              <version>${graphmaquina.version}</version>
                            </path>
                """, "");

        MavenResult missing = fixture.run("package");

        assertNotEquals(0, missing.exitCode(), missing.output());
        assertTrue(missing.output().contains("io.github.graphmaquina:graphmaquina-processor is not on the"
                + " <annotationProcessorPaths> of the default-compile execution"), missing.output());
        assertNothingWasRetiredOrPublished(fixture);
    }

    /**
     * The refusal of a missing processor comes from the generation, before the preparation: it says what the
     * preparation would retire, not that anything has been retired, and the index and the sealed inventory of
     * the previous build are still there afterwards.
     */
    @Test
    void aMissingProcessorIsRefusedBeforeTheIndexIsRetired() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        fixture.editPom("""
                            <path>
                              <groupId>io.github.graphmaquina</groupId>
                              <artifactId>graphmaquina-processor</artifactId>
                              <version>${graphmaquina.version}</version>
                            </path>
                """, "");

        MavenResult result = fixture.run("package");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("and the preparation that follows would retire the index of the"
                + " previous compilation"), result.output());
        assertFalse(result.output().contains("has already retired"), result.output());
        assertNothingWasRetired(fixture, published);
    }

    /**
     * maven-compiler-plugin treats an {@code <annotationProcessorPaths>} that holds no path as one that is absent: it
     * gives javac no processor path, so javac finds the annotation processors on the class path, among the
     * dependencies of the project. The check reads it the same way, so a processor that is a dependency of the project
     * builds and publishes. {@code <proc>full</proc>} is configured as well, since from Java 23 on javac runs a
     * processor found on the class path only when annotation processing is requested.
     */
    @Test
    void anEmptyProcessorPathIsNoProcessorPath() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        processorAsDependency(fixture, "<annotationProcessorPaths/>\n          <proc>full</proc>");

        MavenResult result = fixture.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(List.of("fx.Orders", "fx.ActionsRefs.Approve", "fx.OrderRefs.Id", "fx.OrderRefs.Status"),
                fixture.localExports());
    }

    /**
     * From Java 23 on, javac runs no annotation processor it finds on the class path unless annotation processing is
     * requested: by {@code -proc:full}, by a {@code -processor} list or by a processor path. A StateGraph processor
     * that is only a dependency of the project is therefore refused by the generation when such a javac compiles,
     * before anything is retired, naming the settings that make javac run it; {@code <proc>full</proc>}, a
     * {@code -processor} list that names it and a processor path that a compiler argument gives javac each build and
     * publish the same index. The test is skipped on a JDK below 23, whose javac runs such a processor anyway.
     */
    @Test
    void aProcessorOnlyOnTheClassPathIsRefusedWhereJavacWouldNotRunIt() throws IOException {
        assumeTrue(Runtime.version().feature() >= 23, "a javac below 23 runs a processor it finds on the class path");
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        processorAsDependency(fixture, "");

        MavenResult implicit = fixture.run("package");

        assertNotEquals(0, implicit.exitCode(), implicit.output());
        assertTrue(implicit.output().contains("io.github.graphmaquina:graphmaquina-processor is only a dependency of"
                + " this project, and the default-compile execution of maven-compiler-plugin, which declares no path"
                + " in <annotationProcessorPaths>, compiles with the JDK running Maven"), implicit.output());
        assertTrue(implicit.output().contains("a JDK of Java " + Runtime.version().feature() + ": from Java 23 on,"
                + " javac runs no annotation processor it finds on the class path unless annotation processing is"
                + " requested"), implicit.output());
        assertTrue(implicit.output().contains("Configure <proc>full</proc> for that execution, or declare the"
                + " processor in <annotationProcessorPaths> of that execution"), implicit.output());
        assertNothingWasRetired(fixture, published);

        fixture.editPom("<release>21</release>", "<release>21</release>\n          <proc>full</proc>");

        MavenResult full = fixture.run("package");

        assertEquals(0, full.exitCode(), full.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));

        List<String> withStateGraph = new ArrayList<>(LOMBOK_PROCESSORS);
        withStateGraph.add(STATEGRAPH_PROCESSOR);
        String list = "<annotationProcessors>" + annotationProcessors(withStateGraph) + "</annotationProcessors>";
        fixture.editPom("<proc>full</proc>", list);

        MavenResult listed = fixture.run("package");

        assertEquals(0, listed.exitCode(), listed.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));

        // A processor path given as a compiler argument requests annotation processing too, and javac then finds the
        // processors on that path alone: Lombok, then the StateGraph processor with what it runs on.
        fixture.editPom(list, "");
        extraArguments(fixture, List.of(), List.of("-processorpath", processorPath(true)));

        MavenResult path = fixture.run("package");

        assertEquals(0, path.exitCode(), path.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));
    }

    /**
     * A processor path given as a compiler argument, {@code -processorpath} or {@code --processor-path}, with a space
     * or an {@code =}, is the only place javac looks for annotation processors, over {@code <annotationProcessorPaths>}
     * and over the class path, and of several such arguments javac takes the last one. So the check reads that last
     * argument, evaluated as Maven evaluates it for the compiler, which completes {@code ${settings.localRepository}},
     * and looks the processor up as the processor class loader of javac does. A path that does not reach the
     * StateGraph processor is refused before anything is retired, even with the processor among the dependencies; one
     * that reaches it builds and publishes: with the processor among no dependency, over an
     * {@code <annotationProcessorPaths>} that holds Lombok alone, through the {@code Class-Path} of a jar's manifest,
     * and from a multi-release jar that holds it only for Java 21 and later. A path argument that is one expression
     * naming an object Maven does not take for a {@code String}, such as {@code ${project.file}}, is left out, as Maven
     * leaves it out, so the argument after it is the path javac takes, and is refused. A processor module path is
     * refused, which StateGraph does not support.
     */
    @Test
    void aProcessorPathArgumentIsTheOnlyPlaceJavacLooksForTheProcessor() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        String lombok = processorPath(false);
        String full = processorPath(true);
        processorAsDependency(fixture, "");
        List<String> lombokLast = List.of("-processorpath", full, "-processorpath", lombok);
        extraArguments(fixture, List.of(), lombokLast);

        MavenResult lombokOnly = fixture.run("package");

        assertNotEquals(0, lombokOnly.exitCode(), lombokOnly.output());
        assertTrue(lombokOnly.output().contains("the compiler argument -processorpath " + lombok + " of the"
                + " default-compile execution of maven-compiler-plugin gives javac the processor path it takes, the"
                + " only place it then looks for annotation processors, and that path reaches no"
                + " io/github/graphmaquina/processor/StateGraphProcessor.class"), lombokOnly.output());
        assertNothingWasRetired(fixture, published);

        fixture.editPom("""
                    <dependency>
                      <groupId>io.github.graphmaquina</groupId>
                      <artifactId>graphmaquina-processor</artifactId>
                      <version>${graphmaquina.version}</version>
                      <scope>provided</scope>
                    </dependency>
                """, "");
        List<String> fullLast = List.of("-processorpath", lombok, "--processor-path", full);
        extraArguments(fixture, lombokLast, fullLast);

        MavenResult pathOnly = fixture.run("package");

        assertEquals(0, pathOnly.exitCode(), pathOnly.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));

        fixture.editPom("<release>21</release>", "<release>21</release>\n" + PROCESSOR_PATHS.replace("""
                            <path>
                              <groupId>io.github.graphmaquina</groupId>
                              <artifactId>graphmaquina-processor</artifactId>
                              <version>${graphmaquina.version}</version>
                            </path>
                """, ""));
        List<String> joined = List.of("--processor-path=" + full);
        extraArguments(fixture, fullLast, joined);

        MavenResult overPaths = fixture.run("package");

        assertEquals(0, overPaths.exitCode(), overPaths.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));

        String repository = requireProperty("graphmaquina.mavenLocalRepository");
        List<String> expressionFull = List.of("--processor-path="
                + full.replace(repository, "${settings.localRepository}"));
        extraArguments(fixture, joined, expressionFull);

        MavenResult expression = fixture.run("package");

        assertEquals(0, expression.exitCode(), expression.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));

        List<String> expressionLombok = List.of("--processor-path="
                + lombok.replace(repository, "${settings.localRepository}"));
        extraArguments(fixture, expressionFull, expressionLombok);

        MavenResult evaluated = fixture.run("package");

        assertNotEquals(0, evaluated.exitCode(), evaluated.output());
        assertTrue(evaluated.output().contains("the compiler argument --processor-path=" + lombok + " of the"
                + " default-compile execution of maven-compiler-plugin gives javac the processor path it takes"),
                evaluated.output());
        assertNothingWasRetired(fixture, published);

        List<String> pathing = List.of("-processorpath", pathingJar(tempDirectory.resolve("pathing.jar"), full)
                .toString());
        extraArguments(fixture, expressionLombok, pathing);

        MavenResult manifestClassPath = fixture.run("package");

        assertEquals(0, manifestClassPath.exitCode(), manifestClassPath.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));

        Path processorJar = Path.of(repository).resolve("io/github/graphmaquina/graphmaquina-processor/" + VERSION
                + "/graphmaquina-processor-" + VERSION + ".jar");
        Path versionedJar = multiReleaseJar(processorJar, tempDirectory.resolve("versioned processor.jar"), 21);
        List<String> versioned = List.of("-processorpath", full.replace(processorJar.toString(),
                versionedJar.toString()));
        extraArguments(fixture, pathing, versioned);

        MavenResult multiRelease = fixture.run("package");

        assertEquals(0, multiRelease.exitCode(), multiRelease.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));

        // Maven evaluates an argument that is one expression naming an object it does not take for a String to
        // nothing, and leaves it out, so javac takes the argument after it as the processor path.
        List<String> dropped = List.of("-processorpath", "${project.file}");
        extraArguments(fixture, versioned, dropped);

        MavenResult nothing = fixture.run("package");

        assertNotEquals(0, nothing.exitCode(), nothing.output());
        assertTrue(nothing.output().contains("the compiler argument -processorpath -Xlint:all,-serial of the"
                + " default-compile execution of maven-compiler-plugin gives javac the processor path it takes"),
                nothing.output());
        assertNothingWasRetired(fixture, published);

        extraArguments(fixture, dropped, List.of("--processor-module-path", full));

        MavenResult modules = fixture.run("package");

        assertNotEquals(0, modules.exitCode(), modules.output());
        assertTrue(modules.output().contains("the compiler argument --processor-module-path " + full + " of the"
                + " default-compile execution of maven-compiler-plugin makes javac look for annotation processors on"
                + " a module path, which StateGraph does not support"), modules.output());
        assertNothingWasRetired(fixture, published);
    }

    /**
     * Maven evaluates the compiler arguments when the compiler runs, so a property a plugin sets after
     * {@code generate} has run still reaches javac. A processor path argument that is one such property, not yet set
     * when {@code generate} checks it, is left as it is written and unchecked: here build-helper-maven-plugin sets it
     * in {@code process-sources} to a path that reaches the processor, and the build publishes the same index.
     */
    @Test
    void aProcessorPathPropertySetAfterGenerateIsLeftUnchecked() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        fixture.editPom("<plugins>", "<plugins>" + """
                      <plugin>
                        <groupId>org.codehaus.mojo</groupId>
                        <artifactId>build-helper-maven-plugin</artifactId>
                        <version>3.5.0</version>
                        <executions>
                          <execution>
                            <id>late-processor-path</id>
                            <phase>process-sources</phase>
                            <goals><goal>regex-property</goal></goals>
                            <configuration>
                              <name>late.processor.path</name>
                              <value>%s</value>
                              <regex>^(.*)$</regex>
                              <replacement>$1</replacement>
                            </configuration>
                          </execution>
                        </executions>
                      </plugin>
                """.formatted(processorPath(true)).stripTrailing());
        extraArguments(fixture, List.of(), List.of("-processorpath", "${late.processor.path}"));

        MavenResult late = fixture.run("package");

        assertEquals(0, late.exitCode(), late.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));
    }

    /**
     * javac reads a multi-release jar on its processor path for the release of its own JDK, which need not be the
     * release of the JDK running Maven. So a processor path whose jar holds the processor only for Java 22 and later
     * is refused, before anything is retired, for a forked javac whose JDK declares Java 21, whatever JDK runs Maven:
     * here the one running these tests, which also runs that javac.
     */
    @Test
    void aForkedJavacReadsTheProcessorPathForTheReleaseOfItsOwnJdk() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        Path jdk21 = fakeJdk("jdk-21", "21");
        fixture.editPom("<release>21</release>",
                "<release>21</release><fork>true</fork><executable>" + jdk21.resolve("bin/javac") + "</executable>");
        Path processorJar = Path.of(requireProperty("graphmaquina.mavenLocalRepository"))
                .resolve("io/github/graphmaquina/graphmaquina-processor/" + VERSION + "/graphmaquina-processor-"
                        + VERSION + ".jar");
        Path versionedJar = multiReleaseJar(processorJar, tempDirectory.resolve("processor for 22.jar"), 22);
        extraArguments(fixture, List.of(), List.of("-processorpath",
                processorPath(true).replace(processorJar.toString(), versionedJar.toString())));

        MavenResult forked = fixture.run("-Dgraphmaquina.javaExecutable=" + jdk21.resolve("bin/java"), "package");

        assertNotEquals(0, forked.exitCode(), forked.output());
        assertTrue(forked.output().contains("of the default-compile execution of maven-compiler-plugin gives javac"
                + " the processor path it takes, the only place it then looks for annotation processors, and that"
                + " path reaches no io/github/graphmaquina/processor/StateGraphProcessor.class"), forked.output());
        assertNothingWasRetired(fixture, published);
    }

    /**
     * javac runs only the annotation processors a {@code -processor} list names. maven-compiler-plugin turns
     * {@code <annotationProcessors>} into such a list ahead of the compiler arguments, even when it names nothing,
     * and javac takes the last list it is given, so a {@code -processor} compiler argument wins over it. A list that
     * leaves out the StateGraph processor, in either form and an empty {@code <annotationProcessors>} included, is
     * refused by the generation before the preparation retires anything, naming the list javac would take; a list
     * that names it builds and publishes the same index.
     */
    @Test
    void aProcessorListWithoutTheStateGraphProcessorIsRefusedBeforeAnythingIsRetired() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        List<String> withStateGraph = new ArrayList<>(LOMBOK_PROCESSORS);
        withStateGraph.add(STATEGRAPH_PROCESSOR);
        String lombokOnly = String.join(",", LOMBOK_PROCESSORS);
        fixture.editPom("<release>21</release>", "<release>21</release>\n          <annotationProcessors/>");

        MavenResult empty = fixture.run("package");

        assertNotEquals(0, empty.exitCode(), empty.output());
        assertTrue(empty.output().contains("the <annotationProcessors> of the default-compile execution of"
                + " maven-compiler-plugin give javac the processor list '', whose name '' is not a valid qualified"
                + " class name"), empty.output());
        assertNothingWasRetired(fixture, published);

        fixture.editPom("<annotationProcessors/>", "<annotationProcessors>" + annotationProcessors(LOMBOK_PROCESSORS)
                + "</annotationProcessors>");

        MavenResult listed = fixture.run("package");

        assertNotEquals(0, listed.exitCode(), listed.output());
        assertTrue(listed.output().contains("the <annotationProcessors> of the default-compile execution of"
                + " maven-compiler-plugin give javac the processor list '" + lombokOnly + "', which leaves out "
                + STATEGRAPH_PROCESSOR), listed.output());
        assertNothingWasRetired(fixture, published);

        fixture.editPom(annotationProcessors(LOMBOK_PROCESSORS), annotationProcessors(withStateGraph));

        MavenResult named = fixture.run("package");

        assertEquals(0, named.exitCode(), named.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));

        fixture.editPom("graphmaquina/main/annotation-manifest.json</arg>",
                "graphmaquina/main/annotation-manifest.json</arg>\n                <arg>-processor</arg>\n"
                        + "                <arg>" + lombokOnly + "</arg>");

        MavenResult argument = fixture.run("package");

        assertNotEquals(0, argument.exitCode(), argument.output());
        assertTrue(argument.output().contains("the compiler argument -processor of the default-compile execution"
                + " of maven-compiler-plugin gives javac the processor list '" + lombokOnly + "', which leaves out "
                + STATEGRAPH_PROCESSOR), argument.output());
        assertNothingWasRetired(fixture, published);

        fixture.editPom("<arg>" + lombokOnly + "</arg>", "<arg>" + String.join(",", withStateGraph) + "</arg>");

        MavenResult argumentNamed = fixture.run("package");

        assertEquals(0, argumentNamed.exitCode(), argumentNamed.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));
    }

    /**
     * The list javac gets from {@code <annotationProcessors>} is the one Maven and maven-compiler-plugin build:
     * Maven splits a text value on every comma into the names of the list, plexus joins the names with commas into
     * one {@code -processor} value, and javac splits that value on every comma, dropping the empty names at its
     * end, and takes each name exactly as written. So a text value and one element holding a comma-separated list
     * that name the StateGraph processor build, the same forms without it are refused, a {@code -processor} list
     * ending with a comma builds, and a name with a space in it, which is not a valid qualified class name, is refused
     * before anything is retired.
     */
    @Test
    void aProcessorListIsReadTheWayJavacGetsIt() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        String lombokOnly = String.join(",", LOMBOK_PROCESSORS);
        fixture.editPom("<release>21</release>", "<release>21</release>\n          <annotationProcessors>"
                + lombokOnly + "," + STATEGRAPH_PROCESSOR + "</annotationProcessors>");

        MavenResult text = fixture.run("package");

        assertEquals(0, text.exitCode(), text.output());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        assertTrue(published.contains("fx.Orders"), published::toString);

        fixture.editPom("," + STATEGRAPH_PROCESSOR + "</annotationProcessors>", "</annotationProcessors>");

        MavenResult textWithout = fixture.run("package");

        assertNotEquals(0, textWithout.exitCode(), textWithout.output());
        assertTrue(textWithout.output().contains("the <annotationProcessors> of the default-compile execution of"
                + " maven-compiler-plugin give javac the processor list '" + lombokOnly + "', which leaves out "
                + STATEGRAPH_PROCESSOR), textWithout.output());
        assertNothingWasRetired(fixture, published);

        fixture.editPom("<annotationProcessors>" + lombokOnly + "</annotationProcessors>",
                "<annotationProcessors><annotationProcessor>" + lombokOnly + "," + STATEGRAPH_PROCESSOR
                        + "</annotationProcessor></annotationProcessors>");

        MavenResult element = fixture.run("package");

        assertEquals(0, element.exitCode(), element.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));

        fixture.editPom("," + STATEGRAPH_PROCESSOR + "</annotationProcessor>", "</annotationProcessor>");

        MavenResult elementWithout = fixture.run("package");

        assertNotEquals(0, elementWithout.exitCode(), elementWithout.output());
        assertTrue(elementWithout.output().contains("give javac the processor list '" + lombokOnly + "', which"
                + " leaves out " + STATEGRAPH_PROCESSOR), elementWithout.output());
        assertNothingWasRetired(fixture, published);

        String trailing = lombokOnly + "," + STATEGRAPH_PROCESSOR + ",";
        fixture.editPom("<annotationProcessors><annotationProcessor>" + lombokOnly
                + "</annotationProcessor></annotationProcessors>", "");
        fixture.editPom("graphmaquina/main/annotation-manifest.json</arg>",
                "graphmaquina/main/annotation-manifest.json</arg>\n                <arg>-processor</arg>\n"
                        + "                <arg>" + trailing + "</arg>");

        MavenResult trailingComma = fixture.run("package");

        assertEquals(0, trailingComma.exitCode(), trailingComma.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));

        String spaced = LOMBOK_PROCESSORS.get(0) + ", " + STATEGRAPH_PROCESSOR;
        fixture.editPom("<arg>" + trailing + "</arg>", "<arg>" + spaced + "</arg>");

        MavenResult spaces = fixture.run("package");

        assertNotEquals(0, spaces.exitCode(), spaces.output());
        assertTrue(spaces.output().contains("the compiler argument -processor of the default-compile execution of"
                + " maven-compiler-plugin gives javac the processor list '" + spaced + "', whose name ' "
                + STATEGRAPH_PROCESSOR + "' is not a valid qualified class name"), spaces.output());
        assertNothingWasRetired(fixture, published);
    }

    /**
     * javac takes the last {@code -proc:} option and the last {@code -processor} list it is given, and so do the
     * checks: {@code -proc:none} followed by {@code -proc:full} builds, while {@code -proc:full} followed by
     * {@code -proc:none} is refused, and a {@code -processor} list that names the StateGraph processor followed by
     * one that does not is refused, each before anything is retired.
     */
    @Test
    void theLastProcessingOptionAndTheLastProcessorListAreTheOnesJavacTakes() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        String manifest = "graphmaquina/main/annotation-manifest.json</arg>";
        fixture.editPom(manifest,
                manifest + "\n                <arg>-proc:none</arg>\n                <arg>-proc:full</arg>");

        MavenResult full = fixture.run("package");

        assertEquals(0, full.exitCode(), full.output());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        assertTrue(published.contains("fx.Orders"), published::toString);

        fixture.editPom("<arg>-proc:none</arg>\n                <arg>-proc:full</arg>",
                "<arg>-proc:full</arg>\n                <arg>-proc:none</arg>");

        MavenResult none = fixture.run("package");

        assertNotEquals(0, none.exitCode(), none.output());
        assertTrue(none.output().contains("the compiler argument -proc:none switches annotation processing off for"
                + " the default-compile execution of maven-compiler-plugin"), none.output());
        assertNothingWasRetired(fixture, published);

        String lombokOnly = String.join(",", LOMBOK_PROCESSORS);
        fixture.editPom("<arg>-proc:full</arg>\n                <arg>-proc:none</arg>",
                "<arg>-processor</arg>\n                <arg>" + lombokOnly + "," + STATEGRAPH_PROCESSOR + "</arg>\n"
                        + "                <arg>-processor</arg>\n                <arg>" + lombokOnly + "</arg>");

        MavenResult lists = fixture.run("package");

        assertNotEquals(0, lists.exitCode(), lists.output());
        assertTrue(lists.output().contains("gives javac the processor list '" + lombokOnly + "', which leaves out "
                + STATEGRAPH_PROCESSOR), lists.output());
        assertNothingWasRetired(fixture, published);
    }

    /**
     * Maven converts the deprecated {@code <compilerArguments>} map into a sorted map, so maven-compiler-plugin
     * passes its entries by key, each key once with its last value, and the checks read them the same way: of two
     * manifest arguments the last one is the one javac reads, and of {@code proc:none} and {@code proc:full},
     * written in that order, javac gets {@code -proc:none} last, which is refused before anything is retired.
     */
    @Test
    void theCompilerArgumentsMapIsReadByKeyWithTheLastValueOfEachKey() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<arg>-Agraphmaquina.annotationManifest=${project.build.directory}/graphmaquina/main/"
                + "annotation-manifest.json</arg>\n", "");
        fixture.editPom("<id>default-compile</id>\n            <configuration>",
                "<id>default-compile</id>\n            <configuration>\n"
                        + "              <compilerArguments>\n"
                        + "                <Agraphmaquina.annotationManifest>${project.build.directory}/graphmaquina/"
                        + "elsewhere/annotation-manifest.json</Agraphmaquina.annotationManifest>\n"
                        + "                <Agraphmaquina.annotationManifest>${project.build.directory}/graphmaquina/"
                        + "main/annotation-manifest.json</Agraphmaquina.annotationManifest>\n"
                        + "              </compilerArguments>");

        MavenResult last = fixture.run("compile");

        assertEquals(0, last.exitCode(), last.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        assertTrue(published.contains("fx.Orders"), published::toString);

        fixture.editPom("              </compilerArguments>",
                "                <proc:none/>\n                <proc:full/>\n              </compilerArguments>");

        MavenResult sorted = fixture.run("compile");

        assertNotEquals(0, sorted.exitCode(), sorted.output());
        assertTrue(sorted.output().contains("the compiler argument -proc:none switches annotation processing off"),
                sorted.output());
        assertNothingWasRetired(fixture, published);
    }

    /**
     * Maven also accepts a list parameter written as text, which it splits on every comma: {@code <compilerArgs>}
     * holding the manifest argument and {@code -Werror} that way gives javac both arguments, so the manifest check
     * reads it there and the compilation builds, and a {@code <testExcludes>} written that way, which leaves out the
     * carrier of the test compilation, is refused by the generation of that compilation. {@code <includes>} holding
     * nothing but empty values gets no default include from maven-compiler-plugin, so it selects no source, the
     * carrier included, and is refused as well, before the preparation retires anything.
     */
    @Test
    void listParametersWrittenAsTextAreReadAsMavenSplitsThem() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("""
                              <compilerArgs>
                                <arg>-Agraphmaquina.annotationManifest=${project.build.directory}/graphmaquina/main/\
                annotation-manifest.json</arg>
                                <arg>-Xlint:all,-serial</arg>
                                <arg>-Werror</arg>
                              </compilerArgs>
                """, """
                              <compilerArgs>-Agraphmaquina.annotationManifest=${project.build.directory}/graphmaquina/\
                main/annotation-manifest.json,-Werror</compilerArgs>
                """);

        MavenResult text = fixture.run("compile");

        assertEquals(0, text.exitCode(), text.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        fixture.editPom("<release>21</release>",
                "<release>21</release>\n          <testExcludes>io/github/**</testExcludes>");

        MavenResult excludes = fixture.run("test-compile");

        assertNotEquals(0, excludes.exitCode(), excludes.output());
        assertTrue(excludes.output().contains("the <testExcludes> pattern io/github/** of the default-testCompile"
                + " execution of maven-compiler-plugin leaves out io/github/graphmaquina/generated/bootstrap/"
                + "GraphCompilation"), excludes.output());

        fixture.editPom("<testExcludes>io/github/**</testExcludes>", "<includes>,</includes>");

        MavenResult empty = fixture.run("compile");

        assertNotEquals(0, empty.exitCode(), empty.output());
        assertTrue(empty.output().contains("the <includes> of the default-compile execution of maven-compiler-plugin"
                + " hold only empty values"), empty.output());
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
        String goal = "io.github.graphmaquina:graphmaquina-maven-plugin:" + VERSION + ':';

        MavenResult result = fixture.run(goal + "generate", goal + "prepare", goal + "complete");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("The StateGraph annotation processor did not run"),
                result.output());
        assertTrue(fixture.exists("target/graphmaquina/main/owned-outputs.pending.json"));
        assertFalse(fixture.inventoryIsSealed("main"));
        assertFalse(fixture.exists(MAIN_EXPORTS));
    }

    /**
     * A compiler setting that comes from a {@code maven.compiler.*} property is checked the same way as one
     * configured in XML, whether it is a release below Java 21 or a javac forked at another JDK, and a project
     * that configures no release at all is refused as well: the default source and target of
     * {@code maven-compiler-plugin} are far below what the generated vocabulary parses.
     */
    @Test
    void propertyConfiguredCompilerSettingsAreCheckedToo() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<release>21</release>", "");
        fixture.editPom("<lombok.version>1.18.48</lombok.version>",
                "<lombok.version>1.18.48</lombok.version>\n"
                        + "    <maven.compiler.release>17</maven.compiler.release>");

        MavenResult property = fixture.run("generate-sources");

        assertNotEquals(0, property.exitCode(), property.output());
        assertTrue(property.output().contains("compiles with maven.compiler.release=17"), property.output());

        // Back to the supported configuration, then take the release away entirely.
        fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<release>21</release>", "");

        MavenResult absent = fixture.run("generate-sources");

        assertNotEquals(0, absent.exitCode(), absent.output());
        assertTrue(absent.output().contains("configures no release"), absent.output());

        fixture = MavenFixture.basic(tempDirectory, fixtureRepository);

        MavenResult forked = fixture.run("-Dmaven.compiler.fork=true",
                "-Dmaven.compiler.executable=/nonexistent/javac", "generate-sources");

        assertNotEquals(0, forked.exitCode(), forked.output());
        assertTrue(forked.output().contains("forks javac at /nonexistent/javac"), forked.output());
    }

    /**
     * A compiler property set both for the JVM running Maven and in the POM is read the way Maven evaluates the
     * compiler's own parameter: the property of the session wins over the property of the project, so the
     * release this check reads is the release javac compiles with.
     */
    @Test
    void aJvmSystemPropertyWinsOverTheProjectProperty() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<release>21</release>", "");
        fixture.editPom("<lombok.version>1.18.48</lombok.version>",
                "<lombok.version>1.18.48</lombok.version>\n"
                        + "    <maven.compiler.release>17</maven.compiler.release>");
        // A project sets a system property of the JVM running its build here, as MAVEN_OPTS does.
        Path options = Files.createDirectories(fixture.projectDirectory().resolve(".mvn")).resolve("jvm.config");
        Files.writeString(options, "-Dmaven.compiler.release=21\n", StandardCharsets.UTF_8);

        MavenResult result = fixture.run("compile");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertTrue(fixture.exists("target/classes/fx/OrdersGraph.class"));
    }

    /**
     * Maven merges the configuration the compiler declares for all of its executions into each execution, and
     * a list the execution declares itself replaces the plugin's: the test compilation of this POM compiles
     * with its own arguments alone, so the main manifest the plugin level names is not one it reads.
     */
    @Test
    void anExecutionsCompilerArgumentsReplaceThoseOfThePlugin() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        String mainArguments = """
                          <compilerArgs>
                            <arg>-Agraphmaquina.annotationManifest=${project.build.directory}/graphmaquina/main/\
                annotation-manifest.json</arg>
                            <arg>-Xlint:all,-serial</arg>
                            <arg>-Werror</arg>
                          </compilerArgs>
                """;
        fixture.editPom("""
                          <execution>
                            <id>default-compile</id>
                            <configuration>
                """ + mainArguments.indent(4) + """
                            </configuration>
                          </execution>
                """, "");
        fixture.editPom("<release>21</release>\n", "<release>21</release>\n" + mainArguments);

        MavenResult result = fixture.run("test-compile");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertTrue(fixture.inventoryIsSealed("test"));
    }

    /**
     * maven-compiler-plugin still passes the entries of the deprecated {@code <compilerArguments>} map to javac,
     * with a dash prepended to a key that has none, and the test compilation takes {@code <testCompilerArguments>}
     * over that map.
     * The checks of the generation read the same arguments: a manifest argument given only as an entry of the map
     * is the one javac reads, so both compilations build, and a {@code proc:none} entry switches annotation
     * processing off and is refused before anything is retired.
     */
    @Test
    void theCompilerArgumentsMapIsReadTheWayTheCompilerReadsIt() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        for (String sourceSet : List.of("main", "test")) {
            fixture.editPom("<arg>-Agraphmaquina.annotationManifest=${project.build.directory}/graphmaquina/"
                    + sourceSet + "/annotation-manifest.json</arg>\n", "");
        }
        fixture.editPom("<release>21</release>", "<release>21</release>\n"
                + "          <compilerArguments>\n"
                + "            <Agraphmaquina.annotationManifest>${project.build.directory}/graphmaquina/main/"
                + "annotation-manifest.json</Agraphmaquina.annotationManifest>\n"
                + "          </compilerArguments>");
        fixture.editPom("<id>default-testCompile</id>\n            <configuration>",
                "<id>default-testCompile</id>\n            <configuration>\n"
                        + "              <testCompilerArguments>\n"
                        + "                <Agraphmaquina.annotationManifest>${project.build.directory}/graphmaquina/"
                        + "test/annotation-manifest.json</Agraphmaquina.annotationManifest>\n"
                        + "              </testCompilerArguments>");

        MavenResult mapped = fixture.run("test-compile");

        assertEquals(0, mapped.exitCode(), mapped.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertTrue(fixture.inventoryIsSealed("test"));
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        assertTrue(published.contains("fx.Orders"), published::toString);
        fixture.editPom("<compilerArguments>\n", "<compilerArguments>\n            <proc:none/>\n");

        MavenResult off = fixture.run("test-compile");

        assertNotEquals(0, off.exitCode(), off.output());
        assertTrue(off.output().contains("the compiler argument -proc:none switches annotation processing off for"
                + " the default-compile execution of maven-compiler-plugin"), off.output());
        assertNothingWasRetired(fixture, published);
    }

    /**
     * The two javac executions name the directory of the annotation processor's sources with two different
     * parameters, and each generation reads its own: a redirected test directory is reported by
     * {@code generate-test}, and a main directory set to its default is not reported by either.
     */
    @Test
    void eachCompilationChecksItsOwnGeneratedSourcesDirectory() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<release>21</release>", "<release>21</release>\n"
                + "          <generatedTestSourcesDirectory>${project.build.directory}/elsewhere"
                + "</generatedTestSourcesDirectory>");

        MavenResult redirected = fixture.run("generate-test-sources");

        assertNotEquals(0, redirected.exitCode(), redirected.output());
        List<String> executed = goalExecutions(redirected.output());
        assertTrue(indexOf(executed, ":generate (sg-generate)") >= 0, executed.toString());
        assertTrue(indexOf(executed, ":generate-test (sg-generate-test)") >= 0, executed.toString());
        assertTrue(redirected.output().contains("Failed to execute goal io.github.graphmaquina:"
                + "graphmaquina-maven-plugin:" + VERSION + ":generate-test (sg-generate-test)"),
                redirected.output());
        assertTrue(redirected.output().contains("<generatedTestSourcesDirectory>"), redirected.output());

        fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<release>21</release>", "<release>21</release>\n"
                + "          <generatedSourcesDirectory>${project.build.directory}/generated-sources/"
                + "annotations</generatedSourcesDirectory>");

        MavenResult supported = fixture.run("generate-test-sources");

        assertEquals(0, supported.exitCode(), supported.output());
    }

    /**
     * Every generation emits the carrier of its compilation, and the completion needs the class javac compiles from
     * it. A preparation that finds no carrier under the generated sources, here because it was deleted after the
     * generation, fails and says so, and asks for the generation to run again.
     */
    @Test
    void aPreparationThatFindsNoCarrierAsksForTheGenerationAgain() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        String goal = "io.github.graphmaquina:graphmaquina-maven-plugin:" + VERSION + ':';
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
     * A build that switched a compilation off skips the StateGraph goals of that compilation the way
     * maven-compiler-plugin skips its own goal: they do nothing and say so, the index and the sealed inventory
     * of the previous build stay exactly as they were, and the rest of the build carries on — which is what
     * makes {@code -Dmaven.test.skip=true package} work. The index and the sealed inventory of each compilation are
     * checked after each of the two builds, the test ones included.
     */
    @Test
    void skippingACompilationSkipsItsStateGraphGoals() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        for (Map.Entry<String, String> source : testDomain().entrySet()) {
            fixture.writeTestSource(source.getKey(), source.getValue());
        }
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        List<String> testPublished = fixture.localExportsOf(TEST_EXPORTS);
        assertTrue(testPublished.contains("fxt.Tickets"), testPublished::toString);

        MavenResult main = fixture.run("-Dmaven.main.skip=true", "compile");

        assertEquals(0, main.exitCode(), main.output());
        assertTrue(main.output().contains("StateGraph skips fx.build:fixture:main: maven.main.skip=true switches"
                + " the default-compile compilation off"), main.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));
        assertTrue(fixture.inventoryIsSealed("test"));
        assertEquals(testPublished, fixture.localExportsOf(TEST_EXPORTS));

        MavenResult test = fixture.run("-Dmaven.test.skip=true", "package");

        assertEquals(0, test.exitCode(), test.output());
        assertTrue(test.output().contains("StateGraph skips fx.build:fixture:test: maven.test.skip=true switches"
                + " the default-testCompile compilation off"), test.output());
        assertTrue(fixture.inventoryIsSealed("test"), "the test inventory was retired:\n" + test.output());
        assertEquals(testPublished, fixture.localExportsOf(TEST_EXPORTS));
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));
        assertTrue(fixture.exists("target/fixture-1.0.0.jar"));
    }

    /**
     * A compilation that can end without the classes the completion publishes is refused by the generation,
     * before the preparation retires anything: {@code failOnError=false} carries the build on past a
     * compilation javac rejected, and {@code proc=only}, as a parameter or as a compiler argument, runs the
     * annotation processors and compiles nothing.
     */
    @Test
    void aCompilationThatCanEndWithoutClassesIsRefusedBeforeAnythingIsRetired() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        Map<String, String> broken = MavenFixture.domainSources();
        broken.put("fx.Broken", """
                package fx;

                /** A declaration javac refuses, so the compilation of this fixture fails. */
                public final class Broken {

                    private final int count = "not a number";
                }
                """);
        fixture.writeSources(broken);

        MavenResult failOnError = fixture.run("-Dmaven.compiler.failOnError=false", "package");

        assertNotEquals(0, failOnError.exitCode(), failOnError.output());
        assertTrue(failOnError.output().contains("maven.compiler.failOnError=false lets the build continue past a"
                + " compilation the default-compile execution of maven-compiler-plugin rejects"),
                failOnError.output());
        assertNothingWasRetired(fixture, published);

        MavenResult procOnly = fixture.run("-Dmaven.compiler.proc=only", "compile");

        assertNotEquals(0, procOnly.exitCode(), procOnly.output());
        assertTrue(procOnly.output().contains("maven.compiler.proc=only makes the default-compile execution of"
                + " maven-compiler-plugin run the annotation processors and compile nothing"), procOnly.output());
        assertNothingWasRetired(fixture, published);

        fixture.editPom("graphmaquina/main/annotation-manifest.json</arg>",
                "graphmaquina/main/annotation-manifest.json</arg>\n                <arg>-proc:only</arg>");

        MavenResult argument = fixture.run("compile");

        assertNotEquals(0, argument.exitCode(), argument.output());
        assertTrue(argument.output().contains("the compiler argument -proc:only makes the default-compile"
                + " execution of maven-compiler-plugin run the annotation processors"), argument.output());
        assertTrue(argument.output().contains("Remove that argument from the configuration of that execution."),
                argument.output());
        assertNothingWasRetired(fixture, published);
    }

    /**
     * The completion attributes each class file to the source javac compiled it from through its {@code SourceFile}
     * attribute, which javac leaves out when it is given a {@code -g:} option and none of its {@code -g:} options,
     * whatever their order, lists {@code source}. maven-compiler-plugin passes {@code <debuglevel>} as such an option
     * while {@code <debug>} is on, as it is by default. A debug level or a compiler argument that leaves the attribute
     * out is refused by the generation, before anything is retired; {@code <debug>false</debug>}, which passes no
     * {@code -g} option at all, and a {@code -g:source} before a {@code -g:none} each build and publish.
     */
    @Test
    void aDebugSettingThatLeavesOutTheSourceFileIsRefusedBeforeAnythingIsRetired() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        fixture.editPom("<release>21</release>",
                "<release>21</release>\n          <debuglevel>lines,vars</debuglevel>");

        MavenResult level = fixture.run("package");

        assertNotEquals(0, level.exitCode(), level.output());
        assertTrue(level.output().contains("<debuglevel>lines,vars</debuglevel> makes javac write the class files of"
                + " the default-compile execution of maven-compiler-plugin without the SourceFile attribute"),
                level.output());
        assertNothingWasRetired(fixture, published);

        fixture.editPom("<debuglevel>lines,vars</debuglevel>",
                "<debug>false</debug>\n          <debuglevel>lines,vars</debuglevel>");

        MavenResult off = fixture.run("package");

        assertEquals(0, off.exitCode(), off.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));

        fixture.editPom("\n          <debug>false</debug>\n          <debuglevel>lines,vars</debuglevel>", "");
        String manifest = "graphmaquina/main/annotation-manifest.json</arg>";
        fixture.editPom(manifest, manifest + "\n                <arg>-g:none</arg>");

        MavenResult none = fixture.run("package");

        assertNotEquals(0, none.exitCode(), none.output());
        assertTrue(none.output().contains("the compiler argument -g:none makes javac write the class files of the"
                + " default-compile execution of maven-compiler-plugin without the SourceFile attribute"),
                none.output());
        assertNothingWasRetired(fixture, published);

        fixture.editPom("<arg>-g:none</arg>", "<arg>-g:source</arg>\n                <arg>-g:none</arg>");

        MavenResult source = fixture.run("package");

        assertEquals(0, source.exitCode(), source.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));
    }

    /**
     * With {@code useIncrementalCompilation} false, maven-compiler-plugin compiles only the sources whose class file
     * is missing or older than the source, so a build over the outputs of a previous one would hand javac the
     * vocabulary the preparation retired without the hand-written declarations the annotation processor must see.
     * The setting is refused by the generation before the preparation retires anything, whether the configuration
     * of the execution sets it or the {@code maven.compiler.useIncrementalCompilation} property does, and the
     * default, {@code true}, builds.
     */
    @Test
    void aCompilationOfStaleSourcesOnlyIsRefusedBeforeAnythingIsRetired() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        fixture.editPom("<useIncrementalCompilation>true</useIncrementalCompilation>",
                "<useIncrementalCompilation>false</useIncrementalCompilation>");

        MavenResult configured = fixture.run("package");

        assertNotEquals(0, configured.exitCode(), configured.output());
        assertTrue(fixture.exists(MAIN_EXPORTS), "the index of the previous build was retired:\n"
                + configured.output());
        assertNothingWasRetired(fixture, published);
        assertTrue(configured.output().contains("<useIncrementalCompilation>false</useIncrementalCompilation> makes"
                + " the default-compile execution of maven-compiler-plugin compile only the sources"),
                configured.output());
        assertTrue(configured.output().contains("Leave useIncrementalCompilation at its default, true."),
                configured.output());

        fixture.editPom("<useIncrementalCompilation>false</useIncrementalCompilation>", "");

        MavenResult property = fixture.run("-Dmaven.compiler.useIncrementalCompilation=false", "package");

        assertNotEquals(0, property.exitCode(), property.output());
        assertTrue(property.output().contains("maven.compiler.useIncrementalCompilation=false makes the"
                + " default-compile execution of maven-compiler-plugin compile only the sources"), property.output());
        assertNothingWasRetired(fixture, published);

        MavenResult defaulted = fixture.run("package");

        assertEquals(0, defaulted.exitCode(), defaulted.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));
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
        Path other = fakeJdk("jdk-77", null);
        Path declared = toolchains("toolchains.xml", other);
        Path none = toolchains("no-toolchains.xml", null);
        MavenResult toolchain = fixture.run("-t", declared.toString(), "-gt", none.toString(), "generate-sources");
        assertNotEquals(0, toolchain.exitCode(), toolchain.output());
        assertTrue(toolchain.output().contains("the jdk toolchain {version=77}, which selects "),
                toolchain.output());
        assertTrue(toolchain.output().contains("jdk-77"), toolchain.output());
        assertFalse(fixture.exists("target/graphmaquina/main/annotation-manifest.json"));
    }

    /**
     * javac forked at the {@code javac} of the very JDK the generator runs on is on the same JDK, whatever path
     * names it, so the build compiles.
     */
    @Test
    void aCompilerForkedAtTheJdkOfTheGeneratorCompiles() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        // ${java.home} is the JVM running Maven, which is where the generator runs when nothing else is selected.
        fixture.editPom("<release>21</release>",
                "<release>21</release><fork>true</fork><executable>${java.home}/bin/javac</executable>");

        MavenResult result = fixture.run("compile");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertTrue(fixture.exists("target/classes/fx/OrdersGraph.class"));
    }

    /**
     * A javac that runs inside the JVM running Maven compiles on that JDK, so a generator configured to run on
     * another JDK is refused before anything is generated.
     */
    @Test
    void aGeneratorOnAnotherJdkThanJavacIsRefused() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        Path other = fakeJdk("other-jdk", null);

        MavenResult result = fixture.run("-Dgraphmaquina.javaExecutable=" + other.resolve("bin/java"),
                "generate-sources");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("the default-compile execution of maven-compiler-plugin compiles with"
                + " the JDK running Maven"), result.output());
        assertTrue(result.output().contains("while the StateGraph generator runs under " + other.resolve("bin/java")),
                result.output());
        assertFalse(fixture.exists("target/graphmaquina/main/annotation-manifest.json"));
    }

    /**
     * A {@code <jdkToolchain>} that matches no toolchain of the build is no disagreement: maven-compiler-plugin
     * then compiles with the toolchain selected for the build or, without one, on the JDK running Maven, which
     * is where the generator runs too.
     */
    @Test
    void aJdkToolchainThatMatchesNoToolchainFallsBackLikeTheCompiler() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<release>21</release>",
                "<release>21</release><jdkToolchain><version>21</version></jdkToolchain>");
        Path none = toolchains("no-toolchains.xml", null);

        MavenResult result = fixture.run("-t", none.toString(), "-gt", none.toString(), "compile");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.inventoryIsSealed("main"));
    }

    /**
     * An empty {@code <jdkToolchain/>} is a requirement every jdk toolchain meets, so maven-compiler-plugin forks the
     * javac of the first jdk toolchain of the build, as it would for any requirement that toolchain meets. The check
     * compares that javac with the JDK of the generator, and refuses it here, where it belongs to another JDK, before
     * anything is generated.
     */
    @Test
    void anEmptyJdkToolchainSelectsTheFirstJdkToolchainLikeTheCompiler() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<release>21</release>", "<release>21</release><jdkToolchain/>");
        Path other = fakeJdk("jdk-77", null);
        Path declared = toolchains("toolchains.xml", other);
        Path none = toolchains("no-toolchains.xml", null);

        MavenResult result = fixture.run("-t", declared.toString(), "-gt", none.toString(), "generate-sources");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("the default-compile execution of maven-compiler-plugin compiles with the"
                + " first jdk toolchain of this build, as its empty <jdkToolchain> selects, "
                + other.resolve("bin/javac")), result.output());
        assertFalse(fixture.exists("target/graphmaquina/main/annotation-manifest.json"));
    }

    /**
     * The generator parses sources with the jdk.compiler module and runs on Java 21 or later, so whatever
     * {@code java} it is given is checked before anything is generated: one with no javac beside it belongs to a
     * runtime, and one whose JDK declares an older Java in its release file is too old. Both are refused, although
     * either launcher would start the generator here, since both run the JDK of these tests.
     */
    @Test
    void theGeneratorIsStartedOnlyWithTheJavaOfAJdk21() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        Path runtime = fakeJavaHome("runtime", null, "java");

        MavenResult result = fixture.run("-Dgraphmaquina.javaExecutable=" + runtime.resolve("bin/java"),
                "generate-sources");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("the configured <javaExecutable>, " + runtime.resolve("bin/java")
                + ", has no javac beside it, so it belongs to a runtime, not a JDK"), result.output());

        Path older = fakeJdk("jdk-17", "17.0.2");

        MavenResult old = fixture.run("-Dgraphmaquina.javaExecutable=" + older.resolve("bin/java"),
                "generate-sources");

        assertNotEquals(0, old.exitCode(), old.output());
        assertTrue(old.output().contains("the StateGraph generator needs Java 21 or later, but the configured"
                + " <javaExecutable>, " + older.resolve("bin/java") + ", belongs to Java 17"), old.output());
        assertFalse(fixture.exists("target/graphmaquina/main/annotation-manifest.json"));
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

    /**
     * A compiler configured to read another annotation manifest than the one the generator writes is refused
     * before anything is generated, with the argument that execution must declare.
     */
    @Test
    void aCompilerThatReadsAnotherManifestFailsTheBuild() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("graphmaquina/main/annotation-manifest.json",
                "graphmaquina/elsewhere/annotation-manifest.json");

        MavenResult result = fixture.run("generate-sources");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("graphmaquina/elsewhere/annotation-manifest.json"), result.output());
        assertTrue(result.output().contains("Declare exactly this compiler argument for that execution:"
                + " -Agraphmaquina.annotationManifest="), result.output());
        assertFalse(fixture.exists("target/graphmaquina/main/annotation-manifest.json"));
    }

    /**
     * javac resolves a relative manifest path against the directory it runs in. A javac that compiles inside Maven
     * runs in the directory Maven was started in, which in a multi-module build is the directory of the parent, not of
     * the module, while a forked javac runs in the directory of the module. The check resolves the path the same
     * way: in the module of a build started from its parent, a relative manifest path is refused before anything is
     * retired, naming the file javac would read, and the same path builds with a forked javac.
     */
    @Test
    void aRelativeManifestPathIsResolvedWhereJavacRuns() throws IOException {
        MavenFixture parent = MavenFixture.basic(tempDirectory, fixtureRepository);
        MavenFixture module = parent.module();
        assertEquals(0, parent.run("package").exitCode());
        List<String> published = module.localExportsOf(MAIN_EXPORTS);
        parent.editPom("-Agraphmaquina.annotationManifest=${project.build.directory}/graphmaquina/main/",
                "-Agraphmaquina.annotationManifest=target/graphmaquina/main/");

        MavenResult inside = parent.run("package");

        assertNotEquals(0, inside.exitCode(), inside.output());
        assertTrue(inside.output().contains("reads the annotation manifest"
                + " target/graphmaquina/main/annotation-manifest.json, which javac, compiling inside the JVM running"
                + " Maven, resolves against the directory Maven was started in, to " + parent.projectDirectory()
                .toRealPath().resolve("target/graphmaquina/main/annotation-manifest.json")), inside.output());
        assertTrue(module.inventoryIsSealed("main"));
        assertEquals(published, module.localExportsOf(MAIN_EXPORTS));

        // ${java.home} is the JVM running Maven, which is where the generator runs when nothing else is selected.
        parent.editPom("<release>21</release>",
                "<release>21</release><fork>true</fork><executable>${java.home}/bin/javac</executable>");

        MavenResult forked = parent.run("package");

        assertEquals(0, forked.exitCode(), forked.output());
        assertTrue(module.inventoryIsSealed("main"));
        assertEquals(published, module.localExportsOf(MAIN_EXPORTS));
    }

    /**
     * The completion reads the class output of the project, where javac writes the classes of a compilation unless its
     * compiler execution moves them: through {@code <outputDirectory>}, through the
     * {@code maven.compiler.outputDirectory} property, which only the main compilation reads, or through
     * {@code <multiReleaseOutput>}, which moves the classes of the main compilation below {@code META-INF/versions}.
     * Each is refused by the generation of that compilation before anything is retired, while an
     * {@code <outputDirectory>} that names the class output of the project, here relative to the project directory,
     * builds.
     */
    @Test
    void aCompilerThatWritesItsClassesElsewhereIsRefusedBeforeAnythingIsRetired() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        for (Map.Entry<String, String> source : testDomain().entrySet()) {
            fixture.writeTestSource(source.getKey(), source.getValue());
        }
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        List<String> testPublished = fixture.localExportsOf(TEST_EXPORTS);
        String main = "<id>default-compile</id>\n            <configuration>";
        String elsewhere = "<outputDirectory>${project.build.directory}/elsewhere-classes</outputDirectory>";
        fixture.editPom(main, main + "\n              " + elsewhere);

        MavenResult moved = fixture.run("package");

        assertNotEquals(0, moved.exitCode(), moved.output());
        assertTrue(moved.output().contains("/elsewhere-classes</outputDirectory> makes the default-compile execution"
                + " of maven-compiler-plugin write its classes to "), moved.output());
        assertNothingWasRetired(fixture, published);

        fixture.editPom(elsewhere, "<multiReleaseOutput>true</multiReleaseOutput>");

        MavenResult versioned = fixture.run("package");

        assertNotEquals(0, versioned.exitCode(), versioned.output());
        assertTrue(versioned.output().contains("<multiReleaseOutput>true</multiReleaseOutput> makes the"
                + " default-compile execution of maven-compiler-plugin write its classes to "), versioned.output());
        assertNothingWasRetired(fixture, published);

        String relative = "<outputDirectory>target/classes</outputDirectory>";
        fixture.editPom("<multiReleaseOutput>true</multiReleaseOutput>", relative);

        MavenResult same = fixture.run("package");

        assertEquals(0, same.exitCode(), same.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));

        fixture.editPom("\n              " + relative, "");
        Path classes = fixture.projectDirectory().resolve("target/property-classes");

        MavenResult property = fixture.run("-Dmaven.compiler.outputDirectory=" + classes, "package");

        assertNotEquals(0, property.exitCode(), property.output());
        assertTrue(property.output().contains("maven.compiler.outputDirectory=" + classes + " makes the"
                + " default-compile execution of maven-compiler-plugin write its classes to "), property.output());
        assertNothingWasRetired(fixture, published);

        String test = "<id>default-testCompile</id>\n            <configuration>";
        fixture.editPom(test, test + "\n              "
                + "<outputDirectory>${project.build.directory}/elsewhere-test-classes</outputDirectory>");

        MavenResult tests = fixture.run("package");

        assertNotEquals(0, tests.exitCode(), tests.output());
        assertTrue(tests.output().contains("/elsewhere-test-classes</outputDirectory> makes the default-testCompile"
                + " execution of maven-compiler-plugin write its classes to "), tests.output());
        assertTrue(fixture.inventoryIsSealed("test"));
        assertEquals(testPublished, fixture.localExportsOf(TEST_EXPORTS));
    }

    /**
     * javac takes the last {@code -d} and the last {@code -s} it is given, and maven-compiler-plugin passes its own
     * ahead of the compiler arguments. A {@code -d} compiler argument therefore moves the classes, and the index the
     * annotation processor publishes beside them, away from the class output the completion reads, and a {@code -s}
     * one moves the sources that processor writes away from the directory the preparation retires them from. Either
     * is refused before anything is retired, while a {@code -d} and a {@code -s} that name the directories of the
     * project build, and so does a relative {@code -d} that names the class output through a symbolic link followed
     * by {@code ..}, which javac resolves as the file system does: the link first, then the {@code ..}. That part is
     * aborted where symbolic links cannot be created.
     */
    @Test
    void outputArgumentsThatMoveWhatJavacWritesAreRefusedBeforeAnythingIsRetired() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        String manifest = "graphmaquina/main/annotation-manifest.json</arg>";
        String classes = arguments(List.of("-d", "${project.build.directory}/elsewhere-classes"));
        fixture.editPom(manifest, manifest + classes);

        MavenResult moved = fixture.run("package");

        assertNotEquals(0, moved.exitCode(), moved.output());
        assertTrue(moved.output().contains("/elsewhere-classes of the default-compile execution of"
                + " maven-compiler-plugin makes javac write its classes to "), moved.output());
        assertNothingWasRetired(fixture, published);

        String sources = arguments(List.of("-s", "${project.build.directory}/elsewhere-sources"));
        fixture.editPom(manifest + classes, manifest + sources);

        MavenResult generated = fixture.run("package");

        assertNotEquals(0, generated.exitCode(), generated.output());
        assertTrue(generated.output().contains("/elsewhere-sources of the default-compile execution of"
                + " maven-compiler-plugin makes the annotation processor write its sources to "), generated.output());
        assertNothingWasRetired(fixture, published);

        String same = arguments(List.of("-d", "${project.build.outputDirectory}", "-s",
                "${project.build.directory}/generated-sources/annotations"));
        fixture.editPom(manifest + sources, manifest + same);

        MavenResult kept = fixture.run("package");

        assertEquals(0, kept.exitCode(), kept.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));

        Path inner = Files.createDirectories(fixture.projectDirectory().resolve("target/inner"));
        try {
            Files.createSymbolicLink(fixture.projectDirectory().resolve("inner"), inner);
        } catch (UnsupportedOperationException | FileSystemException unsupported) {
            Assumptions.abort("symbolic links cannot be created here: " + unsupported);
        }
        String throughLink = arguments(List.of("-d", "inner/../classes"));
        fixture.editPom(manifest + same, manifest + throughLink);

        MavenResult linked = fixture.run("package");

        assertEquals(0, linked.exitCode(), linked.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));
    }

    /**
     * {@code <compileSourceRoots>} of a compiler execution replace the source roots of the project for javac, the
     * directory the generator writes into included, so a list that leaves that directory out would have javac compile
     * the hand-written sources without the vocabulary they use. It is refused before anything is retired.
     */
    @Test
    void compilerSourceRootsWithoutTheGeneratedDirectoryAreRefusedBeforeAnythingIsRetired() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        String main = "<id>default-compile</id>\n            <configuration>";
        fixture.editPom(main, main + "\n              <compileSourceRoots>"
                + "<compileSourceRoot>${project.basedir}/src/main/java</compileSourceRoot></compileSourceRoots>");

        MavenResult result = fixture.run("compile");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("the <compileSourceRoots> of the default-compile execution of"
                + " maven-compiler-plugin replace the source roots of the project and leave out "
                + fixture.projectDirectory().toRealPath().resolve("target/generated-sources/graphmaquina")),
                result.output());
        assertNothingWasRetired(fixture, published);
    }

    /**
     * An empty {@code <compileSourceRoots/>} is no list of its own: Maven gives the compiler its default for it, the
     * source roots of the project, the directory the generator writes into included, so it builds and publishes.
     */
    @Test
    void anEmptyCompileSourceRootsElementCompilesTheSourceRootsOfTheProject() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        String main = "<id>default-compile</id>\n            <configuration>";
        fixture.editPom(main, main + "\n              <compileSourceRoots/>");

        MavenResult result = fixture.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(List.of("fx.Orders", "fx.ActionsRefs.Approve", "fx.OrderRefs.Id", "fx.OrderRefs.Status"),
                fixture.localExports());
    }

    /**
     * Maven started with {@code -f} through a symbolic link to the project directory names the project directory
     * through that link, while the JVM running it names its working directory with every link resolved. A relative
     * manifest path, and relative {@code <compileSourceRoots>}, which javac resolves against that working directory,
     * name the very files and directories the generator writes, so both build. So do absolute
     * {@code <compileSourceRoots>} spelled through the link while Maven names the project directory without it, and a
     * relative root that leads through a link inside the project and then up again, which the file system resolves
     * link first. Each build publishes, byte for byte, the index a plain build publishes: a source keeps its origin
     * relative to the project directory, however its root is spelled. The test is aborted where symbolic links cannot
     * be created.
     */
    @Test
    void namesThroughALinkToTheProjectDirectoryBuildTheSameIndex() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        Path link = tempDirectory.resolve("linked project");
        try {
            Files.createSymbolicLink(link, fixture.projectDirectory());
        } catch (UnsupportedOperationException | FileSystemException unsupported) {
            Assumptions.abort("symbolic links cannot be created here: " + unsupported);
        }
        assertEquals(0, fixture.run("package").exitCode());
        Path exports = fixture.projectDirectory().resolve(MAIN_EXPORTS);
        byte[] published = Files.readAllBytes(exports);
        String pom = link.resolve("pom.xml").toString();
        fixture.editPom("-Agraphmaquina.annotationManifest=${project.build.directory}/graphmaquina/main/",
                "-Agraphmaquina.annotationManifest=target/graphmaquina/main/");

        MavenResult manifest = fixture.run("-f", pom, "package");

        assertEquals(0, manifest.exitCode(), manifest.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertArrayEquals(published, Files.readAllBytes(exports), Files.readString(exports));

        String main = "<id>default-compile</id>\n            <configuration>";
        String relative = "<compileSourceRoots>"
                + "<compileSourceRoot>src/main/java</compileSourceRoot>"
                + "<compileSourceRoot>target/generated-sources/graphmaquina</compileSourceRoot>"
                + "</compileSourceRoots>";
        fixture.editPom(main, main + "\n              " + relative);

        MavenResult roots = fixture.run("-f", pom, "package");

        assertEquals(0, roots.exitCode(), roots.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertArrayEquals(published, Files.readAllBytes(exports), Files.readString(exports));

        String absoluteRoots = "<compileSourceRoots>"
                + "<compileSourceRoot>" + link.resolve("src/main/java") + "</compileSourceRoot>"
                + "<compileSourceRoot>" + link.resolve("target/generated-sources/graphmaquina")
                + "</compileSourceRoot></compileSourceRoots>";
        fixture.editPom(relative, absoluteRoots);

        MavenResult absolute = fixture.run("package");

        assertEquals(0, absolute.exitCode(), absolute.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertArrayEquals(published, Files.readAllBytes(exports), Files.readString(exports));

        // The file system resolves deep/../../src/main/java through the link first, to src/main/java, while a
        // normalized spelling of it would leave the project directory.
        Path deep = Files.createDirectories(fixture.projectDirectory().resolve("target/deep"));
        Files.createSymbolicLink(fixture.projectDirectory().resolve("deep"), deep);
        fixture.editPom(absoluteRoots, "<compileSourceRoots>"
                + "<compileSourceRoot>deep/../../src/main/java</compileSourceRoot>"
                + "<compileSourceRoot>target/generated-sources/graphmaquina</compileSourceRoot>"
                + "</compileSourceRoots>");

        MavenResult throughDeep = fixture.run("package");

        assertEquals(0, throughDeep.exitCode(), throughDeep.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertArrayEquals(published, Files.readAllBytes(exports), Files.readString(exports));
    }

    /**
     * The generator parses the sources of the roots javac compiles: the {@code <compileSourceRoots>} of the compiler
     * execution when it declares them. Here they name a root of their own, which holds the shared domain, and the
     * directory the generator writes into, while the source root of the project holds only a draft that does not
     * parse, which javac never sees and the generator does not read either. That root then builds the same index when
     * it is spelled through a symbolic link followed by {@code ..}, which the file system resolves link first, while
     * a normalized spelling of it would name a directory that does not exist. That part is aborted where symbolic
     * links cannot be created.
     */
    @Test
    void theGeneratorReadsTheSourceRootsOfTheCompiler() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        for (Map.Entry<String, String> source : MavenFixture.domainSources().entrySet()) {
            fixture.writeFile("src/other/java/" + source.getKey().replace('.', '/') + ".java",
                    source.getValue().getBytes(StandardCharsets.UTF_8));
        }
        fixture.writeSources(Map.of("misc.Draft", draft("misc")));
        String main = "<id>default-compile</id>\n            <configuration>";
        String other = "<compileSourceRoot>${project.basedir}/src/other/java</compileSourceRoot>";
        fixture.editPom(main, main + "\n              <compileSourceRoots>" + other
                + "<compileSourceRoot>${project.build.directory}/generated-sources/graphmaquina</compileSourceRoot>"
                + "</compileSourceRoots>");

        MavenResult result = fixture.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(List.of("fx.Orders", "fx.ActionsRefs.Approve", "fx.OrderRefs.Id", "fx.OrderRefs.Status"),
                fixture.localExports());
        assertFalse(fixture.jarContains("misc/Draft.class"));
        Path exports = fixture.projectDirectory().resolve(MAIN_EXPORTS);
        byte[] published = Files.readAllBytes(exports);

        Path deeper = Files.createDirectories(fixture.projectDirectory().resolve("src/other/deeper"));
        try {
            Files.createSymbolicLink(fixture.projectDirectory().resolve("inner"), deeper);
        } catch (UnsupportedOperationException | FileSystemException unsupported) {
            Assumptions.abort("symbolic links cannot be created here: " + unsupported);
        }
        fixture.editPom(other, "<compileSourceRoot>${project.basedir}/inner/../java</compileSourceRoot>");

        MavenResult throughLink = fixture.run("package");

        assertEquals(0, throughLink.exitCode(), throughLink.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertArrayEquals(published, Files.readAllBytes(exports), Files.readString(exports));
    }

    /**
     * The sources of a root outside the project directory keep the origin a plain build publishes for them, their path
     * from the project directory once symbolic links are resolved, when the build reaches that root with {@code -f}
     * through a link to the project directory that lies in another directory, whose path has at least as many names
     * as that path has {@code ..}, and through a link to the root itself. The index is the same, byte for byte, each
     * time. The test is aborted where symbolic links cannot be created.
     */
    @Test
    void aSourceRootOutsideTheProjectKeepsItsOriginThroughALink() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        Path shared = tempDirectory.resolve("shared/java");
        for (Map.Entry<String, String> source : MavenFixture.domainSources().entrySet()) {
            Path file = shared.resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
        }
        fixture.writeSources(Map.of("misc.Draft", draft("misc")));
        Path projectLink = Files.createDirectories(tempDirectory.resolve("elsewhere/deeper")).resolve("linked project");
        Path sharedLink = tempDirectory.resolve("shared link");
        try {
            Files.createSymbolicLink(projectLink, fixture.projectDirectory());
            Files.createSymbolicLink(sharedLink, shared);
        } catch (UnsupportedOperationException | FileSystemException unsupported) {
            Assumptions.abort("symbolic links cannot be created here: " + unsupported);
        }
        String main = "<id>default-compile</id>\n            <configuration>";
        String outside = "<compileSourceRoot>${project.basedir}/../shared/java</compileSourceRoot>";
        fixture.editPom(main, main + "\n              <compileSourceRoots>" + outside
                + "<compileSourceRoot>${project.build.directory}/generated-sources/graphmaquina</compileSourceRoot>"
                + "</compileSourceRoots>");

        MavenResult plain = fixture.run("package");

        assertEquals(0, plain.exitCode(), plain.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        Path exports = fixture.projectDirectory().resolve(MAIN_EXPORTS);
        byte[] published = Files.readAllBytes(exports);
        assertTrue(Files.readString(exports).contains("\"../shared/java/fx/Orders.java\""),
                Files.readString(exports));

        MavenResult throughProject = fixture.run("-f", projectLink.resolve("pom.xml").toString(), "package");

        assertEquals(0, throughProject.exitCode(), throughProject.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertArrayEquals(published, Files.readAllBytes(exports), Files.readString(exports));

        fixture.editPom(outside, "<compileSourceRoot>" + sharedLink + "</compileSourceRoot>");

        MavenResult throughRoot = fixture.run("package");

        assertEquals(0, throughRoot.exitCode(), throughRoot.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertArrayEquals(published, Files.readAllBytes(exports), Files.readString(exports));
    }

    /**
     * An origin is the path of its source from the project directory the build is given, read lexically, as the
     * generator relativizes it. When {@code -f} names a deep project directory through a link whose path has fewer
     * names than the {@code ..} that lead from the project directory up to a source root outside it, a name that
     * spelled those {@code ..} after the link would lose some of them in the generator. The root then keeps its own
     * name, so every origin still leads from the link to its source. The test is aborted where symbolic links cannot
     * be created.
     */
    @Test
    void aShallowLinkToADeepProjectLeavesEveryOriginLeadingToItsSource() throws IOException {
        Path deep = tempDirectory;
        for (int level = 0; level <= tempDirectory.getNameCount(); level++) {
            deep = deep.resolve("d" + level);
        }
        MavenFixture fixture = MavenFixture.basic(deep, fixtureRepository);
        Path shared = tempDirectory.resolve("shared/java");
        for (Map.Entry<String, String> source : MavenFixture.domainSources().entrySet()) {
            Path file = shared.resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
        }
        fixture.writeSources(Map.of("misc.Draft", draft("misc")));
        Path projectLink = tempDirectory.resolve("shallow");
        try {
            Files.createSymbolicLink(projectLink, fixture.projectDirectory());
        } catch (UnsupportedOperationException | FileSystemException unsupported) {
            Assumptions.abort("symbolic links cannot be created here: " + unsupported);
        }
        String up = "../".repeat(fixture.projectDirectory().getNameCount() - tempDirectory.getNameCount());
        String main = "<id>default-compile</id>\n            <configuration>";
        fixture.editPom(main, main + "\n              <compileSourceRoots>"
                + "<compileSourceRoot>${project.basedir}/" + up + "shared/java</compileSourceRoot>"
                + "<compileSourceRoot>${project.build.directory}/generated-sources/graphmaquina</compileSourceRoot>"
                + "</compileSourceRoots>");
        Path exports = fixture.projectDirectory().resolve(MAIN_EXPORTS);

        MavenResult plain = fixture.run("package");

        assertEquals(0, plain.exitCode(), plain.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertOriginsLeadToSources(fixture.projectDirectory(), exports);

        MavenResult throughLink = fixture.run("-f", projectLink.resolve("pom.xml").toString(), "package");

        assertEquals(0, throughLink.exitCode(), throughLink.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertOriginsLeadToSources(projectLink, exports);
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
        assertEquals(List.of("io.github.graphmaquina:graphmaquina-codegen:" + VERSION), dependencies(descriptor));

        // The goals resolve toolchains through the public component alone; the private one is Maven internals
        // this plugin has no reason to require.
        assertEquals(List.of("org.apache.maven.toolchain.ToolchainManager"), requirements(descriptor));
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
     * javac reads the sources of a project in the encoding the compiler is given, which defaults to
     * {@code project.build.sourceEncoding}, and at the release it compiles for, so the generator is told both: an
     * accented identifier of a project kept in ISO-8859-1 reaches the generated vocabulary intact.
     */
    @Test
    void passesTheSourceEncodingAndTheReleaseOfTheProjectToTheGenerator() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>",
                "<project.build.sourceEncoding>ISO-8859-1</project.build.sourceEncoding>");
        fixture.writeSources(accentedDomain(), StandardCharsets.ISO_8859_1);

        MavenResult result = fixture.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertEquals("21", fixture.requestValue("main", "release"));
        assertEquals("ISO-8859-1", fixture.requestValue("main", "encoding"));
        assertTrue(fixture.localExports().contains("fx.ActionsRefs.ApproveRésumé"), result.output());
    }

    /**
     * The encoding the compiler is configured with wins over the one of the project, and a compiler that names no
     * release compiles for its target, so the generator is told those.
     *
     * <p>The javac of JDK 25 warns at {@code -source 21} without {@code --release} that the location of system
     * modules is not set, since 21 is below its own release, and {@code -Werror} would turn that into a failure.
     * Both compilations of this fixture therefore turn off javac's {@code options} lint, which that warning belongs
     * to, so the test passes on JDK 25 as well.</p>
     */
    @Test
    void passesTheEncodingAndTheTargetOfTheCompilerToTheGenerator() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<release>21</release>", "<source>21</source>\n"
                + "          <target>21</target>\n"
                + "          <encoding>ISO-8859-1</encoding>");
        for (String sourceSet : List.of("main", "test")) {
            String manifest = "graphmaquina/" + sourceSet + "/annotation-manifest.json</arg>\n";
            fixture.editPom(manifest + "                <arg>-Xlint:all,-serial</arg>",
                    manifest + "                <arg>-Xlint:all,-serial,-options</arg>");
        }
        fixture.writeSources(accentedDomain(), StandardCharsets.ISO_8859_1);

        MavenResult result = fixture.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertEquals("21", fixture.requestValue("main", "release"));
        assertEquals("ISO-8859-1", fixture.requestValue("main", "encoding"));
        assertTrue(fixture.localExports().contains("fx.ActionsRefs.ApproveRésumé"), result.output());
    }

    /**
     * The test compilation of maven-compiler-plugin compiles at {@code <testRelease>} when one is set, else at
     * {@code <release>}, and takes {@code <testSource>} and {@code <testTarget>} over {@code <source>} and
     * {@code <target>} the same way. A test release or test source below Java 21 is therefore refused by the
     * {@code generate-test} goal, the one goal of the test compilation that checks the compiler, before the test
     * request is written, even when the main compilation is at Java 21, and the refusal names the setting that wins
     * and one that fixes it. The two generation goals run on their own, so no javac runs, whatever JDK runs these
     * tests.
     */
    @Test
    void theGenerateTestGoalRefusesATestReleaseOrATestSourceBelowJava21() throws IOException {
        String goal = "io.github.graphmaquina:graphmaquina-maven-plugin:" + VERSION + ':';
        MavenFixture released = MavenFixture.basic(Files.createDirectories(tempDirectory.resolve("released")),
                fixtureRepository);
        released.editPom("<release>21</release>", "<release>21</release>\n          <testRelease>17</testRelease>");
        MavenFixture targeted = MavenFixture.basic(Files.createDirectories(tempDirectory.resolve("targeted")),
                fixtureRepository);
        targeted.editPom("<release>21</release>", "<source>21</source>\n"
                + "          <target>21</target>\n"
                + "          <testSource>17</testSource>\n"
                + "          <testTarget>17</testTarget>");

        MavenResult release = released.run(goal + "generate", goal + "generate-test");
        MavenResult source = targeted.run(goal + "generate", goal + "generate-test");

        assertNotEquals(0, release.exitCode(), release.output());
        assertTrue(release.output().contains("the default-testCompile execution of maven-compiler-plugin compiles"
                + " with <testRelease>17</testRelease>"), release.output());
        assertTrue(release.output().contains("Configure <testRelease>21</testRelease> for that execution."),
                release.output());
        assertNotEquals(0, source.exitCode(), source.output());
        assertTrue(source.output().contains("the default-testCompile execution of maven-compiler-plugin compiles"
                + " with <testSource>17</testSource>"), source.output());
        assertTrue(source.output().contains("Configure <release>21</release> for that execution."),
                source.output());
        for (MavenFixture fixture : List.of(released, targeted)) {
            assertEquals("21", fixture.requestValue("main", "release"));
            assertFalse(fixture.exists("target/graphmaquina/test/request.json"));
        }
    }

    /**
     * The {@code generate-test} goal checks the release of the test compilation alone: a {@code <testRelease>} of
     * Java 21 is what the test compilation compiles at, whatever older release the main compilation is configured
     * with, and so is a {@code <testTarget>} of Java 21 over an older {@code <target>}. The {@code generate-test}
     * goal accepts both, and the test request carries 21 rather than the older release or target.
     */
    @Test
    void theGenerateTestGoalAcceptsATestReleaseOrTestTargetOfJava21OverAnOlderOne() throws IOException {
        MavenFixture released = MavenFixture.basic(Files.createDirectories(tempDirectory.resolve("released")),
                fixtureRepository);
        released.editPom("<release>21</release>", "<release>17</release>\n          <testRelease>21</testRelease>");
        MavenFixture targeted = MavenFixture.basic(Files.createDirectories(tempDirectory.resolve("targeted")),
                fixtureRepository);
        targeted.editPom("<release>21</release>", "<source>17</source>\n"
                + "          <target>17</target>\n"
                + "          <testSource>21</testSource>\n"
                + "          <testTarget>21</testTarget>");

        for (MavenFixture fixture : List.of(released, targeted)) {
            for (Map.Entry<String, String> source : testDomain().entrySet()) {
                fixture.writeTestSource(source.getKey(), source.getValue());
            }

            MavenResult result = fixture.run("io.github.graphmaquina:graphmaquina-maven-plugin:" + VERSION
                    + ":generate-test");

            assertEquals(0, result.exitCode(), result.output());
            assertEquals("21", fixture.requestValue("test", "release"));
            assertTrue(fixture.exists("target/generated-test-sources/graphmaquina/fxt/TicketsGraph.java"));
        }
    }

    /**
     * The request of the test compilation carries the release that compilation uses, {@code <testRelease>} over
     * {@code <release>} and {@code <testTarget>} over {@code <target>}, while the main request keeps the main
     * release. Both have to be Java 21 or later, so telling them apart needs a later release, and a JDK that
     * compiles at it. The two generation goals run on their own, so no javac runs: on a later JDK, javac compiling
     * the main sources of the second fixture at {@code -source 21} warns that the location of system modules is not
     * set, and {@code -Werror} turns that into a failure. The test is skipped on a JDK below 22, since a javac of
     * JDK 21 compiles at no release above 21.
     */
    @Test
    void theTestRequestCarriesTheReleaseOfTheTestCompilation() throws IOException {
        assumeTrue(Runtime.version().feature() >= 22, "a JDK 21 javac compiles at no release above 21");
        String goal = "io.github.graphmaquina:graphmaquina-maven-plugin:" + VERSION + ':';
        MavenFixture released = MavenFixture.basic(Files.createDirectories(tempDirectory.resolve("released")),
                fixtureRepository);
        released.editPom("<release>21</release>", "<release>21</release>\n          <testRelease>22</testRelease>");
        MavenFixture targeted = MavenFixture.basic(Files.createDirectories(tempDirectory.resolve("targeted")),
                fixtureRepository);
        targeted.editPom("<release>21</release>", "<source>21</source>\n"
                + "          <target>21</target>\n"
                + "          <testSource>22</testSource>\n"
                + "          <testTarget>22</testTarget>");

        for (MavenFixture fixture : List.of(released, targeted)) {
            MavenResult result = fixture.run(goal + "generate", goal + "generate-test");

            assertEquals(0, result.exitCode(), result.output());
            assertEquals("21", fixture.requestValue("main", "release"));
            assertEquals("22", fixture.requestValue("test", "release"), result.output());
        }
    }

    /**
     * maven-compiler-plugin leaves out of every source root the files its scanner's default excludes name, such
     * as the AppleDouble companion {@code ._Orders.java} a copy from macOS leaves beside a source, or anything
     * inside the directory of a version control system, such as {@code CVS}. The generator leaves out these two,
     * which Ant's default excludes name as well, whether this plugin hands it the files that includes and excludes
     * select or a whole root to walk. Not every file the compiler leaves out is left out of a whole root: a root the
     * execution does not narrow is handed to the generator whole, and the generator walks it with Ant's default
     * excludes, not with plexus-utils' longer list.
     */
    @Test
    void theGeneratorSkipsTheFilesTheCompilerExcludesByDefault() throws IOException {
        byte[] appleDouble = {0x00, 0x05, 0x16, 0x07, 0x00, 0x02, 0x00, 0x00, 'M', 'a', 'c', ' ', 'O', 'S', ' ', 'X',
            (byte) 0xff, (byte) 0xfe, 0x00, 0x01, '{', '}', (byte) 0x80};
        for (String excludes : List.of("<excludes><exclude>fx/drafts/**</exclude></excludes>", "")) {
            MavenFixture fixture = MavenFixture.basic(Files.createDirectories(
                    tempDirectory.resolve(excludes.isEmpty() ? "whole" : "filtered")), fixtureRepository);
            if (!excludes.isEmpty()) {
                fixture.editPom("<release>21</release>", "<release>21</release>\n          " + excludes);
            }
            fixture.writeFile("src/main/java/fx/._Orders.java", appleDouble);
            fixture.writeFile("src/main/java/fx/CVS/Junk.java", appleDouble);

            MavenResult result = fixture.run("package");

            assertEquals(0, result.exitCode(), result.output());
            assertTrue(fixture.jarContains("fx/OrdersGraph.class"), excludes);
        }
    }

    /**
     * Includes or excludes make this plugin hand the generator the files they select, one by one. A hand-written
     * type that only shares the file name of a generated source, in a package that merely ends like the
     * generated one's, is no collision there either: a listed file stands where its package declaration puts it.
     */
    @Test
    void aHandWrittenTypeNamedLikeAGeneratedOneIsNoCollisionWhenTheCompilerFiltersItsSources()
            throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<release>21</release>", "<release>21</release>\n"
                + "          <excludes><exclude>fx/drafts/**</exclude></excludes>");
        Map<String, String> sources = MavenFixture.domainSources();
        sources.put("legacy.fx.OrderRefs", """
                package legacy.fx;

                /** A hand-written type named like the generated fx.OrderRefs, in another package. */
                public final class OrderRefs {

                    private OrderRefs() {
                    }
                }
                """);
        fixture.writeSources(sources);

        MavenResult result = fixture.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.jarContains("fx/OrderRefs$Status.class"));
        assertTrue(fixture.jarContains("legacy/fx/OrderRefs.class"));
    }

    /**
     * {@code mvn} reads a {@code .mavenrc} from the home directory, which may export another {@code JAVA_HOME};
     * every fixture build runs on the JDK of these tests whatever such a file says, since the JDK they reason
     * about is that one.
     */
    @Test
    void aMavenrcCannotReplaceTheJdkOfAFixtureBuild() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        Path home = Files.createDirectories(tempDirectory.resolve("home"));
        Files.writeString(home.resolve(".mavenrc"), "echo MAVENRC-WAS-READ\nJAVA_HOME='"
                + tempDirectory.resolve("no-such-jdk") + "'\n", StandardCharsets.UTF_8);

        MavenResult result = fixture.run(Map.of("HOME", home.toString()), "compile");

        assertEquals(0, result.exitCode(), result.output());
        assertFalse(result.output().contains("MAVENRC-WAS-READ"), result.output());
    }

    /** The domain with its action spelled with an accent, as a project kept in ISO-8859-1 may spell it. */
    private static Map<String, String> accentedDomain() {
        Map<String, String> sources = MavenFixture.domainSources();
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
        sources.put("fx.Orders", MavenFixture.orders("Orders", "ApproveRésumé", "approveRésumé"));
        return sources;
    }

    /**
     * Asserts that a build refused before the preparation, in a project that had not been built before, left no
     * pending inventory, no published index and no jar.
     */
    private static void assertNothingWasRetiredOrPublished(MavenFixture fixture) {
        assertFalse(fixture.exists("target/graphmaquina/main/owned-outputs.pending.json"));
        assertFalse(fixture.exists(MAIN_EXPORTS));
        assertFalse(fixture.exists("target/fixture-1.0.0.jar"));
    }

    /**
     * Asserts that the main compilation of a previous build is exactly as that build left it: its inventory is
     * sealed, no preparation started another cycle, and its index still publishes {@code published}.
     */
    private static void assertNothingWasRetired(MavenFixture fixture, List<String> published) {
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));
    }

    /** One {@code <annotationProcessor>} element per name, as {@code <annotationProcessors>} lists them. */
    private static String annotationProcessors(List<String> names) {
        StringBuilder elements = new StringBuilder();
        for (String name : names) {
            elements.append("<annotationProcessor>").append(name).append("</annotationProcessor>");
        }
        return elements.toString();
    }

    /**
     * Writes {@code replacement} where the fixture POM declares its processor path, and makes the StateGraph annotation
     * processor a {@code provided} dependency of the project instead, declared after Lombok, so that javac finds both
     * processors on the class path, Lombok first, when no processor path is left.
     */
    private static void processorAsDependency(MavenFixture fixture, String replacement) throws IOException {
        fixture.editPom(PROCESSOR_PATHS, replacement);
        fixture.editPom("  </dependencies>", """
                    <dependency>
                      <groupId>io.github.graphmaquina</groupId>
                      <artifactId>graphmaquina-processor</artifactId>
                      <version>${graphmaquina.version}</version>
                      <scope>provided</scope>
                    </dependency>
                  </dependencies>
                """.stripTrailing());
    }

    /**
     * A processor path of jars of the local repository the fixture builds share, with absolute paths: Lombok, and with
     * {@code stateGraph} the StateGraph annotation processor and the jars it runs on after it. A build that declared
     * the processor before has resolved them all there.
     */
    private static String processorPath(boolean stateGraph) {
        Path repository = Path.of(requireProperty("graphmaquina.mavenLocalRepository"));
        List<String> entries = new ArrayList<>();
        entries.add(repository.resolve("org/projectlombok/lombok/1.18.48/lombok-1.18.48.jar").toString());
        if (stateGraph) {
            for (String artifact : List.of("graphmaquina-processor", "graphmaquina-codegen", "graphmaquina-core")) {
                entries.add(repository.resolve("io/github/graphmaquina/" + artifact + '/' + VERSION + '/' + artifact
                        + '-' + VERSION + ".jar").toString());
            }
            entries.add(jarOf(Gson.class).toString());
        }
        return String.join(File.pathSeparator, entries);
    }

    /**
     * Writes a jar at {@code jar} that holds nothing but a manifest whose {@code Class-Path} names every entry of
     * {@code path} as a file URL, so that a processor path of that jar alone reaches them all.
     */
    private static Path pathingJar(Path jar, String path) throws IOException {
        List<String> urls = new ArrayList<>();
        for (String entry : path.split(File.pathSeparator)) {
            urls.add(Path.of(entry).toUri().toString());
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, String.join(" ", urls));
        new JarOutputStream(Files.newOutputStream(jar), manifest).close();
        return jar;
    }

    /**
     * Copies {@code jar} to {@code target} as a multi-release jar that holds everything outside {@code META-INF} only
     * under {@code META-INF/versions/<release>}, so that a JVM of that release or later reads its classes there and
     * nothing reads them at the top level.
     */
    private static Path multiReleaseJar(Path jar, Path target, int release) throws IOException {
        try (JarFile source = new JarFile(jar.toFile())) {
            Manifest manifest = source.getManifest() == null ? new Manifest() : new Manifest(source.getManifest());
            manifest.getMainAttributes().putIfAbsent(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.MULTI_RELEASE, "true");
            try (JarOutputStream copy = new JarOutputStream(Files.newOutputStream(target), manifest)) {
                for (JarEntry entry : Collections.list(source.entries())) {
                    String name = entry.getName();
                    if (entry.isDirectory() || name.equals(JarFile.MANIFEST_NAME)) {
                        continue;
                    }
                    copy.putNextEntry(new JarEntry(name.startsWith("META-INF/") ? name
                            : "META-INF/versions/" + release + '/' + name));
                    try (InputStream content = source.getInputStream(entry)) {
                        content.transferTo(copy);
                    }
                    copy.closeEntry();
                }
            }
        }
        return target;
    }

    /**
     * Asserts that the index {@code exports} publishes origins, and that each of them, resolved lexically against
     * {@code projectDirectory}, names a regular file.
     */
    private static void assertOriginsLeadToSources(Path projectDirectory, Path exports) throws IOException {
        String index = Files.readString(exports);
        Matcher origins = Pattern.compile("\"path\": \"([^\"]*)\"").matcher(index);
        int found = 0;
        while (origins.find()) {
            found++;
            Path named = projectDirectory.resolve(origins.group(1)).normalize();
            assertTrue(Files.isRegularFile(named), origins.group(1) + " leads from " + projectDirectory + " to "
                    + named + ", which is no source");
        }
        assertTrue(found > 0, index);
    }

    /**
     * Replaces, in the compiler arguments of both compiler executions, the arguments {@code before} that follow the
     * manifest argument with {@code after}.
     */
    private static void extraArguments(MavenFixture fixture, List<String> before, List<String> after)
            throws IOException {
        for (String sourceSet : List.of("main", "test")) {
            String manifest = "graphmaquina/" + sourceSet + "/annotation-manifest.json</arg>";
            fixture.editPom(manifest + arguments(before), manifest + arguments(after));
        }
    }

    /** One {@code <arg>} element per value, each on a line of its own, as the fixture POM writes them. */
    private static String arguments(List<String> values) {
        StringBuilder elements = new StringBuilder();
        for (String value : values) {
            elements.append("\n                <arg>").append(value).append("</arg>");
        }
        return elements.toString();
    }

    /** The jar {@code type} was loaded from, on the classpath of these tests. */
    private static Path jarOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException unusable) {
            throw new IllegalStateException("cannot locate the jar of " + type.getName(), unusable);
        }
    }

    /** A source javac refuses to parse, which only a build that excludes it can carry. */
    private static String draft(String packageName) {
        return """
                package %s;

                /** Work in progress, excluded from the build until it compiles. */
                public final class Draft {

                    void unfinished( {
                }
                """.formatted(packageName);
    }

    /** A directory laid out like the home of another JDK; see {@link #fakeJavaHome}. */
    private Path fakeJdk(String name, String version) throws IOException {
        return fakeJavaHome(name, version, "java", "javac");
    }

    /**
     * A directory laid out like the home of another Java installation: each of {@code tools} in its {@code bin}
     * runs that tool of the JDK running these tests, so only its path tells it apart, and its {@code release}
     * file declares {@code version} when one is given. Without {@code javac} among the tools it is a runtime.
     */
    private Path fakeJavaHome(String name, String version, String... tools) throws IOException {
        Path home = tempDirectory.resolve(name);
        Path bin = Files.createDirectories(home.resolve("bin"));
        Path real = Path.of(System.getProperty("java.home"), "bin");
        for (String tool : tools) {
            Path launcher = bin.resolve(tool);
            Files.writeString(launcher, "#!/bin/sh\nexec \"" + real.resolve(tool) + "\" \"$@\"\n",
                    StandardCharsets.UTF_8);
            if (!launcher.toFile().setExecutable(true)) {
                throw new IllegalStateException("cannot make " + launcher + " executable");
            }
        }
        if (version != null) {
            Files.writeString(home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\n", StandardCharsets.UTF_8);
        }
        return home;
    }

    /**
     * A toolchains file that declares one jdk toolchain of version {@code 77} at {@code jdk}, or none at all when
     * {@code jdk} is {@code null}. A build given it for both the user and the installation level sees exactly
     * these toolchains, whatever the machine running the tests declares.
     */
    private Path toolchains(String name, Path jdk) throws IOException {
        String declared = jdk == null ? "" : """
                  <toolchain>
                    <type>jdk</type>
                    <provides><version>77</version></provides>
                    <configuration><jdkHome>%s</jdkHome></configuration>
                  </toolchain>
                """.formatted(jdk);
        Path file = tempDirectory.resolve(name);
        Files.writeString(file, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<toolchains>\n" + declared
                + "</toolchains>\n", StandardCharsets.UTF_8);
        return file;
    }

    /** Every hand-written source of {@code fixture}, keyed by its path relative to the source root. */
    private static Map<String, String> handWrittenSources(MavenFixture fixture) throws IOException {
        Path root = fixture.projectDirectory().resolve("src/main/java");
        Map<String, String> sources = new LinkedHashMap<>();
        for (Path file : filesUnder(root)) {
            sources.put(root.relativize(file).toString(), Files.readString(file, StandardCharsets.UTF_8));
        }
        return sources;
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

    /** The position of the first execution whose name ends with {@code suffix}, or {@code -1}. */
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

    /** The value of {@code name}, which the Gradle test task sets and these tests cannot run without. */
    private static String requireProperty(String name) {
        String value = System.getProperty(name);
        if (value == null) {
            throw new IllegalStateException(name + " is not set; run these tests through"
                    + " ./gradlew :graphmaquina-maven-plugin:test");
        }
        return value;
    }

    /** One document to parse, so the two sources of XML share the builder and its failure handling. */
    @FunctionalInterface
    private interface Parser {

        Document parse(DocumentBuilder builder) throws IOException, SAXException;
    }
}
