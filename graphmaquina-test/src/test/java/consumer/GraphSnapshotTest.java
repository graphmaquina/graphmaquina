package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.RecoveryRule;
import io.github.graphmaquina.export.GraphView;
import io.github.graphmaquina.export.GraphvizExporter;
import io.github.graphmaquina.test.fixtures.SnapshotGraphs;
import io.github.graphmaquina.test.fixtures.SnapshotGraphs.PublicationFailures;
import io.github.graphmaquina.test.fixtures.SnapshotGraphs.ServiceUnavailableException;
import io.github.graphmaquina.test.fixtures.SnapshotGraphs.Step;
import io.github.graphmaquina.test.fixtures.SnapshotGraphs.TicketChange;
import io.github.graphmaquina.test.fixtures.SnapshotGraphs.TicketState;
import io.github.graphmaquina.test.fixtures.SnapshotGraphs.Verdict;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.snapshot.GraphDiff;
import io.github.graphmaquina.test.snapshot.GraphSnapshot;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * Contract of the versioned structural snapshot: which fields a capture carries, which order they keep,
 * and how they survive the line format. The format is pinned character by character on the smallest
 * fixture, because every golden and every future diff is read against it.
 *
 * <p>Version 2 records the declared edges under {@code /transitions/<from>/<id>} and the rejection policies
 * under {@code /policies/<state>}; there is no path for an edge that was never declared. An edge's recovery
 * rules are recorded under its {@code recoveries} path, indexed in declaration order, only when it declares some,
 * and the sub-machine bound to a state under {@code /submachines/<state>}.
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

    private static final String PUBLISH = "/transitions/READY/PUBLISH";

    /** The binary-name prefix of the types nested in {@code SnapshotGraphs}. */
    private static final String FIXTURE = "io.github.graphmaquina.test.fixtures.SnapshotGraphs$";

    /** The capture of {@code TestDomain}, which declares no rule: the very text of {@code contracts/simple.graph}. */
    private static final String SIMPLE = """
            graphmaquina-snapshot-v2
            /catalog/0\tPREPARE
            /catalog/1\tRECOVER
            /catalog/2\tFINISH
            /catalog/3\tABANDON
            /catalog/count\t4
            /hasAsync\ttrue
            /initial\tNEW
            /machine/entity\tio.github.graphmaquina.test.fixtures.TestDomain$Entity
            /machine/states\tio.github.graphmaquina.test.fixtures.TestDomain$State
            /machine/transitions\tio.github.graphmaquina.test.fixtures.TestDomain$Id
            /policies/DONE/otherwise\tterminal state DONE accepts no transitions
            /policies/FAILED/otherwise\tterminal state FAILED accepts no transitions
            /policies/NEW/otherwise\tprepare first
            /policies/READY/otherwise\tfinish only
            /states/0\tNEW
            /states/1\tREADY
            /states/2\tDONE
            /states/3\tFAILED
            /states/count\t4
            /terminals/DONE\ttrue
            /terminals/FAILED\ttrue
            /transitions/NEW/PREPARE/action/async\ttrue
            /transitions/NEW/PREPARE/action/name\tprepare
            /transitions/NEW/PREPARE/afterCommit\taudit
            /transitions/NEW/PREPARE/failsOn/0\tjava.io.IOException
            /transitions/NEW/PREPARE/failsOn/count\t1
            /transitions/NEW/PREPARE/guards/0/async\tfalse
            /transitions/NEW/PREPARE/guards/0/name\tfirst
            /transitions/NEW/PREPARE/guards/1/async\tfalse
            /transitions/NEW/PREPARE/guards/1/name\tsecond
            /transitions/NEW/PREPARE/guards/count\t2
            /transitions/NEW/PREPARE/kind\tACTION
            /transitions/NEW/PREPARE/onFailure\tRECOVER
            /transitions/NEW/PREPARE/timeout\tPT1S
            /transitions/NEW/PREPARE/to\tREADY
            /transitions/NEW/RECOVER/failsOn/count\t0
            /transitions/NEW/RECOVER/guards/count\t0
            /transitions/NEW/RECOVER/kind\tMOVE
            /transitions/NEW/RECOVER/to\tFAILED
            /transitions/READY/FINISH/failsOn/count\t0
            /transitions/READY/FINISH/guards/count\t0
            /transitions/READY/FINISH/kind\tMOVE
            /transitions/READY/FINISH/to\tDONE
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

    /** Two identifiers leave the same state and the snapshot keeps both, because it keys by identifier and origin. */
    @Test
    void twoIdentifiersLeavingTheSameStateAreTwoEntries() {
        GraphSnapshot snapshot = GraphSnapshot.capture(SnapshotGraphs.variant().build());
        assertEquals("READY", snapshot.fields().get("/transitions/NEW/PREPARE/to"));
        assertEquals("DONE", snapshot.fields().get("/transitions/NEW/FINISH/to"));
        assertEquals("DONE", snapshot.fields().get("/transitions/READY/FINISH/to"));
    }

    // --- recovery rules ---------------------------------------------------------------------------------

    /**
     * Each rule records its kind, its recovery identifier and its throwable types with their count, indexed in
     * declaration order, under the edge that declares it; the edge also records how many rules it has, and no
     * other edge records any.
     */
    @Test
    void theRulesOfAnEdgeAreRecordedWithTheirCountsInDeclarationOrder() {
        GraphSnapshot snapshot = GraphSnapshot.capture(SnapshotGraphs.publication().build());
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put(PUBLISH + "/action/async", "false");
        expected.put(PUBLISH + "/action/name", "publish");
        expected.put(PUBLISH + "/failsOn/0", FIXTURE + "InvalidResponseException");
        expected.put(PUBLISH + "/failsOn/count", "1");
        expected.put(PUBLISH + "/guards/count", "0");
        expected.put(PUBLISH + "/kind", "ACTION");
        expected.put(PUBLISH + "/onFailure", "MARK_INTEGRATION_FAILED");
        expected.put(PUBLISH + "/recoveries/0/failsOn/0", FIXTURE + "ServiceUnavailableException");
        expected.put(PUBLISH + "/recoveries/0/failsOn/count", "1");
        expected.put(PUBLISH + "/recoveries/0/kind", FIXTURE + "PublicationFailures$Unavailable");
        expected.put(PUBLISH + "/recoveries/0/transition", "SCHEDULE_RETRY");
        expected.put(PUBLISH + "/recoveries/1/failsOn/0", FIXTURE + "ProviderRejectedException");
        expected.put(PUBLISH + "/recoveries/1/failsOn/count", "1");
        expected.put(PUBLISH + "/recoveries/1/kind", FIXTURE + "PublicationFailures$Rejected");
        expected.put(PUBLISH + "/recoveries/1/transition", "RECORD_REJECTION");
        expected.put(PUBLISH + "/recoveries/count", "2");
        expected.put(PUBLISH + "/to", "PUBLISHED");
        assertEquals(expected, under(snapshot, PUBLISH + "/"));
        assertEquals(List.copyOf(expected.keySet()), List.copyOf(under(snapshot, PUBLISH + "/").keySet()));
        assertTrue(snapshot.fields().keySet().stream()
                        .filter((String path) -> path.contains("/recoveries/"))
                        .allMatch((String path) -> path.startsWith(PUBLISH + "/recoveries/")),
                snapshot.serialize());
    }

    @Test
    void theKindOfARuleIsOneChange() {
        assertEquals(List.of(change(PUBLISH + "/recoveries/0/kind", FIXTURE + "PublicationFailures$Unavailable",
                        FIXTURE + "PublicationFailures$Throttled")),
                diff(SnapshotGraphs.publication().build(), firstRule(new RecoveryRule<>(
                        PublicationFailures.Throttled.class, TicketChange.SCHEDULE_RETRY,
                        List.of(ServiceUnavailableException.class)))));
    }

    @Test
    void theRecoveryIdentifierOfARuleIsOneChange() {
        assertEquals(List.of(change(PUBLISH + "/recoveries/0/transition", "SCHEDULE_RETRY",
                        "MARK_INTEGRATION_FAILED")),
                diff(SnapshotGraphs.publication().build(), firstRule(new RecoveryRule<>(
                        PublicationFailures.Unavailable.class, TicketChange.MARK_INTEGRATION_FAILED,
                        List.of(ServiceUnavailableException.class)))));
    }

    /**
     * The throwable types of a rule are in the snapshot and not in the diagram: replacing one is one change while
     * the two renderings are identical, adding one adds its index and moves the count, and a rule without types
     * still records a count of zero.
     */
    @Test
    void theThrowableTypesOfARuleDifferWhileTheDiagramIsIdentical() {
        GraphDefinition<String, TicketState, TicketChange> before = SnapshotGraphs.publication().build();
        GraphDefinition<String, TicketState, TicketChange> replaced = firstRule(new RecoveryRule<>(
                PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                List.of(TimeoutException.class)));
        assertEquals(render(before), render(replaced), "a rule's throwable type must not be drawn");
        assertEquals(List.of(change(PUBLISH + "/recoveries/0/failsOn/0", FIXTURE + "ServiceUnavailableException",
                        "java.util.concurrent.TimeoutException")),
                diff(before, replaced));
        assertEquals(List.of(added(PUBLISH + "/recoveries/0/failsOn/1", "java.util.concurrent.TimeoutException"),
                        change(PUBLISH + "/recoveries/0/failsOn/count", "1", "2")),
                diff(before, firstRule(new RecoveryRule<>(PublicationFailures.Unavailable.class,
                        TicketChange.SCHEDULE_RETRY,
                        List.of(ServiceUnavailableException.class, TimeoutException.class)))));
        assertEquals(List.of(removed(PUBLISH + "/recoveries/0/failsOn/0", FIXTURE + "ServiceUnavailableException"),
                        change(PUBLISH + "/recoveries/0/failsOn/count", "1", "0")),
                diff(before, firstRule(new RecoveryRule<>(PublicationFailures.Unavailable.class,
                        TicketChange.SCHEDULE_RETRY, List.of()))));
    }

    /**
     * PUBLISH leaves READY with two rules, RETRY_PENDING with one rule that lists no throwable type, and
     * INTEGRATION_FAILED with none: each origin records its own rules under its own path, and the edge without
     * rules records no recoveries path at all.
     */
    @Test
    void eachOriginOfAnIdentifierRecordsOnlyItsOwnRules() {
        GraphSnapshot snapshot = GraphSnapshot.capture(SnapshotGraphs.publication().republished(true).build());
        String retry = "/transitions/RETRY_PENDING/PUBLISH";
        Map<String, String> expectedRetry = new LinkedHashMap<>();
        expectedRetry.put(retry + "/action/async", "false");
        expectedRetry.put(retry + "/action/name", "publish");
        expectedRetry.put(retry + "/failsOn/count", "0");
        expectedRetry.put(retry + "/guards/count", "0");
        expectedRetry.put(retry + "/kind", "ACTION");
        expectedRetry.put(retry + "/recoveries/0/failsOn/count", "0");
        expectedRetry.put(retry + "/recoveries/0/kind", FIXTURE + "PublicationFailures$Rejected");
        expectedRetry.put(retry + "/recoveries/0/transition", "RECORD_REJECTION");
        expectedRetry.put(retry + "/recoveries/count", "1");
        expectedRetry.put(retry + "/to", "PUBLISHED");
        assertEquals(expectedRetry, under(snapshot, retry + "/"));
        String failed = "/transitions/INTEGRATION_FAILED/PUBLISH";
        Map<String, String> expectedFailed = new LinkedHashMap<>();
        expectedFailed.put(failed + "/action/async", "false");
        expectedFailed.put(failed + "/action/name", "publish");
        expectedFailed.put(failed + "/failsOn/count", "0");
        expectedFailed.put(failed + "/guards/count", "0");
        expectedFailed.put(failed + "/kind", "ACTION");
        expectedFailed.put(failed + "/to", "PUBLISHED");
        assertEquals(expectedFailed, under(snapshot, failed + "/"));
        assertEquals(under(GraphSnapshot.capture(SnapshotGraphs.publication().build()), PUBLISH + "/"),
                under(snapshot, PUBLISH + "/"));
    }

    /**
     * Review focus 4: the order of the rules routes nothing, but the snapshot keeps it, so the same two rules
     * declared the other way round differ at the index paths of both rules and nowhere else.
     */
    @Test
    void reorderedRulesDifferAtTheirIndexPathsOnly() {
        assertEquals(List.of(
                        change(PUBLISH + "/recoveries/0/failsOn/0", FIXTURE + "ServiceUnavailableException",
                                FIXTURE + "ProviderRejectedException"),
                        change(PUBLISH + "/recoveries/0/kind", FIXTURE + "PublicationFailures$Unavailable",
                                FIXTURE + "PublicationFailures$Rejected"),
                        change(PUBLISH + "/recoveries/0/transition", "SCHEDULE_RETRY", "RECORD_REJECTION"),
                        change(PUBLISH + "/recoveries/1/failsOn/0", FIXTURE + "ProviderRejectedException",
                                FIXTURE + "ServiceUnavailableException"),
                        change(PUBLISH + "/recoveries/1/kind", FIXTURE + "PublicationFailures$Rejected",
                                FIXTURE + "PublicationFailures$Unavailable"),
                        change(PUBLISH + "/recoveries/1/transition", "RECORD_REJECTION", "SCHEDULE_RETRY")),
                diff(SnapshotGraphs.publication().build(), SnapshotGraphs.publication()
                        .rules(List.of(SnapshotGraphs.REJECTED_RULE, SnapshotGraphs.UNAVAILABLE_RULE)).build()));
    }

    /**
     * An edge without rules records no recoveries path, not even a count of zero: an action edge with guards, a
     * timeout, a fallback, a failsOn and a hook still serializes to exactly this text, and neither does the
     * publication machine record any once its rules are taken away.
     */
    @Test
    void anEdgeWithoutRulesSerializesWithoutAnyRecoveriesPath() {
        assertEquals(SIMPLE, GraphSnapshot.capture(TestDomain.graph(Duration.ofSeconds(1))).serialize());
        GraphSnapshot withoutRules = GraphSnapshot.capture(SnapshotGraphs.publication().rules(List.of()).build());
        assertTrue(withoutRules.fields().keySet().stream().noneMatch((String path) -> path.contains("/recoveries")),
                withoutRules.serialize());
    }

    // --- sub-machines ------------------------------------------------------------------------------------

    /**
     * A sub-machine records its child machine as a watch records its peer, and the identifier each terminal state of
     * the child exits through. Its lookups never run, and a graph without a sub-machine has no such path at all.
     */
    @Test
    void aSubmachineCarriesItsChildMachineAndTheExitOfEachTerminalState() {
        GraphSnapshot snapshot = GraphSnapshot.capture(SnapshotGraphs.submachineGraph(Verdict.DROP));
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("/submachines/REVIEW/child/entity", "java.lang.String");
        expected.put("/submachines/REVIEW/child/states", FIXTURE + "Step");
        expected.put("/submachines/REVIEW/child/transitions", FIXTURE + "Move");
        expected.put("/submachines/REVIEW/exits/B", "ACCEPT");
        expected.put("/submachines/REVIEW/exits/C", "DROP");
        assertEquals(expected, under(snapshot, "/submachines/"));
        assertEquals(Map.of(), under(GraphSnapshot.capture(SnapshotGraphs.variant().build()), "/submachines/"));
    }

    /** Mapping one terminal state to another identifier differs at that exit's path and nowhere else. */
    @Test
    void theExitOfOneTerminalStateIsOneChange() {
        assertEquals(List.of(change("/submachines/REVIEW/exits/C", "DROP", "ACCEPT")),
                diff(SnapshotGraphs.submachineGraph(Verdict.DROP), SnapshotGraphs.submachineGraph(Verdict.ACCEPT)));
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
                GraphSnapshot.capture(TestDomain.graph(Duration.ofSeconds(30))),
                GraphSnapshot.capture(SnapshotGraphs.publication().build()),
                GraphSnapshot.capture(SnapshotGraphs.publication().republished(true).build()),
                GraphSnapshot.capture(SnapshotGraphs.submachineGraph(Verdict.DROP)))) {
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

    /** The publication machine whose first rule is {@code first} and whose second is the example's. */
    private static GraphDefinition<String, TicketState, TicketChange> firstRule(RecoveryRule<TicketChange> first) {
        return SnapshotGraphs.publication().rules(List.of(first, SnapshotGraphs.REJECTED_RULE)).build();
    }

    private static <E, S extends Enum<S>, T extends Enum<T>> String render(GraphDefinition<E, S, T> definition) {
        return GraphvizExporter.render(GraphView.of(definition));
    }

    private static List<GraphDiff.Change> diff(GraphDefinition<?, ?, ?> before, GraphDefinition<?, ?, ?> after) {
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
