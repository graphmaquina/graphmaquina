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
 * fixture, because Task 13's goldens and every future diff are read against it.
 */
class GraphSnapshotTest {

    private static final String HEADER = "graphmaquina-snapshot-v1\n";

    private static final String MINIMAL = """
            graphmaquina-snapshot-v1
            /cells/CLOSED/CLOSED/failsOn/count\t0
            /cells/CLOSED/CLOSED/guards/count\t0
            /cells/CLOSED/CLOSED/kind\tSTAY
            /cells/CLOSED/CLOSED/reason\t
            /cells/CLOSED/OPEN/failsOn/count\t0
            /cells/CLOSED/OPEN/guards/count\t0
            /cells/CLOSED/OPEN/kind\tFORBID
            /cells/CLOSED/OPEN/reason\tclosed is final
            /cells/OPEN/CLOSED/failsOn/count\t0
            /cells/OPEN/CLOSED/guards/count\t0
            /cells/OPEN/CLOSED/kind\tMOVE
            /cells/OPEN/CLOSED/reason\t
            /cells/OPEN/OPEN/failsOn/count\t0
            /cells/OPEN/OPEN/guards/count\t0
            /cells/OPEN/OPEN/kind\tFORBID
            /cells/OPEN/OPEN/reason\tstay put
            /hasAsync\tfalse
            /initial\tOPEN
            /machine/entity\tjava.lang.String
            /machine/states\tio.github.graphmaquina.test.fixtures.SnapshotGraphs$Flow
            /states/0\tOPEN
            /states/1\tCLOSED
            /states/count\t2
            /terminals/CLOSED\ttrue
            """;

    // --- what a capture carries ---------------------------------------------------------------------

    @Test
    void captureCarriesEveryPropertyOfAnActionCell() {
        GraphSnapshot snapshot = GraphSnapshot.capture(TestDomain.graph(Duration.ofSeconds(1)));
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("/cells/NEW/READY/action/async", "true");
        expected.put("/cells/NEW/READY/action/name", "prepare");
        expected.put("/cells/NEW/READY/afterCommit", "audit");
        expected.put("/cells/NEW/READY/failsOn/0", "java.io.IOException");
        expected.put("/cells/NEW/READY/failsOn/count", "1");
        expected.put("/cells/NEW/READY/guards/0/async", "false");
        expected.put("/cells/NEW/READY/guards/0/name", "first");
        expected.put("/cells/NEW/READY/guards/1/async", "false");
        expected.put("/cells/NEW/READY/guards/1/name", "second");
        expected.put("/cells/NEW/READY/guards/count", "2");
        expected.put("/cells/NEW/READY/kind", "ACTION");
        expected.put("/cells/NEW/READY/onFailure", "FAILED");
        expected.put("/cells/NEW/READY/reason", "");
        expected.put("/cells/NEW/READY/timeout", "PT1S");
        assertEquals(expected, under(snapshot, "/cells/NEW/READY/"));
        assertEquals("io.github.graphmaquina.test.fixtures.TestDomain$Entity",
                snapshot.fields().get("/machine/entity"));
        assertEquals("io.github.graphmaquina.test.fixtures.TestDomain$State",
                snapshot.fields().get("/machine/states"));
        assertEquals("true", snapshot.fields().get("/hasAsync"));
    }

    @Test
    void aCellWithoutAnActionCarriesNoActionField() {
        GraphSnapshot snapshot = GraphSnapshot.capture(TestDomain.graph(Duration.ofSeconds(1)));
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("/cells/NEW/FAILED/failsOn/count", "0");
        expected.put("/cells/NEW/FAILED/guards/count", "0");
        expected.put("/cells/NEW/FAILED/kind", "MOVE");
        expected.put("/cells/NEW/FAILED/reason", "");
        assertEquals(expected, under(snapshot, "/cells/NEW/FAILED/"));
    }

    @Test
    void watchesCarryTheirPeerAndTheirRulesInOrder() {
        GraphSnapshot snapshot = GraphSnapshot.capture(SnapshotGraphs.variant().build());
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("/watches/READY/peer/entity", "java.util.Date");
        expected.put("/watches/READY/peer/states",
                "io.github.graphmaquina.test.fixtures.TestDomain$State");
        expected.put("/watches/READY/rules/0/condition", "ready");
        expected.put("/watches/READY/rules/0/target", "DONE");
        expected.put("/watches/READY/rules/1/condition", "stalled");
        expected.put("/watches/READY/rules/1/target", "FAILED");
        expected.put("/watches/READY/rules/count", "2");
        assertEquals(expected, under(snapshot, "/watches/"));
    }

    @Test
    void capturingNeverRunsACallbackOfTheGraph() {
        // Every action, guard, hook and condition of the fixture throws AssertionError when invoked.
        GraphSnapshot snapshot = GraphSnapshot.capture(SnapshotGraphs.variant().build());
        assertEquals("prepare", snapshot.fields().get("/cells/NEW/READY/action/name"));
    }

    @Test
    void aSynchronousActionIsRecordedAsSuchAndLeavesTheGraphSynchronous() {
        GraphSnapshot snapshot = GraphSnapshot.capture(SnapshotGraphs.cycleGraph(Step.A));
        assertEquals("ACTION", snapshot.fields().get("/cells/A/B/kind"));
        assertEquals("advance", snapshot.fields().get("/cells/A/B/action/name"));
        assertEquals("false", snapshot.fields().get("/cells/A/B/action/async"));
        assertEquals("false", snapshot.fields().get("/hasAsync"));
    }

    @Test
    void anAsynchronousGuardLeavesTheSynchronousActionSynchronous() {
        GraphSnapshot snapshot = GraphSnapshot.capture(SnapshotGraphs.asyncGuardGraph());
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("/cells/A/B/action/async", "false");
        expected.put("/cells/A/B/action/name", "advance");
        expected.put("/cells/A/B/failsOn/count", "0");
        expected.put("/cells/A/B/guards/0/async", "true");
        expected.put("/cells/A/B/guards/0/name", "quota");
        expected.put("/cells/A/B/guards/count", "1");
        expected.put("/cells/A/B/kind", "ACTION");
        expected.put("/cells/A/B/reason", "");
        assertEquals(expected, under(snapshot, "/cells/A/B/"));
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

    // --- the line format ----------------------------------------------------------------------------

    @Test
    void theWholeSerializedFormIsTheVersionedLineContract() {
        GraphSnapshot snapshot = GraphSnapshot.capture(SnapshotGraphs.minimalGraph());
        assertEquals(MINIMAL, snapshot.serialize());
        assertEquals(snapshot.fields(), GraphSnapshot.parse(MINIMAL).fields());
        assertEquals("", snapshot.fields().get("/cells/OPEN/CLOSED/reason"));
    }

    @Test
    void everyEscapedCharacterSurvivesTheRoundTrip() {
        GraphSnapshot snapshot = GraphSnapshot.capture(
                SnapshotGraphs.variant().forbidReason("back\\slash\ttab\nline\rreturn ñ 世界").build());
        assertTrue(snapshot.serialize().contains(
                        "/cells/READY/NEW/reason\tback\\\\slash\\ttab\\nline\\rreturn ñ 世界\n"),
                snapshot.serialize());
        assertEquals(snapshot.fields(), GraphSnapshot.parse(snapshot.serialize()).fields());
        assertEquals("back\\slash\ttab\nline\rreturn ñ 世界",
                GraphSnapshot.parse(snapshot.serialize()).fields().get("/cells/READY/NEW/reason"));
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

    @Test
    void anUnknownVersionIsRejected() {
        assertMessage("graphmaquina-snapshot-v2\n/initial\tOPEN\n", "unsupported snapshot version");
        assertMessage("", "unsupported snapshot version");
    }

    @Test
    void aVersionLineWithAStrayCarriageReturnNamesTheCarriageReturn() {
        assertMessage("graphmaquina-snapshot-v1\r\n/initial\tOPEN\r\n",
                "unsupported snapshot version \"graphmaquina-snapshot-v1\\r\", "
                        + "expected \"graphmaquina-snapshot-v1\"");
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
