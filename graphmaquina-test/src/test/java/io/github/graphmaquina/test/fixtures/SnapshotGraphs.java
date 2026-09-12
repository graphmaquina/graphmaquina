package io.github.graphmaquina.test.fixtures;

import io.github.graphmaquina.api.AsyncTransitionAction;
import io.github.graphmaquina.api.AsyncTransitionGuard;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionGuard;
import io.github.graphmaquina.api.WatchCondition;
import io.github.graphmaquina.dsl.AsyncActionEdge;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.RowBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.WatchBuilder;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Graphs built with the real DSL for the structural snapshot tests. {@link Variant} yields one machine
 * whose every snapshotted property can be changed one at a time, so a diff between two variants names
 * exactly the paths that were touched; {@link #cycleGraph(Step)} and {@link #branchGraph(boolean)} do the
 * same for the initial state and for terminality, which cannot change alone and valid; and
 * {@link #minimalGraph()} is small enough for its serialized form to be pinned character by character.
 *
 * <p>Every action, guard, hook and watch condition here throws when it runs: capturing a snapshot reads
 * the definition only, and a fixture that was executed fails the test that executed it.
 */
public final class SnapshotGraphs {

    /** States of the machine {@link Variant} builds. */
    public enum State { NEW, READY, DONE, FAILED }

    /** States of the machines that vary their initial state or their terminals. */
    public enum Step { A, B, C }

    /** States of the smallest machine, whose whole serialized snapshot fits in one assertion. */
    public enum Flow { OPEN, CLOSED }

    private static final MachineKey<String, State> KEY = MachineKey.of(String.class, State.class);
    private static final MachineKey<String, Step> STEP_KEY = MachineKey.of(String.class, Step.class);
    private static final MachineKey<String, Flow> FLOW_KEY = MachineKey.of(String.class, Flow.class);

    private static final AsyncTransitionAction<String, State> ACTION =
            ctx -> { throw new AssertionError("the action ran during a capture"); };
    private static final TransitionAction<String, Step> STEP_ACTION =
            ctx -> { throw new AssertionError("the synchronous action ran during a capture"); };
    private static final TransitionGuard<String, State> FIRST_GUARD =
            ctx -> { throw new AssertionError("the first guard ran during a capture"); };
    private static final TransitionGuard<String, State> SECOND_GUARD =
            ctx -> { throw new AssertionError("the second guard ran during a capture"); };
    private static final AsyncTransitionGuard<String, State> ASYNC_GUARD =
            ctx -> { throw new AssertionError("an asynchronous guard ran during a capture"); };
    private static final AsyncTransitionGuard<String, Step> STEP_ASYNC_GUARD =
            ctx -> { throw new AssertionError("the asynchronous guard ran during a capture"); };
    private static final Consumer<String> HOOK =
            entity -> { throw new AssertionError("the after-commit hook ran during a capture"); };

    private SnapshotGraphs() { }

    /** A machine with an asynchronous cell, two guards, a failure route, a hook and a watch. */
    public static Variant variant() {
        return new Variant();
    }

    /**
     * {@code A} and {@code B} reach each other, so either of them is a valid initial state and nothing
     * else in the machine has to change with it. Its only action is synchronous, which is what keeps
     * {@code /hasAsync} false while an action is present.
     */
    public static GraphDefinition<String, Step> cycleGraph(Step initial) {
        return StateGraph.define(STEP_KEY).initial(initial).terminal(Step.C).allowsCycle(Step.A)
                .from(Step.A).to(Step.B).action("advance", STEP_ACTION).stay().forbidOthers("a only goes to b")
                .from(Step.B).to(Step.A).move().to(Step.C).move().stay().forbidOthers("b is open")
                .from(Step.C).stay().forbidOthers("c is final")
                .build();
    }

    /**
     * A synchronous action under an asynchronous guard: the one shape where the cell is asynchronous
     * while its action is not, so {@code /cells/A/B/action/async} disagrees with the cell's own flag.
     */
    public static GraphDefinition<String, Step> asyncGuardGraph() {
        return StateGraph.define(STEP_KEY).initial(Step.A).terminal(Step.C)
                .from(Step.A).to(Step.B).action("advance", STEP_ACTION).asyncGuard("quota", STEP_ASYNC_GUARD)
                .to(Step.C).move().stay().forbidOthers("a advances or skips")
                .from(Step.B).to(Step.C).move().stay().forbidOthers("b only finishes")
                .from(Step.C).stay().forbidOthers("c is final")
                .build();
    }

    /**
     * {@code B} is terminal or moves on to {@code C}; a state cannot become terminal while it still has
     * an exit, so the pair differs in the terminal and in the cell that exit occupied.
     */
    public static GraphDefinition<String, Step> branchGraph(boolean terminalB) {
        GraphBuilder<String, Step> start = StateGraph.define(STEP_KEY).initial(Step.A).terminal(Step.C);
        if (terminalB) {
            start = start.terminal(Step.B);
        }
        RowBuilder<String, Step> row = start
                .from(Step.A).to(Step.B).move().to(Step.C).move().stay().forbidOthers("a branches")
                .from(Step.B);
        GraphBuilder<String, Step> rest = terminalB
                ? row.stay().forbidOthers("b does not go back")
                : row.to(Step.C).move().stay().forbidOthers("b does not go back");
        return rest.from(Step.C).stay().forbidOthers("c is final").build();
    }

    /** Two states, one move, one stay and two forbidden cells: every kind but {@code ACTION}. */
    public static GraphDefinition<String, Flow> minimalGraph() {
        return StateGraph.define(FLOW_KEY).initial(Flow.OPEN).terminal(Flow.CLOSED)
                .from(Flow.OPEN).to(Flow.CLOSED).move().forbidOthers("stay put")
                .from(Flow.CLOSED).stay().forbidOthers("closed is final")
                .build();
    }

    /**
     * Builder of the rich machine. Each setter changes one recorded property and nothing else, so the
     * diff between two variants names only the paths that property owns: one path for most of them, and
     * the paths of a whole rule or of the watch for the ones that move or remove a rule.
     */
    public static final class Variant {

        private String actionName = "prepare";
        private boolean asyncSecondGuard;
        private boolean reversedGuards;
        private State onFailure = State.FAILED;
        private Class<? extends Throwable> failsOn = IOException.class;
        private String hookName = "audit";
        private String forbidReason = "only prepare";
        private boolean sqlPeer;
        private boolean reversedRules;
        private boolean watched = true;

        private Variant() { }

        public Variant actionName(String name) {
            this.actionName = name;
            return this;
        }

        public Variant asyncSecondGuard(boolean async) {
            this.asyncSecondGuard = async;
            return this;
        }

        public Variant reversedGuards(boolean reversed) {
            this.reversedGuards = reversed;
            return this;
        }

        public Variant onFailure(State state) {
            this.onFailure = state;
            return this;
        }

        public Variant failsOn(Class<? extends Throwable> type) {
            this.failsOn = type;
            return this;
        }

        public Variant hookName(String name) {
            this.hookName = name;
            return this;
        }

        public Variant forbidReason(String reason) {
            this.forbidReason = reason;
            return this;
        }

        /** Picks between two peer classes that share the simple name {@code Date} under different packages. */
        public Variant sqlPeer(boolean sql) {
            this.sqlPeer = sql;
            return this;
        }

        public Variant reversedRules(boolean reversed) {
            this.reversedRules = reversed;
            return this;
        }

        public Variant watched(boolean watched) {
            this.watched = watched;
            return this;
        }

        public GraphDefinition<String, State> build() {
            AsyncActionEdge<String, State> edge = StateGraph.define(KEY).initial(State.NEW)
                    .terminal(State.DONE).terminal(State.FAILED)
                    .from(State.NEW).to(State.READY).asyncAction(actionName, ACTION);
            edge = reversedGuards ? secondGuard(edge).guard("first", FIRST_GUARD)
                    : secondGuard(edge.guard("first", FIRST_GUARD));
            RowBuilder<String, State> row = edge.timeout(Duration.ofSeconds(1))
                    .onFailure(onFailure).failsOn(failsOn).afterCommit(hookName, HOOK)
                    .to(State.FAILED).move()
                    .to(State.DONE).move()
                    .stay()
                    .from(State.READY).to(State.DONE).move().to(State.FAILED).move().stay();
            return (watched ? watch(row) : row).forbidOthers(forbidReason)
                    .from(State.DONE).stay().forbidOthers("done is final")
                    .from(State.FAILED).stay().forbidOthers("failed is final")
                    .build();
        }

        private AsyncActionEdge<String, State> secondGuard(AsyncActionEdge<String, State> edge) {
            return asyncSecondGuard ? edge.asyncGuard("second", ASYNC_GUARD) : edge.guard("second", SECOND_GUARD);
        }

        private RowBuilder<String, State> watch(RowBuilder<String, State> row) {
            return sqlPeer
                    ? rules(row, MachineKey.of(java.sql.Date.class, TestDomain.State.class))
                    : rules(row, MachineKey.of(java.util.Date.class, TestDomain.State.class));
        }

        private <P> RowBuilder<String, State> rules(
                RowBuilder<String, State> row, MachineKey<P, TestDomain.State> peer) {
            WatchCondition<String, P, TestDomain.State> condition =
                    (owner, peers, view) -> { throw new AssertionError("a watch condition ran during a capture"); };
            WatchBuilder<String, State, P, TestDomain.State> watch =
                    row.watches(peer, owner -> List.<P>of(), other -> Optional.of("owner"));
            if (reversedRules) {
                return watch.when("stalled", condition).goTo(State.FAILED)
                        .when("ready", condition).goTo(State.DONE);
            }
            return watch.when("ready", condition).goTo(State.DONE)
                    .when("stalled", condition).goTo(State.FAILED);
        }
    }
}
