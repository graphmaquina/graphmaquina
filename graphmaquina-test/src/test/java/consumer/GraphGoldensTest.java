package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.GraphGoldens;
import io.github.graphmaquina.test.fixtures.SnapshotGraphs;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.Id;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import io.github.graphmaquina.test.snapshot.GoldenOptions;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Contract of the Graphviz DOT golden. Update writes the source file, read mode resolves the very same resource
 * through the class loader, and a second update over matching content writes nothing. The checked-in
 * {@code golden/simple.dot} pins the rendering of the fixture — every declared edge and nothing else — and a
 * mismatch reports the lines that moved.
 */
class GraphGoldensTest {

    private static final String RESOURCE = "golden/simple.dot";

    /**
     * The negative cases name the checked-in fixtures with content that deliberately does not match, so they
     * must never follow {@code graphmaquina.goldens.update}: a regeneration run would rewrite the fixtures
     * with that wrong content instead of asserting. Explicit options read no property.
     */
    private static final GoldenOptions READ_ONLY =
            GoldenOptions.readOnly(GraphGoldensTest.class.getClassLoader());

    /** Every mismatch names how to regenerate the golden, after the difference it reports. */
    private static final String REGENERATE = "\nrun with -D" + GoldenOptions.UPDATE_PROPERTY + "=true -D"
            + GoldenOptions.ROOT_PROPERTY + "=<source root> to regenerate it";

    // --- the premise of this task: write once, then read what was written ----------------------------

    @Test
    void updateCreatesSourceThenReadOnlyMatchesIt(@TempDir Path root) throws Exception {
        GraphDefinition<Entity, State, Id> graph = TestDomain.graph(Duration.ofSeconds(1));
        List<Path> written = new ArrayList<>();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            GoldenOptions update = GoldenOptions.updating(loader, root, written::add);
            GraphGoldens.assertMatches(graph, RESOURCE, update);
            assertEquals(List.of(root.resolve(RESOURCE)), written);
            GraphGoldens.assertMatches(graph, RESOURCE, GoldenOptions.readOnly(loader));
            GraphGoldens.assertMatches(graph, RESOURCE, update);
            assertEquals(1, written.size());
        }
    }

    @Test
    void updateRejectsAnEscapingParentEvenWhenTheFinalSymlinkReturnsInside(@TempDir Path temp)
            throws Exception {
        Path root = Files.createDirectory(temp.resolve("root"));
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Path inside = Files.writeString(root.resolve("inside.dot"), "old\n");
        Files.createSymbolicLink(root.resolve("away"), outside);
        Path external = Files.createSymbolicLink(outside.resolve("x.dot"), inside);
        List<Path> written = new ArrayList<>();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            GoldenOptions update = GoldenOptions.updating(loader, root, written::add);
            assertThrows(IllegalArgumentException.class,
                    () -> GraphGoldens.assertMatches(SnapshotGraphs.minimalGraph(), "away/x.dot", update));
        }
        assertTrue(Files.isSymbolicLink(external));
        assertEquals(inside, Files.readSymbolicLink(external));
        assertEquals("old\n", Files.readString(inside));
        assertEquals(List.of(), written);
        try (Stream<Path> entries = Files.list(outside)) {
            assertEquals(List.of(external), entries.toList());
        }
    }

    @Test
    void aDiagramSavedWithAByteOrderMarkIsReadLikeAnyOther(@TempDir Path root) throws Exception {
        String checkedIn = Files.readString(
                Path.of(GraphGoldensTest.class.getClassLoader().getResource(RESOURCE).toURI()));
        Files.createDirectories(root.resolve("golden"));
        Files.writeString(root.resolve(RESOURCE), "\uFEFF" + checkedIn);
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            GraphGoldens.assertMatches(TestDomain.graph(Duration.ofSeconds(1)), RESOURCE,
                    GoldenOptions.readOnly(loader));
        }
    }

    // --- the checked-in fixture ----------------------------------------------------------------------

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void theCheckedInDiagramIsTheRenderingOfTheFixture() {
        GraphGoldens.assertMatches(TestDomain.graph(Duration.ofSeconds(1)), RESOURCE);
    }

    /**
     * The rendering draws declared edges only, so the identifier of every arrow is in its label and an
     * identifier without an edge appears nowhere: there is no exhaustive mode that could draw it.
     */
    @Test
    void theDiagramNamesEveryIdentifierItDrawsAndNoIdentifierItDoesNot() throws Exception {
        String rendered = Files.readString(Path.of(
                GraphGoldensTest.class.getClassLoader().getResource(RESOURCE).toURI()));
        assertTrue(rendered.contains("label=\"PREPARE prepare [async] [if first, second] [onFailure RECOVER]\""),
                rendered);
        assertTrue(rendered.contains("label=\"RECOVER\""), rendered);
        assertTrue(rendered.contains("label=\"FINISH\""), rendered);
        assertFalse(rendered.contains("ABANDON"), rendered);
    }

    @Test
    void anotherGraphFailsWithTheLinesThatMoved() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphGoldens.assertMatches(SnapshotGraphs.minimalGraph(), RESOURCE, READ_ONLY));
        String difference = """
                @@ -1,12 +1,8 @@
                -digraph "Entity/State" {
                +digraph "String/Flow" {
                     rankdir=LR;
                     initial [shape=point, label=""];
                -    n0 [label="NEW", shape=ellipse, tooltip="otherwise: prepare first"];
                -    n1 [label="READY", shape=ellipse, tooltip="otherwise: finish only"];
                -    n2 [label="DONE", shape=doublecircle, tooltip="otherwise: terminal state DONE accepts no transitions"];
                -    n3 [label="FAILED", shape=doublecircle, tooltip="otherwise: terminal state FAILED accepts no transitions"];
                +    n0 [label="OPEN", shape=ellipse, tooltip="otherwise: stay put"];
                +    n1 [label="CLOSED", shape=doublecircle, tooltip="otherwise: terminal state CLOSED accepts no transitions"];
                     initial -> n0;
                -    n0 -> n1 [label="PREPARE prepare [async] [if first, second] [onFailure RECOVER]"];
                -    n0 -> n3 [label="RECOVER"];
                -    n1 -> n2 [label="FINISH"];
                +    n0 -> n1 [label="CLOSE"];
                 }
                """;
        assertEquals(difference, error.actual());
        assertEquals(RESOURCE + ": content expected <matching golden> but was <" + difference + ">"
                + REGENERATE, error.getMessage());
    }

    @Test
    void aResourceThatIsNotOnTheClassPathExplainsUpdate() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphGoldens.assertMatches(TestDomain.graph(Duration.ofSeconds(1)),
                        "golden/absent.dot", READ_ONLY));
        assertEquals("an existing golden resource", error.expected());
        assertTrue(String.valueOf(error.actual()).contains(GoldenOptions.UPDATE_PROPERTY),
                String.valueOf(error.actual()));
    }

    // --- a null subject fails as an assertion, a null argument as a programming error ----------------

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void aNullDefinitionFailsTheTwoArgumentAssertion() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphGoldens.assertMatches(null, RESOURCE));
        assertEquals(RESOURCE, error.context());
        assertEquals("content", error.property());
        assertEquals("matching golden", error.expected());
        assertNull(error.actual());
    }

    /**
     * A malformed name is an error of the calling test whatever else is wrong, so the two-argument entry point
     * reports it before it reads a system property that may be wrong too.
     */
    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void aMalformedNameIsReportedBeforeTheSystemProperties() {
        GraphDefinition<Entity, State, Id> graph = TestDomain.graph(Duration.ofSeconds(1));
        String previousUpdate = System.getProperty(GoldenOptions.UPDATE_PROPERTY);
        String previousRoot = System.getProperty(GoldenOptions.ROOT_PROPERTY);
        try {
            System.setProperty(GoldenOptions.UPDATE_PROPERTY, "yes");
            assertEquals("the resource </abs.dot> must be relative", assertThrows(IllegalArgumentException.class,
                    () -> GraphGoldens.assertMatches(graph, "/abs.dot")).getMessage());
            System.setProperty(GoldenOptions.UPDATE_PROPERTY, "true");
            System.clearProperty(GoldenOptions.ROOT_PROPERTY);
            assertEquals("the resource <../x.dot> carries an empty or relative segment",
                    assertThrows(IllegalArgumentException.class,
                            () -> GraphGoldens.assertMatches(graph, "../x.dot")).getMessage());
        } finally {
            restore(GoldenOptions.UPDATE_PROPERTY, previousUpdate);
            restore(GoldenOptions.ROOT_PROPERTY, previousRoot);
        }
    }

    @Test
    void aNullDefinitionFailsTheThreeArgumentAssertion() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphGoldens.assertMatches(null, RESOURCE, READ_ONLY));
        assertEquals(RESOURCE, error.context());
        assertEquals("content", error.property());
        assertNull(error.actual());
    }

    @Test
    void nullArgumentsAreProgrammingErrors() {
        GraphDefinition<Entity, State, Id> graph = TestDomain.graph(Duration.ofSeconds(1));
        assertEquals("resource", assertThrows(NullPointerException.class,
                () -> GraphGoldens.assertMatches(graph, null, READ_ONLY)).getMessage());
        assertEquals("options", assertThrows(NullPointerException.class,
                () -> GraphGoldens.assertMatches(graph, RESOURCE, null)).getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> GraphGoldens.assertMatches(graph, "../escape.dot", READ_ONLY));
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
