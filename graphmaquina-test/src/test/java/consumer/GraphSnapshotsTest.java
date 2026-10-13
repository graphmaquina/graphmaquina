package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.GraphSnapshots;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.Id;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import io.github.graphmaquina.test.snapshot.GoldenOptions;
import io.github.graphmaquina.test.snapshot.GraphDiff;
import io.github.graphmaquina.test.snapshot.GraphSnapshot;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Contract of the structural golden. It pins what the diagram leaves out, so a change of timeout breaks the
 * build with the path that differs instead of with two files; a stored snapshot of an unknown version is
 * reported as the fixture problem it is and never silently rewritten by a read; and update replaces
 * whatever was there with the current version without reading the old one as a snapshot.
 */
class GraphSnapshotsTest {

    private static final String RESOURCE = "contracts/simple.graph";

    /**
     * The negative cases name the checked-in fixture with content that deliberately does not match, so they
     * must never follow {@code graphmaquina.goldens.update}: a regeneration run would rewrite the fixture
     * with that wrong content instead of asserting. Explicit options read no property.
     */
    private static final GoldenOptions READ_ONLY =
            GoldenOptions.readOnly(GraphSnapshotsTest.class.getClassLoader());

    /** Every mismatch names how to regenerate the golden, after the difference it reports. */
    private static final String REGENERATE = "\nrun with -D" + GoldenOptions.UPDATE_PROPERTY + "=true -D"
            + GoldenOptions.ROOT_PROPERTY + "=<source root> to regenerate it";

    // --- the premise of this task: a difference no diagram would show --------------------------------

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void theCheckedInContractIsTheCaptureOfTheFixture() {
        GraphSnapshots.assertMatches(TestDomain.graph(Duration.ofSeconds(1)), RESOURCE);
    }

    @Test
    void aChangedTimeoutFailsWithThePathThatDiffers() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class, () -> GraphSnapshots
                .assertMatches(TestDomain.graph(Duration.ofSeconds(30)), RESOURCE, READ_ONLY));
        assertEquals(RESOURCE, error.context());
        assertEquals("content", error.property());
        assertEquals("matching golden", error.expected());
        assertEquals("/transitions/NEW/PREPARE/timeout: expected \"PT1S\" but was \"PT30S\"\n",
                error.actual());
        assertEquals(RESOURCE + ": content expected <matching golden> but was "
                + "</transitions/NEW/PREPARE/timeout: expected \"PT1S\" but was \"PT30S\"\n>" + REGENERATE,
                error.getMessage());
    }

    /**
     * The store decides the mismatch on the stored text while the report is built from the fields, so a
     * golden whose lines were reordered differs with no field differing. The failure still has to name
     * something to change.
     */
    @Test
    void aGoldenWhoseLinesOnlyMovedFallsBackToTheLineDiff(@TempDir Path root) throws Exception {
        GraphDefinition<Entity, State, Id> graph = TestDomain.graph(Duration.ofSeconds(1));
        String capture = GraphSnapshot.capture(graph).serialize();
        String reordered = capture.replace("/hasAsync\ttrue\n/initial\tNEW\n",
                "/initial\tNEW\n/hasAsync\ttrue\n");
        assertNotEquals(capture, reordered);
        assertTrue(GraphDiff.compare(GraphSnapshot.parse(reordered), GraphSnapshot.parse(capture)).isEmpty());
        Files.createDirectories(root.resolve("contracts"));
        Files.writeString(root.resolve(RESOURCE), reordered, StandardCharsets.UTF_8);
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            GraphAssertionError error = assertThrows(GraphAssertionError.class,
                    () -> GraphSnapshots.assertMatches(graph, RESOURCE, GoldenOptions.readOnly(loader)));
            String difference = """
                    @@ -4,8 +4,8 @@
                     /catalog/2\tFINISH
                     /catalog/3\tABANDON
                     /catalog/count\t4
                    -/initial\tNEW
                     /hasAsync\ttrue
                    +/initial\tNEW
                     /machine/entity\tio.github.graphmaquina.test.fixtures.TestDomain$Entity
                     /machine/states\tio.github.graphmaquina.test.fixtures.TestDomain$State
                     /machine/transitions\tio.github.graphmaquina.test.fixtures.TestDomain$Id
                    """;
            assertEquals(difference, error.actual());
            assertEquals(RESOURCE + ": content expected <matching golden> but was <" + difference + ">"
                    + REGENERATE, error.getMessage());
        }
    }

    // --- writing and reading back --------------------------------------------------------------------

    @Test
    void updateCreatesSourceThenReadOnlyMatchesIt(@TempDir Path root) throws Exception {
        GraphDefinition<Entity, State, Id> graph = TestDomain.graph(Duration.ofSeconds(1));
        List<Path> written = new ArrayList<>();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            GoldenOptions update = GoldenOptions.updating(loader, root, written::add);
            GraphSnapshots.assertMatches(graph, RESOURCE, update);
            assertEquals(List.of(root.resolve(RESOURCE)), written);
            assertEquals(GraphSnapshot.capture(graph).serialize(),
                    Files.readString(root.resolve(RESOURCE), StandardCharsets.UTF_8));
            GraphSnapshots.assertMatches(graph, RESOURCE, GoldenOptions.readOnly(loader));
            GraphSnapshots.assertMatches(graph, RESOURCE, update);
            assertEquals(1, written.size());
        }
    }

    @Test
    void updateReplacesAnUnreadableSnapshotWithTheCurrentVersion(@TempDir Path root) throws Exception {
        GraphDefinition<Entity, State, Id> graph = TestDomain.graph(Duration.ofSeconds(1));
        Files.createDirectories(root.resolve("contracts"));
        Files.writeString(root.resolve(RESOURCE), "graphmaquina-snapshot-v0\nnonsense\n",
                StandardCharsets.UTF_8);
        List<Path> written = new ArrayList<>();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            GraphSnapshots.assertMatches(graph, RESOURCE, GoldenOptions.updating(loader, root, written::add));
        }
        assertEquals(List.of(root.resolve(RESOURCE)), written);
        assertEquals(GraphSnapshot.capture(graph).serialize(),
                Files.readString(root.resolve(RESOURCE), StandardCharsets.UTF_8));
    }

    /** A byte order mark is not part of the version line, so it never makes a current snapshot unsupported. */
    @Test
    void aSnapshotSavedWithAByteOrderMarkIsReadLikeAnyOther(@TempDir Path root) throws Exception {
        GraphDefinition<Entity, State, Id> graph = TestDomain.graph(Duration.ofSeconds(1));
        Files.createDirectories(root.resolve("contracts"));
        Files.writeString(root.resolve(RESOURCE), "\uFEFF" + GraphSnapshot.capture(graph).serialize(),
                StandardCharsets.UTF_8);
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            GraphSnapshots.assertMatches(graph, RESOURCE, GoldenOptions.readOnly(loader));
        }
    }

    /**
     * Update replaces whatever was there, so also a stored text that is not even UTF-8, such as a snapshot saved
     * as Latin-1.
     */
    @Test
    void updateReplacesASnapshotThatIsNotUtf8(@TempDir Path root) throws Exception {
        GraphDefinition<Entity, State, Id> graph = TestDomain.graph(Duration.ofSeconds(1));
        Files.createDirectories(root.resolve("contracts"));
        Files.write(root.resolve(RESOURCE),
                "graphmaquina-snapshot-v1\n/policies/NEW/otherwise\tcaf\u00e9\n"
                        .getBytes(StandardCharsets.ISO_8859_1));
        List<Path> written = new ArrayList<>();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            GraphSnapshots.assertMatches(graph, RESOURCE, GoldenOptions.updating(loader, root, written::add));
        }
        assertEquals(List.of(root.resolve(RESOURCE)), written);
        assertEquals(GraphSnapshot.capture(graph).serialize(),
                Files.readString(root.resolve(RESOURCE), StandardCharsets.UTF_8));
    }

    @Test
    void anUnreadableSnapshotInReadModeExplainsTheFormatAndWritesNothing(@TempDir Path root)
            throws Exception {
        String stored = "graphmaquina-snapshot-v0\nnonsense\n";
        Files.createDirectories(root.resolve("contracts"));
        Files.writeString(root.resolve(RESOURCE), stored, StandardCharsets.UTF_8);
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> GraphSnapshots.assertMatches(TestDomain.graph(Duration.ofSeconds(1)), RESOURCE,
                            GoldenOptions.readOnly(loader)));
            assertTrue(error.getMessage().contains("<" + RESOURCE + ">"), error.getMessage());
            assertTrue(error.getMessage().contains("is not a readable snapshot"), error.getMessage());
            assertTrue(error.getMessage().contains("unsupported snapshot version"), error.getMessage());
            assertInstanceOf(IllegalArgumentException.class, error.getCause());
        }
        assertEquals(stored, Files.readString(root.resolve(RESOURCE), StandardCharsets.UTF_8));
    }

    // --- a null subject fails as an assertion, a null argument as a programming error ----------------

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void aNullDefinitionFailsTheTwoArgumentAssertion() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphSnapshots.assertMatches(null, RESOURCE));
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
            assertEquals("the resource </abs.graph> must be relative", assertThrows(IllegalArgumentException.class,
                    () -> GraphSnapshots.assertMatches(graph, "/abs.graph")).getMessage());
            System.setProperty(GoldenOptions.UPDATE_PROPERTY, "true");
            System.clearProperty(GoldenOptions.ROOT_PROPERTY);
            assertEquals("the resource <../x.graph> carries an empty or relative segment",
                    assertThrows(IllegalArgumentException.class,
                            () -> GraphSnapshots.assertMatches(graph, "../x.graph")).getMessage());
        } finally {
            restore(GoldenOptions.UPDATE_PROPERTY, previousUpdate);
            restore(GoldenOptions.ROOT_PROPERTY, previousRoot);
        }
    }

    @Test
    void aNullDefinitionFailsTheThreeArgumentAssertion() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphSnapshots.assertMatches(null, RESOURCE, READ_ONLY));
        assertEquals(RESOURCE, error.context());
        assertEquals("content", error.property());
        assertNull(error.actual());
    }

    @Test
    void nullArgumentsAreProgrammingErrors() {
        GraphDefinition<Entity, State, Id> graph = TestDomain.graph(Duration.ofSeconds(1));
        assertEquals("resource", assertThrows(NullPointerException.class,
                () -> GraphSnapshots.assertMatches(graph, null, READ_ONLY)).getMessage());
        assertEquals("options", assertThrows(NullPointerException.class,
                () -> GraphSnapshots.assertMatches(graph, RESOURCE, null)).getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> GraphSnapshots.assertMatches(graph, "/contracts/simple.graph", READ_ONLY));
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
