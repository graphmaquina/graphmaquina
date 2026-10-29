package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.GraphSnapshots;
import io.github.graphmaquina.test.fixtures.AutomaticDomain;
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
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract of the marks of the automatic vocabulary in the structural snapshot: an automatic edge, a continuing
 * {@code onFailure} route and a continuing recovery rule are recorded as {@code true} only when they are set, the
 * order of a state with two or more automatic edges is recorded under {@code /automaticOrder/<state>}, and the format
 * stays {@code graphmaquina-snapshot-v1}, so a graph that declares none of them captures what it always did.
 */
class AutomaticSnapshotTest {

    private static final String RESOURCE = "contracts/automatic.graph";

    @Test
    void theMarksAndTheOrderAreRecordedWhereTheGraphDeclaresThem() {
        Map<String, String> fields = GraphSnapshot.capture(AutomaticDomain.graph()).fields();
        assertEquals("true", fields.get("/transitions/NEW/PREPARE/automatic"));
        assertEquals("true", fields.get("/transitions/READY/FINISH/automatic"));
        assertEquals("true", fields.get("/transitions/READY/ABANDON/automatic"));
        assertEquals("true", fields.get("/transitions/FAILED/FINISH/automatic"));
        assertEquals("true", fields.get("/transitions/NEW/PREPARE/recoveries/0/continues"));
        assertEquals("true", fields.get("/transitions/READY/FINISH/onFailureContinues"));
        assertEquals("2", fields.get("/automaticOrder/READY/count"));
        assertEquals("FINISH", fields.get("/automaticOrder/READY/0"));
        assertEquals("ABANDON", fields.get("/automaticOrder/READY/1"));
    }

    @Test
    void aMarkIsAbsentWhereTheGraphDoesNotDeclareIt() {
        Map<String, String> fields = GraphSnapshot.capture(AutomaticDomain.graph()).fields();
        assertFalse(fields.containsKey("/transitions/NEW/RECOVER/automatic"));
        assertFalse(fields.containsKey("/transitions/NEW/ABANDON/automatic"));
        assertFalse(fields.containsKey("/transitions/NEW/PREPARE/recoveries/1/continues"));
        assertFalse(fields.containsKey("/transitions/NEW/PREPARE/onFailureContinues"));
        assertFalse(fields.containsKey("/transitions/READY/FINISH/recoveries/count"));
    }

    @Test
    void aStateWithOneAutomaticEdgeRecordsNoOrder() {
        Map<String, String> fields = GraphSnapshot.capture(AutomaticDomain.graph()).fields();
        assertTrue(fields.keySet().stream().noneMatch((String path) -> path.startsWith("/automaticOrder/NEW")));
        assertTrue(fields.keySet().stream().noneMatch((String path) -> path.startsWith("/automaticOrder/FAILED")));
    }

    @Test
    void aGraphWithoutMarksCapturesNoPathOfTheAutomaticVocabulary() {
        Map<String, String> fields = GraphSnapshot.capture(TestDomain.graph(Duration.ofSeconds(1))).fields();
        assertTrue(fields.keySet().stream().noneMatch((String path) ->
                path.contains("automatic") || path.contains("Automatic") || path.endsWith("continues")
                        || path.endsWith("Continues")), fields.keySet().toString());
        assertTrue(GraphSnapshot.capture(TestDomain.graph(Duration.ofSeconds(1))).serialize()
                .startsWith("graphmaquina-snapshot-v1\n"));
    }

    @Test
    void anEdgeThatTurnsAutomaticIsOneChangeAtItsOwnPath() {
        GraphDiff difference = GraphDiff.compare(GraphSnapshot.capture(AutomaticDomain.graph(false)),
                GraphSnapshot.capture(AutomaticDomain.graph(true)));
        assertEquals("/transitions/NEW/PREPARE/automatic: expected <absent> but was \"true\"\n",
                difference.render());
    }

    @Test
    void aSnapshotWithMarksRoundTripsThroughTheLineFormat() {
        GraphSnapshot snapshot = GraphSnapshot.capture(AutomaticDomain.graph());
        assertEquals(snapshot.fields(), GraphSnapshot.parse(snapshot.serialize()).fields());
        assertEquals(snapshot.serialize(), GraphSnapshot.parse(snapshot.serialize()).serialize());
        assertTrue(snapshot.serialize().contains("/automaticOrder/READY/0\tFINISH\n"));
        assertTrue(snapshot.serialize().contains("/transitions/NEW/PREPARE/automatic\ttrue\n"));
    }

    @Test
    void aGraphWithMarksIsWrittenThenComparedAndAChangedMarkFails(@TempDir Path root) throws Exception {
        GraphDefinition<Entity, State, Id> graph = AutomaticDomain.graph();
        List<Path> written = new ArrayList<>();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            GraphSnapshots.assertMatches(graph, RESOURCE, GoldenOptions.updating(loader, root, written::add));
            assertEquals(List.of(root.resolve(RESOURCE)), written);
            assertEquals(GraphSnapshot.capture(graph).serialize(),
                    Files.readString(root.resolve(RESOURCE), StandardCharsets.UTF_8));
            GraphSnapshots.assertMatches(graph, RESOURCE, GoldenOptions.readOnly(loader));
            GraphAssertionError error = assertThrows(GraphAssertionError.class, () -> GraphSnapshots
                    .assertMatches(AutomaticDomain.graph(false), RESOURCE, GoldenOptions.readOnly(loader)));
            assertEquals("/transitions/NEW/PREPARE/automatic: expected \"true\" but was <absent>\n", error.actual());
        }
    }
}
