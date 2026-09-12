package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.export.Detail;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.GraphGoldens;
import io.github.graphmaquina.test.fixtures.SnapshotGraphs;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import io.github.graphmaquina.test.snapshot.GoldenOptions;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Contract of the Mermaid golden. Update writes the source file, read mode resolves the very same resource
 * through the class loader, and a second update over matching content writes nothing. The checked-in
 * {@code golden/simple.mmd} pins the default detail — the transitable one, which is also what the exporter
 * defaults to — so the example module's diagrams stay valid, and a mismatch reports the lines that moved.
 */
class GraphGoldensTest {

    private static final String RESOURCE = "golden/simple.mmd";

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
        GraphDefinition<Entity, State> graph = TestDomain.graph(Duration.ofSeconds(1));
        List<Path> written = new ArrayList<>();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            GoldenOptions update = GoldenOptions.updating(loader, root, written::add);
            GraphGoldens.assertMatches(graph, RESOURCE, Detail.FULL, update);
            assertEquals(List.of(root.resolve(RESOURCE)), written);
            GraphGoldens.assertMatches(graph, RESOURCE, Detail.FULL, GoldenOptions.readOnly(loader));
            GraphGoldens.assertMatches(graph, RESOURCE, Detail.FULL, update);
            assertEquals(1, written.size());
        }
    }

    // --- the checked-in fixture and the default detail -----------------------------------------------

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void theCheckedInDiagramIsTheTransitableRenderingOfTheFixture() {
        GraphGoldens.assertMatches(TestDomain.graph(Duration.ofSeconds(1)), RESOURCE);
    }

    @Test
    void theDetailIsHonouredAndFullAddsEveryForbiddenCell() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphGoldens.assertMatches(TestDomain.graph(Duration.ofSeconds(1)), RESOURCE,
                        Detail.FULL, READ_ONLY));
        assertEquals(RESOURCE, error.context());
        assertEquals("content", error.property());
        assertEquals("matching golden", error.expected());
        String diff = String.valueOf(error.actual());
        assertTrue(diff.contains("+    NEW --> NEW : stay"), diff);
        assertTrue(diff.contains("+    NEW --> DONE : forbid: prepare first"), diff);
        assertTrue(diff.contains("+    FAILED --> DONE : forbid: failed is final"), diff);
        assertEquals(13, diff.lines().filter(line -> line.startsWith("+")).count(), diff);
        assertEquals(0, diff.lines().filter(line -> line.startsWith("-")).count(), diff);
    }

    /**
     * Pins that the three-argument overload forwards the caller's detail instead of rendering the default
     * one. It cannot use {@link GoldenOptions#readOnly(ClassLoader)} — the overload builds its own
     * {@link GoldenOptions#defaults()} — so it forces read mode through the property the defaults read,
     * which is also what stops the deliberately mismatching content from rewriting the checked-in fixture.
     */
    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void theThreeArgumentAssertionForwardsTheDetail() {
        String previousUpdate = System.getProperty(GoldenOptions.UPDATE_PROPERTY);
        try {
            System.setProperty(GoldenOptions.UPDATE_PROPERTY, "false");
            GraphAssertionError error = assertThrows(GraphAssertionError.class,
                    () -> GraphGoldens.assertMatches(TestDomain.graph(Duration.ofSeconds(1)), RESOURCE,
                            Detail.FULL));
            assertEquals(RESOURCE, error.context());
            assertEquals("content", error.property());
            assertEquals("matching golden", error.expected());
            String diff = String.valueOf(error.actual());
            assertTrue(diff.contains("+    NEW --> NEW : stay"), diff);
            assertTrue(diff.contains("+    NEW --> DONE : forbid: prepare first"), diff);
            assertEquals(13, diff.lines().filter(line -> line.startsWith("+")).count(), diff);
            assertEquals(0, diff.lines().filter(line -> line.startsWith("-")).count(), diff);
        } finally {
            if (previousUpdate == null) {
                System.clearProperty(GoldenOptions.UPDATE_PROPERTY);
            } else {
                System.setProperty(GoldenOptions.UPDATE_PROPERTY, previousUpdate);
            }
        }
    }

    @Test
    void anotherGraphFailsWithTheLinesThatMoved() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphGoldens.assertMatches(SnapshotGraphs.minimalGraph(), RESOURCE,
                        Detail.TRANSITABLE, READ_ONLY));
        String difference = """
                @@ -1,7 +1,4 @@
                 stateDiagram-v2
                -    [*] --> NEW
                -    NEW --> READY : prepare [async] [if first, second]
                -    NEW --> FAILED : [failure of prepare]
                -    READY --> DONE
                -    DONE --> [*]
                -    FAILED --> [*]
                +    [*] --> OPEN
                +    OPEN --> CLOSED
                +    CLOSED --> [*]
                """;
        assertEquals(difference, error.actual());
        assertEquals(RESOURCE + ": content expected <matching golden> but was <" + difference + ">"
                + REGENERATE, error.getMessage());
    }

    @Test
    void aResourceThatIsNotOnTheClassPathExplainsUpdate() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphGoldens.assertMatches(TestDomain.graph(Duration.ofSeconds(1)),
                        "golden/absent.mmd", Detail.TRANSITABLE, READ_ONLY));
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

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void aNullDefinitionFailsTheThreeArgumentAssertion() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphGoldens.assertMatches(null, RESOURCE, Detail.FULL));
        assertEquals(RESOURCE, error.context());
        assertEquals("content", error.property());
        assertNull(error.actual());
    }

    @Test
    void aNullDefinitionFailsTheFourArgumentAssertion() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphGoldens.assertMatches(null, RESOURCE, Detail.FULL, READ_ONLY));
        assertEquals(RESOURCE, error.context());
        assertEquals("content", error.property());
        assertNull(error.actual());
    }

    @Test
    void nullArgumentsAreProgrammingErrors() {
        GraphDefinition<Entity, State> graph = TestDomain.graph(Duration.ofSeconds(1));
        assertEquals("resource", assertThrows(NullPointerException.class,
                () -> GraphGoldens.assertMatches(graph, null, Detail.FULL, READ_ONLY)).getMessage());
        assertEquals("detail", assertThrows(NullPointerException.class,
                () -> GraphGoldens.assertMatches(graph, RESOURCE, null, READ_ONLY)).getMessage());
        assertEquals("options", assertThrows(NullPointerException.class,
                () -> GraphGoldens.assertMatches(graph, RESOURCE, Detail.FULL, null)).getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> GraphGoldens.assertMatches(graph, "../escape.mmd", Detail.FULL, READ_ONLY));
    }
}
