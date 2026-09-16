package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.test.fixtures.SnapshotGraphs;
import io.github.graphmaquina.test.fixtures.SnapshotGraphs.Step;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.snapshot.GraphSnapshot;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Contract of the versioned structural snapshot: which fields a capture carries, which order they keep,
 * and how they survive the line format. The format is pinned character by character on the smallest
 * fixture, because every golden and every future diff is read against it.
 *
 * <p>Version 2 records the declared edges under {@code /transitions/<from>/<id>} and the rejection policies
 * under {@code /policies/<state>}; there is no path for an edge that was never declared.
 */
class GraphSnapshotTest {

    private static final String HEADER = "graphmaquina-snapshot-v2\n";

    private static final String MINIMAL = """
            graphmaquina-snapshot-v2
            /catalog/0\tCLOSE
            /catalog/1\tREOPEN
            /catalog/count\t2
            /hasAsync\tfalse
            /initial\tOPEN
            /machine/entity\tjava.lang.String
            /machine/states\tio.github.graphmaquina.test.fixtures.SnapshotGraphs$Flow
            /machine/transitions\tio.github.graphmaquina.test.fixtures.SnapshotGraphs$Gate
            /policies/CLOSED/otherwise\tterminal state CLOSED accepts no transitions
            /policies/OPEN/otherwise\tstay put
            /states/0\tOPEN
            /states/1\tCLOSED
            /states/count\t2
            /terminals/CLOSED\ttrue
            /transitions/OPEN/CLOSE/failsOn/count\t0
            /transitions/OPEN/CLOSE/guards/count\t0
            /transitions/OPEN/CLOSE/kind\tMOVE
            /transitions/OPEN/CLOSE/to\tCLOSED
            """;

    // --- what a capture carries ---------------------------------------------------------------------

    @Test
    void captureCarriesEveryPropertyOfAnActionEdge() {
        GraphSnapshot snapshot = GraphSnapshot.capture(TestDomain.graph(Duration.ofSeconds(1)));
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("/transitions/NEW/PREPARE/action/async", "true");
        expected.put("/transitions/NEW/PREPARE/action/name", "prepare");
        expected.put("/transitions/NEW/PREPARE/afterCommit", "audit");
        expected.put("/transitions/NEW/PREPARE/failsOn/0", "java.io.IOException");
        expected.put("/transitions/NEW/PREPARE/failsOn/count", "1");
        expected.put("/transitions/NEW/PREPARE/guards/0/async", "false");
        expected.put("/transitions/NEW/PREPARE/guards/0/name", "first");
        expected.put("/transitions/NEW/PREPARE/guards/1/async", "false");
        expected.put("/transitions/NEW/PREPARE/guards/1/name", "second");
        expected.put("/transitions/NEW/PREPARE/guards/count", "2");
        expected.put("/transitions/NEW/PREPARE/kind", "ACTION");
        expected.put("/transitions/NEW/PREPARE/onFailure", "RECOVER");
        expected.put("/transitions/NEW/PREPARE/timeout", "PT1S");
        expected.put("/transitions/NEW/PREPARE/to", "READY");
        assertEquals(expected, under(snapshot, "/transitions/NEW/PREPARE/"));
        assertEquals("io.github.graphmaquina.test.fixtures.TestDomain$Entity",
                snapshot.fields().get("/machine/entity"));
        assertEquals("io.github.graphmaquina.test.fixtures.TestDomain$State",
                snapshot.fields().get("/machine/states"));
        assertEquals("io.github.graphmaquina.test.fixtures.TestDomain$Id",
                snapshot.fields().get("/machine/transitions"));
        assertEquals("true", snapshot.fields().get("/hasAsync"));
    }

    @Test
    void anEdgeWithoutAnActionCarriesNoActionField() {
        GraphSnapshot snapshot = GraphSnapshot.capture(TestDomain.graph(Duration.ofSeconds(1)));
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("/transitions/NEW/RECOVER/failsOn/count", "0");
        expected.put("/transitions/NEW/RECOVER/guards/count", "0");
        expected.put("/transitions/NEW/RECOVER/kind", "MOVE");
        expected.put("/transitions/NEW/RECOVER/to", "FAILED");
        assertEquals(expected, under(snapshot, "/transitions/NEW/RECOVER/"));
    }

    /** Absence has no path: what a graph does not declare is simply not in the capture. */
    @Test
    void anIdentifierWithoutAnEdgeCarriesNoPathAtAll() {
        GraphSnapshot snapshot = GraphSnapshot.capture(TestDomain.graph(Duration.ofSeconds(1)));
        assertEquals(Map.of(), under(snapshot, "/transitions/NEW/ABANDON/"));
        assertEquals(Map.of(), under(snapshot, "/transitions/READY/PREPARE/"));
        assertEquals(Map.of(), under(snapshot, "/transitions/DONE/"));
        assertTrue(snapshot.fields().keySet().stream().noneMatch(path -> path.startsWith("/cells/")),
                "the retired matrix paths must not come back");
        assertEquals("ABANDON", snapshot.fields().get("/catalog/3"));
    }

    @Test
    void everyStateCarriesItsRejectionPolicy() {
        GraphSnapshot snapshot = GraphSnapshot.capture(SnapshotGraphs.variant().build());
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("/policies/DONE/otherwise", "terminal state DONE accepts no transitions");
        expected.put("/policies/FAILED/otherwise", "terminal state FAILED accepts no transitions");
        expected.put("/policies/NEW/otherwise", "prepare first");
        expected.put("/policies/READY/otherwise", "only prepare");
        expected.put("/policies/READY/rejects/PREPARE", "prepare belongs to NEW");
        assertEquals(expected, under(snapshot, "/policies/"));
    }

    @Test
    void watchesCarryTheirPeerAndTheirRulesInOrder() {
        GraphSnapshot snapshot = GraphSnapshot.capture(SnapshotGraphs.variant().build());
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("/watches/READY/peer/entity", "java.util.Date");
        expected.put("/watches/READY/peer/states",
                "io.github.graphmaquina.test.fixtures.TestDomain$State");
        expected.put("/watches/READY/peer/transitions",
                "io.github.graphmaquina.test.fixtures.TestDomain$Id");
        expected.put("/watches/READY/rules/0/condition", "ready");
        expected.put("/watches/READY/rules/0/transition", "FINISH");
        expected.put("/watches/READY/rules/1/condition", "stalled");
        expected.put("/watches/READY/rules/1/transition", "ABANDON");
        expected.put("/watches/READY/rules/count", "2");
        assertEquals(expected, under(snapshot, "/watches/"));
    }

    @Test
    void capturingNeverRunsACallbackOfTheGraph() {
        // Every action, guard, hook and condition of the fixture throws AssertionError when invoked.
        GraphSnapshot snapshot = GraphSnapshot.capture(SnapshotGraphs.variant().build());
        assertEquals("prepare", snapshot.fields().get("/transitions/NEW/PREPARE/action/name"));
    }

    @Test
    void aSynchronousActionIsRecordedAsSuchAndLeavesTheGraphSynchronous() {
        GraphSnapshot snapshot = GraphSnapshot.capture(SnapshotGraphs.cycleGraph(Step.A));
        assertEquals("ACTION", snapshot.fields().get("/transitions/A/ADVANCE/kind"));
        assertEquals("advance", snapshot.fields().get("/transitions/A/ADVANCE/action/name"));
        assertEquals("false", snapshot.fields().get("/transitions/A/ADVANCE/action/async"));
        assertEquals("false", snapshot.fields().get("/hasAsync"));
    }

    @Test
    void anAsynchronousGuardLeavesTheSynchronousActionSynchronous() {
        GraphSnapshot snapshot = GraphSnapshot.capture(SnapshotGraphs.asyncGuardGraph());
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("/transitions/A/ADVANCE/action/async", "false");
        expected.put("/transitions/A/ADVANCE/action/name", "advance");
        expected.put("/transitions/A/ADVANCE/failsOn/count", "0");
        expected.put("/transitions/A/ADVANCE/guards/0/async", "true");
        expected.put("/transitions/A/ADVANCE/guards/0/name", "quota");
        expected.put("/transitions/A/ADVANCE/guards/count", "1");
        expected.put("/transitions/A/ADVANCE/kind", "ACTION");
        expected.put("/transitions/A/ADVANCE/to", "B");
        assertEquals(expected, under(snapshot, "/transitions/A/ADVANCE/"));
        assertEquals("true", snapshot.fields().get("/hasAsync"));
    }

    @Test
    void theStatesKeepTheirDeclarationOrderAndTheTerminalsAreFlagged() {
        GraphSnapshot snapshot = GraphSnapshot.capture(SnapshotGraphs.branchGraph(true));
        assertEquals("A", snapshot.fields().get("/states/0"));
        assertEquals("B", snapshot.fields().get("/states/1"));
        assertEquals("C", snapshot.fields().get("/states/2"));
        assertEquals("3", snapshot.fields().get("/states/count"));
        assertEquals(Map.of("/terminals/B", "true", "/terminals/C", "true"),
                under(snapshot, "/terminals/"));
        assertEquals("A", snapshot.fields().get("/initial"));
    }

    @Test
    void theCatalogueKeepsTheOrdinalOrderOfTheIdentifierEnum() {
        GraphSnapshot snapshot = GraphSnapshot.capture(SnapshotGraphs.branchGraph(true));
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("/catalog/0", "ADVANCE");
        expected.put("/catalog/1", "BACK");
        expected.put("/catalog/2", "SKIP");
        expected.put("/catalog/count", "3");
        assertEquals(expected, under(snapshot, "/catalog/"));
    }

    /** Parallel identifiers share a destination, and the snapshot keeps both because it keys by identifier. */
    @Test
    void twoIdentifiersLeavingTheSameStateAreTwoEntries() {
        GraphSnapshot snapshot = GraphSnapshot.capture(SnapshotGraphs.variant().build());
        assertEquals("READY", snapshot.fields().get("/transitions/NEW/PREPARE/to"));
        assertEquals("DONE", snapshot.fields().get("/transitions/NEW/FINISH/to"));
        assertEquals("DONE", snapshot.fields().get("/transitions/READY/FINISH/to"));
    }

    // --- the line format ----------------------------------------------------------------------------

    @Test
    void theWholeSerializedFormIsTheVersionedLineContract() {
        GraphSnapshot snapshot = GraphSnapshot.capture(SnapshotGraphs.minimalGraph());
        assertEquals(MINIMAL, snapshot.serialize());
        assertEquals(snapshot.fields(), GraphSnapshot.parse(MINIMAL).fields());
    }

    @Test
    void everyEscapedCharacterSurvivesTheRoundTrip() {
        GraphSnapshot snapshot = GraphSnapshot.capture(
                SnapshotGraphs.variant().policyReason("back\\slash\ttab\nline\rreturn ñ 世界").build());
        assertTrue(snapshot.serialize().contains(
                        "/policies/READY/otherwise\tback\\\\slash\\ttab\\nline\\rreturn ñ 世界\n"),
                snapshot.serialize());
        assertEquals(snapshot.fields(), GraphSnapshot.parse(snapshot.serialize()).fields());
        assertEquals("back\\slash\ttab\nline\rreturn ñ 世界",
                GraphSnapshot.parse(snapshot.serialize()).fields().get("/policies/READY/otherwise"));
    }

    @Test
    void theFieldsAreSortedWhateverOrderTheyArrivedIn() {
        GraphSnapshot snapshot = GraphSnapshot.parse(HEADER + "/states/1\tCLOSED\n/initial\tOPEN\n");
        assertEquals(List.of("/initial", "/states/1"), List.copyOf(snapshot.fields().keySet()));
        assertEquals(HEADER + "/initial\tOPEN\n/states/1\tCLOSED\n", snapshot.serialize());
    }

    @Test
    void theFieldsCannotBeChangedThroughTheSnapshot() {
        Map<String, String> fields = GraphSnapshot.capture(SnapshotGraphs.minimalGraph()).fields();
        assertThrows(UnsupportedOperationException.class, () -> fields.put("/initial", "CLOSED"));
    }

    // --- what parse rejects -------------------------------------------------------------------------

    /** The matrix format is a different model, so it is refused with what to do about it, never converted. */
    @Test
    void theRetiredMatrixSnapshotIsRejectedWithAMigrationInstruction() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> GraphSnapshot.parse("graphmaquina-snapshot-v1\n/cells/OPEN/CLOSED/kind\tMOVE\n"));
        assertTrue(error.getMessage().contains("graphmaquina-snapshot-v1"), error.getMessage());
        assertTrue(error.getMessage().contains("regenerate it"), error.getMessage());
        assertTrue(error.getMessage().contains("graphmaquina-snapshot-v2"), error.getMessage());
    }

    @Test
    void anUnknownVersionIsRejected() {
        assertMessage("graphmaquina-snapshot-v3\n/initial\tOPEN\n", "unsupported snapshot version");
        assertMessage("", "unsupported snapshot version");
    }

    @Test
    void aVersionLineWithAStrayCarriageReturnNamesTheCarriageReturn() {
        assertMessage("graphmaquina-snapshot-v2\r\n/initial\tOPEN\r\n",
                "unsupported snapshot version \"graphmaquina-snapshot-v2\\r\", "
                        + "expected \"graphmaquina-snapshot-v2\"");
    }

    @Test
    void aMissingFinalLineFeedIsRejected() {
        assertMessage(HEADER + "/initial\tOPEN", "must end with a line feed");
    }

    @Test
    void aLineWithoutASeparatorIsRejected() {
        assertMessage(HEADER + "/initial OPEN\n", "line 2 has no separator");
        assertMessage(HEADER + "/initial\tOPEN\n\n", "line 3 has no separator");
    }

    @Test
    void anExtraUnescapedSeparatorIsRejected() {
        assertMessage(HEADER + "/initial\tOPEN\tCLOSED\n", "line 2 has more than one unescaped separator");
    }

    @Test
    void anUnknownEscapeIsRejected() {
        assertMessage(HEADER + "/initial\t\\x\n", "line 2 carries the unknown escape <\\x>");
    }

    @Test
    void aTruncatedEscapeIsRejected() {
        assertMessage(HEADER + "/initial\tOPEN\\\n", "line 2 ends with a truncated escape");
    }

    @Test
    void aRepeatedPathIsRejected() {
        assertMessage(HEADER + "/initial\tOPEN\n/initial\tCLOSED\n",
                "the path </initial> is repeated on line 3");
    }

    @Test
    void aNullArgumentIsAProgrammingError() {
        assertThrows(NullPointerException.class, () -> GraphSnapshot.capture(null));
        assertThrows(NullPointerException.class, () -> GraphSnapshot.parse(null));
    }

    @Test
    void aSnapshotOfEveryFixtureRoundTrips() {
        for (GraphSnapshot snapshot : List.of(
                GraphSnapshot.capture(SnapshotGraphs.variant().build()),
                GraphSnapshot.capture(SnapshotGraphs.cycleGraph(Step.B)),
                GraphSnapshot.capture(TestDomain.graph(Duration.ofSeconds(30))))) {
            assertEquals(snapshot.fields(), GraphSnapshot.parse(snapshot.serialize()).fields());
            assertEquals(snapshot.serialize(), GraphSnapshot.parse(snapshot.serialize()).serialize());
        }
    }

    private static void assertMessage(String text, String expected) {
        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> GraphSnapshot.parse(text));
        assertTrue(error.getMessage().contains(expected), error.getMessage());
    }

    private static Map<String, String> under(GraphSnapshot snapshot, String prefix) {
        Map<String, String> selected = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : snapshot.fields().entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                selected.put(entry.getKey(), entry.getValue());
            }
        }
        return selected;
    }
}
