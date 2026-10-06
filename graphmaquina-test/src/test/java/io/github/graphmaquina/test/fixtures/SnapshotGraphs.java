package io.github.graphmaquina.test.fixtures;

import io.github.graphmaquina.api.AsyncTransitionAction;
import io.github.graphmaquina.api.AsyncTransitionGuard;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionGuard;
import io.github.graphmaquina.api.WatchCondition;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.dsl.WatchBuilder;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Graphs built with the real DSL for the structural snapshot tests. {@link Variant} yields one machine
 * whose action name, guard mode and order, recovery identifier, failure class, hook, timeout, rejection
 * reasons, watch peer, watch rules and watch itself can each be changed on their own, so a diff between two
 * variants names exactly the paths that were touched; {@link #cycleGraph(Step)} and
 * {@link #branchGraph(boolean)} do the same for the initial state and for terminality, which cannot change
 * alone and valid; {@link #identityGraph(Gate)} changes nothing but the identifier of an edge;
 * {@link #asyncGuardGraph()} is the one shape whose edge is asynchronous while its action is not; and
 * {@link #minimalGraph()} is small enough for its serialized form to be pinned character by character.
 *
 * <p>Every action, guard, hook and watch condition here throws when it runs: capturing a snapshot reads
 * the definition only, and a fixture that was executed fails the test that executed it.
 */
public final class SnapshotGraphs {

    /** States of the machine {@link Variant} builds. */
    public enum State { NEW, READY, DONE, FAILED }

    /** Transition identifiers of the machine {@link Variant} builds. */
    public enum Id { PREPARE, RECOVER, FINISH, ABANDON }

    /**
     * States of {@link #cycleGraph(Step)}, {@link #asyncGuardGraph()} and {@link #branchGraph(boolean)}; the first
     * varies its initial state and the last its terminals.
     */
    public enum Step { A, B, C }

    /** Transition identifiers of the machines built over {@link Step}. */
    public enum Move { ADVANCE, BACK, SKIP }

    /** States of the smallest machine, whose whole serialized snapshot fits in one assertion. */
    public enum Flow { OPEN, CLOSED }

    /** Transition identifiers of the smallest machine; the pair that tells two identities apart. */
    public enum Gate { CLOSE, REOPEN }

    private static final MachineKey<String, State, Id> KEY =
            MachineKey.of(String.class, State.class, Id.class);
    private static final MachineKey<String, Step, Move> STEP_KEY =
            MachineKey.of(String.class, Step.class, Move.class);
    private static final MachineKey<String, Flow, Gate> FLOW_KEY =
            MachineKey.of(String.class, Flow.class, Gate.class);

    private static final AsyncTransitionAction<String, State, Id> ACTION =
            ctx -> { throw new AssertionError("the action ran during a capture"); };
    private static final TransitionAction<String, Step, Move> STEP_ACTION =
            ctx -> { throw new AssertionError("the synchronous action ran during a capture"); };
    private static final TransitionAction<String, Flow, Gate> FLOW_ACTION =
            ctx -> { throw new AssertionError("the identified action ran during a capture"); };
    private static final TransitionGuard<String, State, Id> FIRST_GUARD =
            ctx -> { throw new AssertionError("the first guard ran during a capture"); };
    private static final TransitionGuard<String, State, Id> SECOND_GUARD =
            ctx -> { throw new AssertionError("the second guard ran during a capture"); };
    private static final AsyncTransitionGuard<String, State, Id> ASYNC_GUARD =
            ctx -> { throw new AssertionError("an asynchronous guard ran during a capture"); };
    private static final AsyncTransitionGuard<String, Step, Move> STEP_ASYNC_GUARD =
            ctx -> { throw new AssertionError("the asynchronous guard ran during a capture"); };
    private static final Consumer<String> HOOK =
            entity -> { throw new AssertionError("the after-commit hook ran during a capture"); };

    private SnapshotGraphs() { }

    /** The builder of a machine with an asynchronous edge, two guards, a recovery identifier, a hook and a watch. */
    public static Variant variant() {
        return new Variant();
    }

    /**
     * {@code A} and {@code B} reach each other, so either of them is a valid initial state and nothing
     * else in the machine has to change with it. Its only action is synchronous, which is what keeps
     * {@code /hasAsync} false while an action is present.
     */
    public static GraphDefinition<String, Step, Move> cycleGraph(Step initial) {
        return StateGraph.define(STEP_KEY).initial(initial).terminal(Step.C).allowsCycle(Step.A)
                .statePolicy(Step.A, "a only goes to b").statePolicy(Step.B, "b is open")
                .transition(Move.ADVANCE, Step.A, Step.B).action("advance", STEP_ACTION).endTransition()
                .transition(Move.BACK, Step.B, Step.A).endTransition()
                .transition(Move.SKIP, Step.B, Step.C).endTransition()
                .build();
    }

    /**
     * A synchronous action under an asynchronous guard: the one shape where the edge is asynchronous
     * while its action is not, so {@code /transitions/A/ADVANCE/action/async} disagrees with the graph's
     * own flag.
     */
    public static GraphDefinition<String, Step, Move> asyncGuardGraph() {
        return StateGraph.define(STEP_KEY).initial(Step.A).terminal(Step.C)
                .statePolicy(Step.A, "a advances or skips").statePolicy(Step.B, "b only finishes")
                .transition(Move.ADVANCE, Step.A, Step.B).action("advance", STEP_ACTION)
                    .asyncGuard("quota", STEP_ASYNC_GUARD).endTransition()
                .transition(Move.SKIP, Step.A, Step.C).endTransition()
                .transition(Move.SKIP, Step.B, Step.C).endTransition()
                .build();
    }

    /**
     * {@code B} is terminal or moves on to {@code C}; a state cannot become terminal while it still has
     * an exit, so the pair differs in the terminal, in the edge that exit was, and in the policy a
     * terminal state generates for itself.
     */
    public static GraphDefinition<String, Step, Move> branchGraph(boolean terminalB) {
        GraphBuilder<String, Step, Move> start = StateGraph.define(STEP_KEY).initial(Step.A).terminal(Step.C)
                .statePolicy(Step.A, "a branches");
        if (terminalB) {
            start = start.terminal(Step.B);
        } else {
            start = start.statePolicy(Step.B, "b does not go back")
                    .transition(Move.SKIP, Step.B, Step.C).endTransition();
        }
        return start.transition(Move.ADVANCE, Step.A, Step.B).endTransition()
                .transition(Move.SKIP, Step.A, Step.C).endTransition()
                .build();
    }

    /** Two states and one edge: every path of the snapshot fits in one assertion. */
    public static GraphDefinition<String, Flow, Gate> minimalGraph() {
        return StateGraph.define(FLOW_KEY).initial(Flow.OPEN).terminal(Flow.CLOSED)
                .statePolicy(Flow.OPEN, "stay put")
                .transition(Gate.CLOSE, Flow.OPEN, Flow.CLOSED).endTransition()
                .build();
    }

    /**
     * The same endpoints and the same action under a different identifier. Two of these differ in nothing
     * a matrix could have recorded, which is exactly what makes the identifier part of the contract.
     */
    public static GraphDefinition<String, Flow, Gate> identityGraph(Gate transition) {
        return StateGraph.define(FLOW_KEY).initial(Flow.OPEN).terminal(Flow.CLOSED)
                .statePolicy(Flow.OPEN, "stay put")
                .transition(transition, Flow.OPEN, Flow.CLOSED).action("close", FLOW_ACTION).endTransition()
                .build();
    }

    /**
     * Builder of the rich machine. Each setter changes one recorded property and nothing else, so the
     * diff between two variants names only the paths that property owns: one path for most of them, and
     * several for the ones that reorder the guards or the rules or remove the watch.
     */
    public static final class Variant {

        private String actionName = "prepare";
        private boolean asyncSecondGuard;
        private boolean reversedGuards;
        private Id onFailure = Id.RECOVER;
        private Class<? extends Throwable> failsOn = IOException.class;
        private String hookName = "audit";
        private String policyReason = "only prepare";
        private String rejectReason = "prepare belongs to NEW";
        private Duration timeout = Duration.ofSeconds(1);
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

        /** {@code RECOVER} and {@code FINISH} both leave {@code NEW}, so either of them is a valid recovery route. */
        public Variant onFailure(Id transition) {
            this.onFailure = transition;
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

        public Variant timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /** The reason {@code READY} gives an identifier that has no reason of its own. */
        public Variant policyReason(String reason) {
            this.policyReason = reason;
            return this;
        }

        /** The reason {@code READY} declares for {@code PREPARE}, which has no edge from there. */
        public Variant rejectReason(String reason) {
            this.rejectReason = reason;
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

        public GraphDefinition<String, State, Id> build() {
            TransitionBuilder<String, State, Id> edge = StateGraph.define(KEY).initial(State.NEW)
                    .terminal(State.DONE).terminal(State.FAILED)
                    .statePolicy(State.NEW, "prepare first")
                    .statePolicy(State.READY, policyReason)
                    .reject(State.READY, Id.PREPARE, rejectReason)
                    .transition(Id.PREPARE, State.NEW, State.READY).asyncAction(actionName, ACTION);
            edge = reversedGuards ? secondGuard(edge).guard("first", FIRST_GUARD)
                    : secondGuard(edge.guard("first", FIRST_GUARD));
            GraphBuilder<String, State, Id> graph = edge.timeout(timeout)
                    .onFailure(onFailure).failsOn(failsOn).afterCommit(hookName, HOOK)
                    .endTransition()
                    .transition(Id.RECOVER, State.NEW, State.FAILED).endTransition()
                    .transition(Id.FINISH, State.NEW, State.DONE).endTransition()
                    .transition(Id.FINISH, State.READY, State.DONE).endTransition()
                    .transition(Id.ABANDON, State.READY, State.FAILED).endTransition();
            return (watched ? watch(graph) : graph).build();
        }

        private TransitionBuilder<String, State, Id> secondGuard(TransitionBuilder<String, State, Id> edge) {
            return asyncSecondGuard ? edge.asyncGuard("second", ASYNC_GUARD) : edge.guard("second", SECOND_GUARD);
        }

        private GraphBuilder<String, State, Id> watch(GraphBuilder<String, State, Id> graph) {
            return sqlPeer
                    ? rules(graph, MachineKey.of(java.sql.Date.class, TestDomain.State.class, TestDomain.Id.class))
                    : rules(graph, MachineKey.of(java.util.Date.class, TestDomain.State.class, TestDomain.Id.class));
        }

        private <P> GraphBuilder<String, State, Id> rules(
                GraphBuilder<String, State, Id> graph, MachineKey<P, TestDomain.State, TestDomain.Id> peer) {
            WatchCondition<String, P, TestDomain.State, TestDomain.Id> condition =
                    (owner, peers, view) -> { throw new AssertionError("a watch condition ran during a capture"); };
            WatchBuilder<String, State, Id, P, TestDomain.State, TestDomain.Id> watch =
                    graph.watch(State.READY, peer, owner -> List.<P>of(), other -> Optional.of("owner"));
            if (reversedRules) {
                return watch.when("stalled", condition).execute(Id.ABANDON)
                        .when("ready", condition).execute(Id.FINISH).endWatch();
            }
            return watch.when("ready", condition).execute(Id.FINISH)
                    .when("stalled", condition).execute(Id.ABANDON).endWatch();
        }
    }
}
