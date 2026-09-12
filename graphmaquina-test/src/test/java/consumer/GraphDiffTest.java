package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.test.fixtures.SnapshotGraphs;
import io.github.graphmaquina.test.fixtures.SnapshotGraphs.State;
import io.github.graphmaquina.test.fixtures.SnapshotGraphs.Step;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.snapshot.GraphDiff;
import io.github.graphmaquina.test.snapshot.GraphSnapshot;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Contract of the structural diff. Every property the snapshot records is changed on its own in a pair of
 * graphs built with the real DSL, and the diff must name exactly the paths that changed — including the
 * ones no diagram shows, such as a timeout, an exception class or the mode of a guard. A path present on
 * one side only stays distinguishable from a path present with an empty value.
 */
class GraphDiffTest {

    private static final String CELL = "/cells/NEW/READY";

    // --- the premise of this task: a difference that Mermaid cannot show -----------------------------

    @Test
    void snapshotDiffIdentifiesTimeoutProperty() {
        GraphSnapshot before = GraphSnapshot.capture(TestDomain.graph(Duration.ofSeconds(1)));
        GraphSnapshot after = GraphSnapshot.capture(TestDomain.graph(Duration.ofSeconds(30)));
        GraphDiff diff = GraphDiff.compare(before, after);
        assertEquals(List.of(new GraphDiff.Change("/cells/NEW/READY/timeout",
                Optional.of("PT1S"), Optional.of("PT30S"))), diff.changes());
        assertEquals(before.fields(), GraphSnapshot.parse(before.serialize()).fields());
    }

    // --- one property at a time ---------------------------------------------------------------------

    @Test
    void theNameOfAnActionIsOneChange() {
        assertEquals(List.of(change(CELL + "/action/name", "prepare", "start")),
                diff(SnapshotGraphs.variant().build(),
                        SnapshotGraphs.variant().actionName("start").build()));
    }

    @Test
    void theModeOfAGuardIsOneChange() {
        assertEquals(List.of(change(CELL + "/guards/1/async", "false", "true")),
                diff(SnapshotGraphs.variant().build(),
                        SnapshotGraphs.variant().asyncSecondGuard(true).build()));
    }

    @Test
    void theOrderOfTheGuardsIsVisible() {
        assertEquals(List.of(change(CELL + "/guards/0/name", "first", "second"),
                        change(CELL + "/guards/1/name", "second", "first")),
                diff(SnapshotGraphs.variant().build(),
                        SnapshotGraphs.variant().reversedGuards(true).build()));
    }

    @Test
    void theFailureRouteIsOneChange() {
        assertEquals(List.of(change(CELL + "/onFailure", "FAILED", "DONE")),
                diff(SnapshotGraphs.variant().build(),
                        SnapshotGraphs.variant().onFailure(State.DONE).build()));
    }

    @Test
    void theExceptionClassThatTriggersTheRouteIsOneChange() {
        assertEquals(List.of(change(CELL + "/failsOn/0",
                        "java.io.IOException", "java.lang.IllegalStateException")),
                diff(SnapshotGraphs.variant().build(),
                        SnapshotGraphs.variant().failsOn(IllegalStateException.class).build()));
    }

    @Test
    void theAfterCommitHookIsOneChange() {
        assertEquals(List.of(change(CELL + "/afterCommit", "audit", "notify")),
                diff(SnapshotGraphs.variant().build(),
                        SnapshotGraphs.variant().hookName("notify").build()));
    }

    @Test
    void theReasonOfAForbiddenCellIsOneChange() {
        assertEquals(List.of(change("/cells/READY/NEW/reason", "only prepare", "no way back")),
                diff(SnapshotGraphs.variant().build(),
                        SnapshotGraphs.variant().forbidReason("no way back").build()));
    }

    @Test
    void aPeerWithTheSameSimpleNameIsStillADifferentPeer() {
        assertEquals(List.of(change("/watches/READY/peer/entity", "java.util.Date", "java.sql.Date")),
                diff(SnapshotGraphs.variant().build(),
                        SnapshotGraphs.variant().sqlPeer(true).build()));
    }

    @Test
    void theOrderOfTheWatchRulesIsVisible() {
        assertEquals(List.of(
                        change("/watches/READY/rules/0/condition", "ready", "stalled"),
                        change("/watches/READY/rules/0/target", "DONE", "FAILED"),
                        change("/watches/READY/rules/1/condition", "stalled", "ready"),
                        change("/watches/READY/rules/1/target", "FAILED", "DONE")),
                diff(SnapshotGraphs.variant().build(),
                        SnapshotGraphs.variant().reversedRules(true).build()));
    }

    @Test
    void removingAWatchRemovesEveryFieldOfIt() {
        assertEquals(List.of(
                        removed("/watches/READY/peer/entity", "java.util.Date"),
                        removed("/watches/READY/peer/states",
                                "io.github.graphmaquina.test.fixtures.TestDomain$State"),
                        removed("/watches/READY/rules/0/condition", "ready"),
                        removed("/watches/READY/rules/0/target", "DONE"),
                        removed("/watches/READY/rules/1/condition", "stalled"),
                        removed("/watches/READY/rules/1/target", "FAILED"),
                        removed("/watches/READY/rules/count", "2")),
                diff(SnapshotGraphs.variant().build(),
                        SnapshotGraphs.variant().watched(false).build()));
    }

    @Test
    void theInitialStateIsOneChange() {
        assertEquals(List.of(change("/initial", "A", "B")),
                diff(SnapshotGraphs.cycleGraph(Step.A), SnapshotGraphs.cycleGraph(Step.B)));
    }

    @Test
    void terminalityChangesWithTheExitItForbids() {
        assertEquals(List.of(
                        change("/cells/B/C/kind", "FORBID", "MOVE"),
                        change("/cells/B/C/reason", "b does not go back", ""),
                        removed("/terminals/B", "true")),
                diff(SnapshotGraphs.branchGraph(true), SnapshotGraphs.branchGraph(false)));
    }

    // --- the shape of the diff itself ---------------------------------------------------------------

    @Test
    void twoIdenticalGraphsDifferInNothing() {
        GraphDiff diff = GraphDiff.compare(GraphSnapshot.capture(SnapshotGraphs.variant().build()),
                GraphSnapshot.capture(SnapshotGraphs.variant().build()));
        assertTrue(diff.isEmpty());
        assertEquals(List.of(), diff.changes());
        assertEquals("", diff.render());
    }

    @Test
    void anAbsentFieldIsNotAnEmptyOne() {
        GraphDiff diff = GraphDiff.compare(
                GraphSnapshot.parse("graphmaquina-snapshot-v1\n/a\t\n/b\tkept\n"),
                GraphSnapshot.parse("graphmaquina-snapshot-v1\n/b\tkept\n"));
        assertFalse(diff.isEmpty());
        assertEquals(List.of(new GraphDiff.Change("/a", Optional.of(""), Optional.empty())),
                diff.changes());
        assertEquals("/a: expected \"\" but was <absent>\n", diff.render());
    }

    @Test
    void renderNamesEveryChangeWithItsEscapedValues() {
        GraphDiff diff = GraphDiff.compare(
                GraphSnapshot.parse("graphmaquina-snapshot-v1\n/one\tplain\n"),
                GraphSnapshot.parse("graphmaquina-snapshot-v1\n/one\ttwo\\tlines\\nhere\n/two\t\n"));
        assertEquals("/one: expected \"plain\" but was \"two\\tlines\\nhere\"\n"
                + "/two: expected <absent> but was \"\"\n", diff.render());
    }

    @Test
    void theChangesAreReadOnly() {
        GraphDiff diff = GraphDiff.compare(GraphSnapshot.capture(SnapshotGraphs.branchGraph(true)),
                GraphSnapshot.capture(SnapshotGraphs.branchGraph(false)));
        List<GraphDiff.Change> changes = diff.changes();
        assertThrows(UnsupportedOperationException.class, changes::clear);
    }

    @Test
    void aNullArgumentIsAProgrammingError() {
        GraphSnapshot snapshot = GraphSnapshot.capture(SnapshotGraphs.minimalGraph());
        assertThrows(NullPointerException.class, () -> GraphDiff.compare(null, snapshot));
        assertThrows(NullPointerException.class, () -> GraphDiff.compare(snapshot, null));
        assertThrows(NullPointerException.class,
                () -> new GraphDiff.Change(null, Optional.of("a"), Optional.of("b")));
        assertThrows(NullPointerException.class,
                () -> new GraphDiff.Change("/a", null, Optional.of("b")));
        assertThrows(NullPointerException.class,
                () -> new GraphDiff.Change("/a", Optional.of("a"), null));
    }

    private static List<GraphDiff.Change> diff(
            GraphDefinition<?, ?> before, GraphDefinition<?, ?> after) {
        return GraphDiff.compare(GraphSnapshot.capture(before), GraphSnapshot.capture(after)).changes();
    }

    private static GraphDiff.Change change(String path, String expected, String actual) {
        return new GraphDiff.Change(path, Optional.of(expected), Optional.of(actual));
    }

    private static GraphDiff.Change removed(String path, String expected) {
        return new GraphDiff.Change(path, Optional.of(expected), Optional.empty());
    }
}
