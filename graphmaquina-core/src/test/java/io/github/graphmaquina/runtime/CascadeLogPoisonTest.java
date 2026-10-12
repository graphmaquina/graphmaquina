package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.fixtures.LibraryLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A fatal TRACE backend cannot make a failed cascade safe to commit when the action catches that Error. */
class CascadeLogPoisonTest {

    /** The states of a node. */
    enum State { A, B }

    /** The one request. */
    enum Step { GO }

    /** A node with children to cascade to and an optional refusal expressed by {@code blocked}. */
    static final class Node {
        private final String name;
        private final boolean blocked;
        private final List<Node> children;
        private State state = State.A;

        Node(String name, boolean blocked, List<Node> children) {
            this.name = Objects.requireNonNull(name, "name");
            this.blocked = blocked;
            this.children = Objects.requireNonNull(children, "children");
        }
    }

    /** An entity whose machine is absent from the registry. */
    static final class Missing { }

    /** A handler that throws the supplied Error at one selected line. */
    static final class BreakingHandler extends Handler {
        private final String target;
        private final AssertionError fatal;

        BreakingHandler(String target, AssertionError fatal) {
            this.target = Objects.requireNonNull(target, "target");
            this.fatal = Objects.requireNonNull(fatal, "fatal");
        }

        @Override public void publish(LogRecord record) {
            if (record.getMessage().contains(target)) {
                throw fatal;
            }
        }

        @Override public void flush() { }
        @Override public void close() { }
    }

    @ParameterizedTest
    @ValueSource(strings = {"collect-rollback", "aggregate", "cycle", "unknown", "empty-unknown"})
    void catchingAnErrorOfTheCascadeLogStillPreventsCommit(String scenario) {
        MachineKey<Node, State, Step> key = MachineKey.of(Node.class, State.class, Step.class);
        MachineKey<Missing, State, Step> missing = MachineKey.of(Missing.class, State.class, Step.class);
        Node first = new Node("first", false, List.of());
        Node refused = new Node("refused", true, List.of());
        Node last = new Node("last", false, List.of());
        Node root = new Node("root", false, List.of(first, refused, last));
        AssertionError fatal = new AssertionError("the TRACE backend failed");
        List<Error> caught = new ArrayList<>();
        GraphDefinition<Node, State, Step> graph = StateGraph.define(key).initial(State.A).terminal(State.B)
                .statePolicy(State.A, "GO leaves A")
                .transition(Step.GO, State.A, State.B)
                .guard("allowed", (GuardContext<Node, State, Step> ctx) -> ctx.entity().blocked
                        ? GuardResult.reject("blocked") : GuardResult.pass())
                .action("cascade", (TransitionContext<Node, State, Step> ctx) -> {
                    if (ctx.entity() == root) {
                        try {
                            switch (scenario) {
                                case "cycle" -> ctx.cascade(key, root, Step.GO);
                                case "unknown" -> ctx.cascade(missing, new Missing(), Step.GO);
                                case "empty-unknown" ->
                                        ctx.cascadeAll(missing, List.of(), Step.GO, CascadePolicy.COLLECT);
                                default -> ctx.cascadeAll(key, root.children, Step.GO, CascadePolicy.COLLECT);
                            }
                        } catch (Error thrown) {
                            caught.add(thrown);
                        }
                    }
                    return ActionOutcome.ok();
                }).endTransition().build();
        String line = scenario.equals("collect-rollback") ? "collect-rollback" : "depth=0 poison error=";
        BreakingHandler handler = new BreakingHandler(line, fatal);
        Logger logger = Logger.getLogger("io.github.graphmaquina.runtime");
        try (LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            logger.addHandler(handler);
            try {
                StateMachine<Node, State, Step> machine = StateMachines.from(graph)
                        .state((Node node) -> node.state, (Node node, State state) -> node.state = state)
                        .identity((Node node) -> node.name).recordTrail().build();
                CallFailedException failure = assertThrows(CallFailedException.class,
                        () -> machine.execute(root, Step.GO));
                assertEquals(StatesWritten.NONE, failure.statesWritten());
                assertEquals(List.of(State.A, State.A, State.A, State.A),
                        List.of(root.state, first.state, refused.state, last.state));
                assertEquals(1, caught.size());
                assertSame(fatal, caught.getFirst());
                assertTrue(log.records().stream().anyMatch((LogRecord record) -> record.getMessage().contains(line)));
            } finally {
                logger.removeHandler(handler);
            }
        }
    }
}
