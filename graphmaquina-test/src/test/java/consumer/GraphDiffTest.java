package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.export.GraphView;
import io.github.graphmaquina.export.GraphvizExporter;
import io.github.graphmaquina.test.fixtures.SnapshotGraphs;
import io.github.graphmaquina.test.fixtures.SnapshotGraphs.Gate;
import io.github.graphmaquina.test.fixtures.SnapshotGraphs.Id;
import io.github.graphmaquina.test.fixtures.SnapshotGraphs.Step;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.snapshot.GraphDiff;
import io.github.graphmaquina.test.snapshot.GraphSnapshot;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Contract of the structural diff. Each property {@link SnapshotGraphs} can vary is changed on its own in a
 * pair of graphs built with the real DSL, and the diff must name exactly the paths that changed — including
 * the ones no diagram shows, such as a timeout, an exception class or a hook, and including the identifier
 * of an edge. A path present on one side only stays distinguishable from a path present with an empty
 * value.
 */
class GraphDiffTest {

    private static final String EDGE = "/transitions/NEW/PREPARE";

    // --- the premise of this task: differences the visual projection cannot show --------------------

    @Test
    void snapshotDiffIdentifiesTimeoutProperty() {
        GraphSnapshot before = GraphSnapshot.capture(TestDomain.graph(Duration.ofSeconds(1)));
        GraphSnapshot after = GraphSnapshot.capture(TestDomain.graph(Duration.ofSeconds(30)));
        GraphDiff diff = GraphDiff.compare(before, after);
        assertEquals(List.of(new GraphDiff.Change("/transitions/NEW/PREPARE/timeout",
                Optional.of("PT1S"), Optional.of("PT30S"))), diff.changes());
        assertEquals(before.fields(), GraphSnapshot.parse(before.serialize()).fields());
    }

    /**
     * A timeout, an exception class, a hook and the mode of a guard on an edge that is asynchronous anyway
     * leave the diagram untouched, so the rendering of the two graphs is identical character for character
     * while the snapshot still names the path that differs.
     */
    @Test
    void aGuardATimeoutAHookAndAFailureClassDifferWhileTheDiagramIsIdentical() {
        assertInvisibleToTheDiagram(SnapshotGraphs.variant().build(),
                SnapshotGraphs.variant().timeout(Duration.ofSeconds(30)).build(),
                List.of(change(EDGE + "/timeout", "PT1S", "PT30S")));
        assertInvisibleToTheDiagram(SnapshotGraphs.variant().build(),
                SnapshotGraphs.variant().hookName("notify").build(),
                List.of(change(EDGE + "/afterCommit", "audit", "notify")));
        assertInvisibleToTheDiagram(SnapshotGraphs.variant().build(),
                SnapshotGraphs.variant().failsOn(IllegalStateException.class).build(),
                List.of(change(EDGE + "/failsOn/0",
                        "java.io.IOException", "java.lang.IllegalStateException")));
        assertInvisibleToTheDiagram(SnapshotGraphs.variant().build(),
                SnapshotGraphs.variant().asyncSecondGuard(true).build(),
                List.of(change(EDGE + "/guards/1/async", "false", "true")));
    }

    /**
     * Two graphs with the same states, the same endpoints and the same action, differing only in the
     * identifier of their single edge. The identifier is part of every edge path, so the snapshot shows it.
     */
    @Test
    void aChangedIdentifierIsADifferenceEvenWithTheSameEndpointsAndAction() {
        GraphDiff diff = GraphDiff.compare(
                GraphSnapshot.capture(SnapshotGraphs.identityGraph(Gate.CLOSE)),
                GraphSnapshot.capture(SnapshotGraphs.identityGraph(Gate.REOPEN)));
        assertFalse(diff.isEmpty(), "a changed identifier must be a difference");
        assertEquals(List.of(
                        removed("/transitions/OPEN/CLOSE/action/async", "false"),
                        removed("/transitions/OPEN/CLOSE/action/name", "close"),
                        removed("/transitions/OPEN/CLOSE/failsOn/count", "0"),
                        removed("/transitions/OPEN/CLOSE/guards/count", "0"),
                        removed("/transitions/OPEN/CLOSE/kind", "ACTION"),
                        removed("/transitions/OPEN/CLOSE/to", "CLOSED"),
                        added("/transitions/OPEN/REOPEN/action/async", "false"),
                        added("/transitions/OPEN/REOPEN/action/name", "close"),
                        added("/transitions/OPEN/REOPEN/failsOn/count", "0"),
                        added("/transitions/OPEN/REOPEN/guards/count", "0"),
                        added("/transitions/OPEN/REOPEN/kind", "ACTION"),
                        added("/transitions/OPEN/REOPEN/to", "CLOSED")),
                diff.changes());
    }

    // --- one property at a time ---------------------------------------------------------------------

    @Test
    void theNameOfAnActionIsOneChange() {
        assertEquals(List.of(change(EDGE + "/action/name", "prepare", "start")),
                diff(SnapshotGraphs.variant().build(),
                        SnapshotGraphs.variant().actionName("start").build()));
    }

    @Test
    void theModeOfAGuardIsOneChange() {
        assertEquals(List.of(change(EDGE + "/guards/1/async", "false", "true")),
                diff(SnapshotGraphs.variant().build(),
                        SnapshotGraphs.variant().asyncSecondGuard(true).build()));
    }

    @Test
    void theOrderOfTheGuardsIsVisible() {
        assertEquals(List.of(change(EDGE + "/guards/0/name", "first", "second"),
                        change(EDGE + "/guards/1/name", "second", "first")),
                diff(SnapshotGraphs.variant().build(),
                        SnapshotGraphs.variant().reversedGuards(true).build()));
    }

    @Test
    void theRecoveryIdentifierIsOneChange() {
        assertEquals(List.of(change(EDGE + "/onFailure", "RECOVER", "FINISH")),
                diff(SnapshotGraphs.variant().build(),
                        SnapshotGraphs.variant().onFailure(Id.FINISH).build()));
    }

    @Test
    void theExceptionClassThatTriggersTheRouteIsOneChange() {
        assertEquals(List.of(change(EDGE + "/failsOn/0",
                        "java.io.IOException", "java.lang.IllegalStateException")),
                diff(SnapshotGraphs.variant().build(),
                        SnapshotGraphs.variant().failsOn(IllegalStateException.class).build()));
    }

    @Test
    void theAfterCommitHookIsOneChange() {
        assertEquals(List.of(change(EDGE + "/afterCommit", "audit", "notify")),
                diff(SnapshotGraphs.variant().build(),
                        SnapshotGraphs.variant().hookName("notify").build()));
    }

    @Test
    void theDefaultReasonOfAStateIsOneChange() {
        assertEquals(List.of(change("/policies/READY/otherwise", "only prepare", "no way back")),
                diff(SnapshotGraphs.variant().build(),
                        SnapshotGraphs.variant().policyReason("no way back").build()));
    }

    @Test
    void theReasonDeclaredForOneIdentifierIsOneChange() {
        assertEquals(List.of(change("/policies/READY/rejects/PREPARE",
                        "prepare belongs to NEW", "already prepared")),
                diff(SnapshotGraphs.variant().build(),
                        SnapshotGraphs.variant().rejectReason("already prepared").build()));
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
                        change("/watches/READY/rules/0/transition", "FINISH", "ABANDON"),
                        change("/watches/READY/rules/1/condition", "stalled", "ready"),
                        change("/watches/READY/rules/1/transition", "ABANDON", "FINISH")),
                diff(SnapshotGraphs.variant().build(),
                        SnapshotGraphs.variant().reversedRules(true).build()));
    }

    @Test
    void removingAWatchRemovesEveryFieldOfIt() {
        assertEquals(List.of(
                        removed("/watches/READY/peer/entity", "java.util.Date"),
                        removed("/watches/READY/peer/states",
                                "io.github.graphmaquina.test.fixtures.TestDomain$State"),
                        removed("/watches/READY/peer/transitions",
                                "io.github.graphmaquina.test.fixtures.TestDomain$Id"),
                        removed("/watches/READY/rules/0/condition", "ready"),
                        removed("/watches/READY/rules/0/transition", "FINISH"),
                        removed("/watches/READY/rules/1/condition", "stalled"),
                        removed("/watches/READY/rules/1/transition", "ABANDON"),
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
    void terminalityChangesWithTheExitItRemoves() {
        assertEquals(List.of(
                        change("/policies/B/otherwise",
                                "terminal state B accepts no transitions", "b does not go back"),
                        removed("/terminals/B", "true"),
                        added("/transitions/B/SKIP/failsOn/count", "0"),
                        added("/transitions/B/SKIP/guards/count", "0"),
                        added("/transitions/B/SKIP/kind", "MOVE"),
                        added("/transitions/B/SKIP/to", "C")),
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

    /**
     * Quotes delimit a value, so a quote inside one is escaped: without that, a value that contains the
     * words {@code " but was "} makes two different changes render as the very same line.
     */
    @Test
    void renderEscapesTheQuotesOfAValueSoDifferentChangesNeverReadTheSame() {
        GraphDiff first = GraphDiff.compare(
                GraphSnapshot.parse("graphmaquina-snapshot-v1\n/p\ta\" but was \"b\n"),
                GraphSnapshot.parse("graphmaquina-snapshot-v1\n/p\tc\n"));
        GraphDiff second = GraphDiff.compare(
                GraphSnapshot.parse("graphmaquina-snapshot-v1\n/p\ta\n"),
                GraphSnapshot.parse("graphmaquina-snapshot-v1\n/p\tb\" but was \"c\n"));
        assertEquals("/p: expected \"a\\\" but was \\\"b\" but was \"c\"\n", first.render());
        assertEquals("/p: expected \"a\" but was \"b\\\" but was \\\"c\"\n", second.render());
        assertNotEquals(first.render(), second.render());
    }

    /** A backslash is doubled and a quote gets one in front, so {@code x\"y} and {@code x"y} stay apart. */
    @Test
    void renderKeepsABackslashBeforeAQuoteApartFromTheQuoteAlone() {
        GraphDiff diff = GraphDiff.compare(
                GraphSnapshot.parse("graphmaquina-snapshot-v1\n/p\tx\\\\\"y\n"),
                GraphSnapshot.parse("graphmaquina-snapshot-v1\n/p\tx\"y\n"));
        assertEquals("/p: expected \"x\\\\\\\"y\" but was \"x\\\"y\"\n", diff.render());
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

    private static void assertInvisibleToTheDiagram(GraphDefinition<?, ?, ?> before,
            GraphDefinition<?, ?, ?> after, List<GraphDiff.Change> expected) {
        assertEquals(render(before), render(after), "this change must not be visible in the diagram");
        assertEquals(expected, diff(before, after));
    }

    private static <E, S extends Enum<S>, T extends Enum<T>> String render(
            GraphDefinition<E, S, T> definition) {
        return GraphvizExporter.render(GraphView.of(definition));
    }

    private static List<GraphDiff.Change> diff(
            GraphDefinition<?, ?, ?> before, GraphDefinition<?, ?, ?> after) {
        return GraphDiff.compare(GraphSnapshot.capture(before), GraphSnapshot.capture(after)).changes();
    }

    private static GraphDiff.Change change(String path, String expected, String actual) {
        return new GraphDiff.Change(path, Optional.of(expected), Optional.of(actual));
    }

    private static GraphDiff.Change removed(String path, String expected) {
        return new GraphDiff.Change(path, Optional.of(expected), Optional.empty());
    }

    private static GraphDiff.Change added(String path, String actual) {
        return new GraphDiff.Change(path, Optional.empty(), Optional.of(actual));
    }
}
