package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.WatchCondition;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.fixtures.Cell;
import io.github.graphmaquina.fixtures.CellChains;
import io.github.graphmaquina.fixtures.CellStatus;
import io.github.graphmaquina.fixtures.CellTransition;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A call that ends in a real StackOverflowError releases the reentrancy marker of its thread, whatever depth the
 * overflow strikes at: the marker is released by a store that needs no stack, so a release cannot overflow again and
 * leave the thread refusing every later call with ReentrantTransitionException. Each test sweeps, on threads of a small
 * stack, the band of depths where a call ends in an overflow, and after each such call runs a call at a shallow depth
 * on the same thread, which must not be refused. Where the overflow strikes depends on the JIT, so the sweeps prove
 * nothing when they pass by chance; they fail only when the invariant breaks.
 */
class ReentrancyOverflowTest {

    private static final long STACK = 256 * 1024;

    /** The first state of a ping and of a pong, then the second. */
    enum Side { FIRST, SECOND }

    /** The only transition of either machine. */
    enum Flip { FLIP }

    /** An entity of the ping machine, paired with a pong. */
    static final class Ping {
        final String id;
        Side side = Side.FIRST;
        Pong pong;

        Ping(String id) {
            this.id = id;
        }
    }

    /** An entity of the pong machine, paired with a ping. */
    static final class Pong {
        final String id;
        Side side = Side.FIRST;
        Ping ping;

        Pong(String id) {
            this.id = id;
        }
    }

    static final MachineKey<Ping, Side, Flip> PINGS = MachineKey.of(Ping.class, Side.class, Flip.class);
    static final MachineKey<Pong, Side, Flip> PONGS = MachineKey.of(Pong.class, Side.class, Flip.class);

    /**
     * Calls of the cell machine that start deeper and deeper on the thread, from where the call fits to where the call
     * itself no longer does.
     */
    @Test
    void aRootCallThatOverflowsReleasesTheMarkerOfItsThread() throws InterruptedException {
        StateMachine<Cell, CellStatus, CellTransition> cells = CellChains.cells(0, false, 10);
        for (int call = 0; call < 300; call++) {
            cells.execute(CellChains.chain(1)[0], CellTransition.GO);
        }
        List<String> stuck = new ArrayList<>();
        int overflowed = 0;
        for (int round = 0; round < 8 && stuck.isEmpty(); round++) {
            int[] overflows = new int[1];
            Throwable thrown = CellChains.onStack(STACK, () -> sweepTheBandOfDepths(cells, stuck, overflows));
            assertEquals(null, thrown);
            overflowed += overflows[0];
        }
        assertEquals(List.of(), stuck);
        assertTrue(overflowed > 0, "no call overflowed");
    }

    /**
     * An endless chain of watch reactions between two machines that watch each other, under a maxWatchChain that never
     * stops it, overflows the stack wherever the chain is: in a root, a reevaluation, an owner lookup or an observer.
     */
    @Test
    void aWatchChainThatOverflowsReleasesTheMarkerOfItsThread() throws InterruptedException {
        StateMachine<Pong, Side, Flip> endless = pingPong(Integer.MAX_VALUE).of(PONGS);
        StateMachine<Pong, Side, Flip> bounded = pingPong(4).of(PONGS);
        for (int call = 0; call < 300; call++) {
            bounded.execute(pair("warm" + call), Flip.FLIP);
        }
        List<String> stuck = new ArrayList<>();
        int overflowed = 0;
        for (int round = 0; round < 8 && stuck.isEmpty(); round++) {
            long stack = STACK + 64 * 1024 * (round % 4);
            int[] overflows = new int[1];
            Throwable thrown = CellChains.onStack(stack,
                    () -> sweepThePaddings(endless, bounded, stack, stuck, overflows));
            assertEquals(null, thrown);
            overflowed += overflows[0];
        }
        assertEquals(List.of(), stuck);
        assertTrue(overflowed > 0, "no watch chain overflowed");
    }

    /** Runs a deep call at each depth of the band where a call ends in an overflow, then a shallow one. */
    private static void sweepTheBandOfDepths(StateMachine<Cell, CellStatus, CellTransition> cells,
                                             List<String> stuck, int[] overflows) {
        int threshold = firstDepthThatDoesNotFit(cells);
        for (int frames = Math.max(0, threshold - 96); frames <= threshold + 200 && stuck.isEmpty(); frames++) {
            Throwable deep = deepCall(frames, cells);
            if (deep instanceof StackOverflowError) {
                overflows[0]++;
            }
            shallowCall(cells, "a call " + frames + " frames deep ended in " + deep, stuck);
        }
    }

    private static int firstDepthThatDoesNotFit(StateMachine<Cell, CellStatus, CellTransition> cells) {
        int threshold = 0;
        while (threshold < 1_000_000 && fits(threshold, cells)) {
            threshold += 32;
        }
        return threshold;
    }

    /** Starts the endless watch chain under each padding, then a call of the bounded registry on the same thread. */
    private static void sweepThePaddings(StateMachine<Pong, Side, Flip> endless,
                                         StateMachine<Pong, Side, Flip> bounded, long stack,
                                         List<String> stuck, int[] overflows) {
        for (int padding = 0; padding < 400 && stuck.isEmpty(); padding++) {
            Throwable deep = endlessChainThrew(padding, endless);
            if (deep instanceof StackOverflowError) {
                overflows[0]++;
            }
            try {
                bounded.execute(pair("after"), Flip.FLIP);
            } catch (RuntimeException refused) {
                stuck.add("stack " + stack + ", padding " + padding + ": the watch chain ended in " + deep
                        + ", then a call of another registry on the same thread threw " + refused);
            }
        }
    }

    /** What the endless watch chain started under {@code padding} frames threw, if anything. */
    private static Throwable endlessChainThrew(int padding, StateMachine<Pong, Side, Flip> endless) {
        try {
            CellChains.padded(padding, () -> endless.execute(pair("deep"), Flip.FLIP));
            return null;
        } catch (Throwable error) {
            return error;
        }
    }

    /** Whether a call of {@code cells} fits under {@code frames} frames of the current thread's stack. */
    private static boolean fits(int frames, StateMachine<Cell, CellStatus, CellTransition> cells) {
        return !(deepCall(frames, cells) instanceof StackOverflowError);
    }

    /** What a call of {@code cells} under {@code frames} frames threw, if anything. */
    private static Throwable deepCall(int frames, StateMachine<Cell, CellStatus, CellTransition> cells) {
        try {
            CellChains.padded(frames, () -> cells.execute(CellChains.chain(1)[0], CellTransition.GO));
            return null;
        } catch (Throwable error) {
            return error;
        }
    }

    /** A call at the current depth, which must run; when it throws, {@code stuck} records why. */
    private static void shallowCall(StateMachine<Cell, CellStatus, CellTransition> cells, String before,
                                    List<String> stuck) {
        try {
            cells.execute(CellChains.chain(1)[0], CellTransition.GO);
        } catch (RuntimeException refused) {
            stuck.add(before + ", then a shallow call on the same thread threw " + refused);
        }
    }

    /**
     * Two machines that watch each other: each flips once the other reached the side it is on, so a flip of either
     * starts a chain of reactions that only {@code maxWatchChain} stops.
     */
    private static MachineRegistry pingPong(int maxWatchChain) {
        GraphDefinition<Ping, Side, Flip> pings = StateGraph.define(PINGS)
                .initial(Side.FIRST).allowsCycle(Side.FIRST)
                .statePolicy(Side.FIRST, "x").statePolicy(Side.SECOND, "x")
                .transition(Flip.FLIP, Side.FIRST, Side.SECOND).endTransition()
                .transition(Flip.FLIP, Side.SECOND, Side.FIRST).endTransition()
                .watch(Side.FIRST, PONGS, (Ping ping) -> List.of(ping.pong), (Pong pong) -> Optional.of(pong.ping))
                    .when(WatchCondition.anyIn(Side.SECOND)).execute(Flip.FLIP).endWatch()
                .watch(Side.SECOND, PONGS, (Ping ping) -> List.of(ping.pong), (Pong pong) -> Optional.of(pong.ping))
                    .when(WatchCondition.anyIn(Side.FIRST)).execute(Flip.FLIP).endWatch()
                .build();
        GraphDefinition<Pong, Side, Flip> pongs = StateGraph.define(PONGS)
                .initial(Side.FIRST).allowsCycle(Side.FIRST)
                .statePolicy(Side.FIRST, "x").statePolicy(Side.SECOND, "x")
                .transition(Flip.FLIP, Side.FIRST, Side.SECOND).endTransition()
                .transition(Flip.FLIP, Side.SECOND, Side.FIRST).endTransition()
                .watch(Side.FIRST, PINGS, (Pong pong) -> List.of(pong.ping), (Ping ping) -> Optional.of(ping.pong))
                    .when(WatchCondition.anyIn(Side.FIRST)).execute(Flip.FLIP).endWatch()
                .watch(Side.SECOND, PINGS, (Pong pong) -> List.of(pong.ping), (Ping ping) -> Optional.of(ping.pong))
                    .when(WatchCondition.anyIn(Side.SECOND)).execute(Flip.FLIP).endWatch()
                .build();
        return MachineRegistry.builder()
                .maxWatchChain(maxWatchChain)
                .observer(new MachineObserver() { })
                .add(StateMachines.from(pings)
                        .state((Ping ping) -> ping.side, (Ping ping, Side side) -> ping.side = side)
                        .identity((Ping ping) -> ping.id))
                .add(StateMachines.from(pongs)
                        .state((Pong pong) -> pong.side, (Pong pong, Side side) -> pong.side = side)
                        .identity((Pong pong) -> pong.id))
                .build();
    }

    /** A fresh pong, paired with a fresh ping, both on their first side. */
    private static Pong pair(String id) {
        Ping ping = new Ping("ping-" + id);
        Pong pong = new Pong("pong-" + id);
        ping.pong = pong;
        pong.ping = ping;
        return pong;
    }
}
