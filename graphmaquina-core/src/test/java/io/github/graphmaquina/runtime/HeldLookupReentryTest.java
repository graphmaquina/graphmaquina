package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.error.ReentrantTransitionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * A nested check asks whether the call holds its entity while that entity's identity, compared with an entry of the
 * log, cascades the entity itself through the root action's retained context: the entry that cascade adds is compared
 * too, so the check is refused as held, whether the log is short or long.
 */
class HeldLookupReentryTest {

    /** States of a node. */
    enum Status { A, B }

    /** The one identifier of a node. */
    enum Step { GO }

    /** States of the parent. */
    enum ParentStatus { OPEN, DONE }

    /** The one identifier of the parent. */
    enum ParentStep { COMPLETE }

    /** An identity that, compared once with {@code trigger}, first runs {@code onCompare}. */
    static final class Key {
        private final int value;
        private Optional<Key> trigger = Optional.empty();
        private Optional<Runnable> onCompare = Optional.empty();

        Key(int value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object other) {
            if (onCompare.isPresent() && trigger.isPresent() && other == trigger.get()) {
                Runnable compare = onCompare.get();
                onCompare = Optional.empty();
                compare.run();
            }
            return other instanceof Key that && that.value == value;
        }

        @Override
        public int hashCode() {
            return value;
        }
    }

    /** A node with its identity. */
    static final class Node {
        final Key id;
        Status status = Status.A;

        Node(int value) {
            id = new Key(value);
        }
    }

    /** The parent, whose COMPLETE cascades GO to its nodes, then to {@code guarded}. */
    static final class Parent {
        final List<Node> nodes = new ArrayList<>();
        ParentStatus status = ParentStatus.OPEN;
    }

    static final MachineKey<Node, Status, Step> NODES = MachineKey.of(Node.class, Status.class, Step.class);
    static final MachineKey<Parent, ParentStatus, ParentStep> PARENTS =
            MachineKey.of(Parent.class, ParentStatus.class, ParentStep.class);
    /** What the rolled-back scenario records, as measured on the release before the held index. */
    private static final List<String> EXPECTED_ROLLED_BACK = List.of("z checks y: Eligible",
            "cascade threw CascadeException", "guarded checks x: Eligible", "call threw CascadeException", "x=A");

    @Test
    void anEntityThatItsIdentityCascadesDuringAShortLookupIsHeld() {
        assertEquals(List.of("HELD_CHECK", "x=B", "APPLIED"), run(4));
    }

    @Test
    void anEntityThatItsIdentityCascadesDuringALongLookupIsHeld() {
        assertEquals(List.of("HELD_CHECK", "x=B", "APPLIED"), run(40));
    }

    /**
     * The cascade that the identity starts fails once a step it cascades to has run a held lookup of its own over the
     * longer log: the failure rolls back what the identity's cascade added, the identity carries on, and the lookup
     * that called it compares the entries that remain.
     */
    @Test
    void aLookupGoesOnOverTheLogThatAFailedCascadeOfItsIdentityLeft() {
        assertEquals(EXPECTED_ROLLED_BACK, runRolledBack(40));
    }

    /**
     * The call of a parent of {@code size} nodes: its action keeps its context, cascades GO to each node, then to the
     * guarded node, whose guard checks the node {@code x}; {@code x}'s identity, compared with the fifth node's,
     * cascades {@code x} through the kept context. What the check answered, {@code x}'s state, the outcome's status.
     */
    private static List<String> run(int size) {
        Parent parent = new Parent();
        for (int value = 0; value < size; value++) {
            parent.nodes.add(new Node(value));
        }
        Node guarded = new Node(1000);
        Node x = new Node(2000);
        List<String> seen = new ArrayList<>();
        AtomicReference<TransitionContext<Parent, ParentStatus, ParentStep>> kept = new AtomicReference<>();
        AtomicReference<StateMachine<Node, Status, Step>> nodes = new AtomicReference<>();
        x.id.trigger = Optional.of(parent.nodes.get(3).id);
        x.id.onCompare = Optional.of(() -> kept.get().cascade(NODES, x, Step.GO));
        GraphDefinition<Node, Status, Step> nodeGraph = StateGraph.define(NODES).initial(Status.A)
                .terminal(Status.B).statePolicy(Status.A, "a node only goes")
                .transition(Step.GO, Status.A, Status.B)
                .guard("checks", (GuardContext<Node, Status, Step> ctx) -> {
                    if (ctx.entity() == guarded) {
                        seen.add(answer(nodes.get(), x));
                    }
                    return GuardResult.pass();
                })
                .endTransition().build();
        GraphDefinition<Parent, ParentStatus, ParentStep> parentGraph = StateGraph.define(PARENTS)
                .initial(ParentStatus.OPEN).terminal(ParentStatus.DONE)
                .statePolicy(ParentStatus.OPEN, "a parent only completes")
                .transition(ParentStep.COMPLETE, ParentStatus.OPEN, ParentStatus.DONE)
                .action("cascades", (TransitionContext<Parent, ParentStatus, ParentStep> ctx) -> {
                    kept.set(ctx);
                    for (Node node : ctx.entity().nodes) {
                        ctx.cascade(NODES, node, Step.GO);
                    }
                    ctx.cascade(NODES, guarded, Step.GO);
                    return ActionOutcome.ok();
                })
                .endTransition().build();
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(nodeGraph).state((Node n) -> n.status, (Node n, Status s) -> n.status = s)
                        .identity((Node n) -> n.id))
                .add(StateMachines.from(parentGraph)
                        .state((Parent p) -> p.status, (Parent p, ParentStatus s) -> p.status = s)
                        .identity((Parent p) -> "parent"))
                .build()) {
            nodes.set(registry.of(NODES));
            Outcome<Parent, ParentStatus, ParentStep> outcome = registry.of(PARENTS).execute(parent,
                    ParentStep.COMPLETE);
            seen.add("x=" + x.status);
            seen.add(outcome.status().name());
        }
        return seen;
    }

    /**
     * The call of a parent of {@code size} nodes whose action keeps its context, cascades GO to each node, then to the
     * guarded node, whose guard checks the node {@code x}. {@code x}'s identity, compared with the fourth node's,
     * cascades {@code x} through the kept context, as a COLLECT cascade, and catches what that throws; {@code x}'s action cascades the node
     * {@code z}, whose guard checks the node {@code y}, then throws. What happened, in order, then {@code x}'s state
     * and how the call ended.
     */
    private static List<String> runRolledBack(int size) {
        Parent parent = new Parent();
        for (int value = 0; value < size; value++) {
            parent.nodes.add(new Node(value));
        }
        Node guarded = new Node(1000);
        Node x = new Node(2000);
        List<String> seen = new ArrayList<>();
        AtomicReference<TransitionContext<Parent, ParentStatus, ParentStep>> kept = new AtomicReference<>();
        AtomicReference<StateMachine<Node, Status, Step>> nodes = new AtomicReference<>();
        x.id.trigger = Optional.of(parent.nodes.get(3).id);
        x.id.onCompare = Optional.of(() -> cascadeCatching(kept.get(), x, seen));
        try (MachineRegistry registry = registry(failing(guarded, x, nodes, seen), guarded, kept)) {
            nodes.set(registry.of(NODES));
            seen.add(ending(registry.of(PARENTS), parent));
            seen.add("x=" + x.status);
        }
        return seen;
    }

    /**
     * Cascades {@code x} through {@code kept}, as a COLLECT cascade of one, which rolls {@code x} back when it fails,
     * adding to {@code seen} that it did or what it threw.
     */
    private static void cascadeCatching(TransitionContext<Parent, ParentStatus, ParentStep> kept, Node x,
            List<String> seen) {
        try {
            kept.cascadeAll(NODES, List.of(x), Step.GO, CascadePolicy.COLLECT);
            seen.add("cascaded");
        } catch (RuntimeException failed) {
            seen.add("cascade threw " + failed.getClass().getSimpleName());
        }
    }

    /** How the call of {@code parent} ended: its outcome's status, or the failure it threw. */
    private static String ending(StateMachine<Parent, ParentStatus, ParentStep> parents, Parent parent) {
        try {
            return parents.execute(parent, ParentStep.COMPLETE).status().name();
        } catch (CallFailedException failed) {
            return "call threw " + failed.getClass().getSimpleName();
        }
    }

    /**
     * The node graph whose guard has {@code guarded} check {@code x}, and the node {@code z} check the node {@code y},
     * and whose action has {@code x} cascade {@code z}, then throw.
     */
    private static GraphDefinition<Node, Status, Step> failing(Node guarded, Node x,
            AtomicReference<StateMachine<Node, Status, Step>> nodes, List<String> seen) {
        Node z = new Node(3000);
        Node y = new Node(4000);
        return StateGraph.define(NODES).initial(Status.A)
                .terminal(Status.B).statePolicy(Status.A, "a node only goes")
                .transition(Step.GO, Status.A, Status.B)
                .guard("checks", (GuardContext<Node, Status, Step> ctx) -> {
                    if (ctx.entity() == guarded) {
                        seen.add("guarded checks x: " + answer(nodes.get(), x));
                    } else if (ctx.entity() == z) {
                        seen.add("z checks y: " + answer(nodes.get(), y));
                    }
                    return GuardResult.pass();
                })
                .action("acts", (TransitionContext<Node, Status, Step> ctx) -> {
                    if (ctx.entity() == x) {
                        ctx.cascade(NODES, z, Step.GO);
                        throw new IllegalStateException("x fails");
                    }
                    return ActionOutcome.ok();
                })
                .endTransition().build();
    }

    /** The registry of {@code nodeGraph} and of the parent, whose action keeps its context in {@code kept}. */
    private static MachineRegistry registry(GraphDefinition<Node, Status, Step> nodeGraph, Node guarded,
            AtomicReference<TransitionContext<Parent, ParentStatus, ParentStep>> kept) {
        GraphDefinition<Parent, ParentStatus, ParentStep> parentGraph = StateGraph.define(PARENTS)
                .initial(ParentStatus.OPEN).terminal(ParentStatus.DONE)
                .statePolicy(ParentStatus.OPEN, "a parent only completes")
                .transition(ParentStep.COMPLETE, ParentStatus.OPEN, ParentStatus.DONE)
                .action("cascades", (TransitionContext<Parent, ParentStatus, ParentStep> ctx) -> {
                    kept.set(ctx);
                    for (Node node : ctx.entity().nodes) {
                        ctx.cascade(NODES, node, Step.GO);
                    }
                    ctx.cascade(NODES, guarded, Step.GO);
                    return ActionOutcome.ok();
                })
                .endTransition().build();
        return MachineRegistry.builder()
                .add(StateMachines.from(nodeGraph).state((Node n) -> n.status, (Node n, Status s) -> n.status = s)
                        .identity((Node n) -> n.id))
                .add(StateMachines.from(parentGraph)
                        .state((Parent p) -> p.status, (Parent p, ParentStatus s) -> p.status = s)
                        .identity((Parent p) -> "parent"))
                .build();
    }

    /** The simple answer of a check of {@code node}: the eligibility's kind, or HELD_CHECK when it was refused so. */
    private static String answer(StateMachine<Node, Status, Step> machine, Node node) {
        try {
            return machine.eligibility(node, Step.GO).getClass().getSimpleName();
        } catch (ReentrantTransitionException refused) {
            return refused.getMessage().contains("of an entity that an enclosing call or check holds")
                    ? "HELD_CHECK" : refused.getMessage();
        }
    }
}
