package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.TransitionSpec;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The chain starts of a commit: one per entity of the log, at the state its last entry leaves it in, round trips and
 * self-loops included, and stopping the chain only when that last entry is the hop of a failure route that stops it.
 * The log is built by hand.
 */
class CommitSummaryChainTest {

    /** States of the fixture machine. */
    enum S { A, B, C }

    /** Transition identifiers of the fixture machine. */
    enum T { GO }

    private static EntityRef ref(Object id) {
        return new EntityRef(MachineKey.of(Object.class, S.class, T.class), id, new Object());
    }

    private static LogEntry hop(EntityRef ref, S from, S to, boolean stopsChain) {
        return new LogEntry.Hop(ref, new TransitionSpec<Object, S, T>(from, T.GO, to,
                Optional.empty(), List.of(), Optional.empty(), List.of(),
                Optional.empty(), Optional.empty()), 0, stopsChain);
    }

    @Test
    void aRoundTripIsAChainStart() {
        EntityRef a = ref(1);
        CommitSummary summary = CommitSummary.from(List.of(hop(a, S.A, S.B, false), hop(a, S.B, S.A, false)));
        assertEquals(List.of(), summary.movedEntities());
        assertEquals(List.of(new ChainStart(a, S.A, false)), summary.chainStarts());
    }

    @Test
    void aSelfLoopIsAChainStart() {
        EntityRef a = ref(1);
        CommitSummary summary = CommitSummary.from(List.of(hop(a, S.B, S.B, false)));
        assertEquals(List.of(), summary.movedEntities());
        assertEquals(List.of(new ChainStart(a, S.B, false)), summary.chainStarts());
    }

    @Test
    void aStartedChildStartsAtItsInitial() {
        EntityRef parent = ref(1);
        EntityRef child = ref(2);
        CommitSummary summary = CommitSummary.from(List.of(hop(parent, S.A, S.B, true),
                new LogEntry.Start(child, S.C, S.A, parent, S.B, 0)));
        assertEquals(List.of(new ChainStart(parent, S.B, true), new ChainStart(child, S.A, false)),
                summary.chainStarts());
    }

    @Test
    void theLastHopDecidesStopsChain() {
        EntityRef a = ref(1);
        EntityRef b = ref(2);
        CommitSummary summary = CommitSummary.from(List.of(hop(a, S.A, S.B, true), hop(a, S.B, S.C, false),
                hop(b, S.A, S.B, false), hop(b, S.B, S.C, true)));
        assertEquals(List.of(new ChainStart(a, S.C, false), new ChainStart(b, S.C, true)), summary.chainStarts());
    }

    @Test
    void orderIsFirstAppearance() {
        EntityRef a = ref(1);
        EntityRef b = ref(2);
        EntityRef c = ref(3);
        CommitSummary summary = CommitSummary.from(List.of(hop(b, S.A, S.B, false), hop(c, S.A, S.C, false),
                hop(a, S.A, S.B, false), hop(b, S.B, S.C, false)));
        assertEquals(List.of(new ChainStart(b, S.C, false), new ChainStart(c, S.C, false),
                new ChainStart(a, S.B, false)), summary.chainStarts());
    }

    @Test
    void anEmptyLogHasNoChainStart() {
        assertEquals(List.of(), CommitSummary.EMPTY.chainStarts());
    }
}
