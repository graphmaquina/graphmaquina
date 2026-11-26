package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.Eligibility;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Which entities the checks that cascaded steps' guards start find held, and how often that lookup runs the identities'
 * equals and hashCode: a check of an entity of another machine, of a sibling the cascade has not reached, and of a
 * sibling it already moved, in a cascade that commits and in a COLLECT cascade that rolls back a failed element, after
 * which that element is no longer held.
 */
class HeldEntityLookupTest {

    /** States of the child and of the peer machine. */
    enum Status { A, B }

    /** The one identifier of the child and of the peer machine. */
    enum Step { GO }

    /** States of the parent machine. */
    enum ParentStatus { OPEN, DONE }

    /** The one identifier of the parent machine. */
    enum ParentStep { COMPLETE }

    /** Counts the calls of the equals and hashCode of the identities that share it. */
    static final class Calls {
        int equals;
        int hashCode;
    }

    /** An identity whose equals and hashCode count their calls. */
    static final class CountedId {
        private final int value;
        private final Calls calls;

        CountedId(int value, Calls calls) {
            this.value = value;
            this.calls = calls;
        }

        @Override
        public boolean equals(Object other) {
            calls.equals++;
            return other instanceof CountedId that && that.value == value;
        }

        @Override
        public int hashCode() {
            calls.hashCode++;
            return value;
        }
    }

    /** A child, or a peer: an identity and a state. */
    static class Node {
        final CountedId id;
        Status status = Status.A;

        Node(int value, Calls calls) {
            id = new CountedId(value, calls);
        }
    }

    /** The parent, which cascades GO to its children in order. */
    static final class Parent {
        final List<Node> children = new ArrayList<>();
        ParentStatus status = ParentStatus.OPEN;
    }

    /** Child entities, typed apart from the peers so that the two machines have distinct keys. */
    static final class Child extends Node {
        Child(int value, Calls calls) {
            super(value, calls);
        }
    }

    /** Peer entities. */
    static final class PeerNode extends Node {
        PeerNode(int value, Calls calls) {
            super(value, calls);
        }
    }

    static final MachineKey<Child, Status, Step> CHILDREN = MachineKey.of(Child.class, Status.class, Step.class);
    static final MachineKey<PeerNode, Status, Step> PEERS = MachineKey.of(PeerNode.class, Status.class, Step.class);
    static final MachineKey<Parent, ParentStatus, ParentStep> PARENTS =
            MachineKey.of(Parent.class, ParentStatus.class, ParentStep.class);
    /** How often the children's identities ran equals and hashCode in the committing cascade, as measured. */
    private static final int EQUALS_COMMIT = 19;
    private static final int HASH_COMMIT = 27;
    /** The same counts in the COLLECT cascade that rolled its second element back. */
    private static final int EQUALS_COLLECT = 16;
    private static final int HASH_COLLECT = 22;
    /** The peer's identity is hashed by its four checks and never compared. */
    private static final int PEER_HASH = 4;
    /** The children of the long cascades: their log outgrows a short scan. */
    private static final int LONG = 40;
    /** The child that the long COLLECT cascade rolls back, once the log outgrew a short scan. */
    private static final int LONG_THROWING = 35;
    /** How often the children's identities ran equals and hashCode in the long cascades, as measured. */
    private static final int EQUALS_LONG_COMMIT = 1639;
    private static final int HASH_LONG_COMMIT = 279;
    private static final int EQUALS_LONG_COLLECT = 1632;
    private static final int HASH_LONG_COLLECT = 202;

    private final Calls childCalls = new Calls();
    private final Calls peerCalls = new Calls();
    private final List<String> seen = new ArrayList<>();
    /** Whether a guard of the cascade runs its checks, so that the guards those checks run check nothing. */
    private boolean checking;

    @Test
    void checksOfACommittingCascadeFindTheMovedSiblingsHeld() {
        Fixture fixture = new Fixture(4, CascadePolicy.FAIL_FAST, -1);
        Outcome<Parent, ParentStatus, ParentStep> outcome = fixture.run();
        assertInstanceOf(Outcome.Applied.class, outcome);
        assertEquals(List.of(
                "c0 peer=Eligible next=Eligible",
                "c1 peer=Eligible previous=HELD_CHECK next=Eligible",
                "c2 peer=Eligible previous=HELD_CHECK next=Eligible",
                "c3 peer=Eligible previous=HELD_CHECK"), seen);
        assertEquals(List.of(EQUALS_COMMIT, HASH_COMMIT, 0, PEER_HASH),
                List.of(childCalls.equals, childCalls.hashCode, peerCalls.equals, peerCalls.hashCode));
    }

    @Test
    void aSiblingThatACollectCascadeRolledBackIsNoLongerHeld() {
        Fixture fixture = new Fixture(4, CascadePolicy.COLLECT, 1);
        assertThrows(CallFailedException.class, fixture::run);
        assertEquals(List.of(
                "c0 peer=Eligible next=Eligible",
                "c1 peer=Eligible previous=HELD_CHECK next=Eligible",
                "c2 peer=Eligible previous=Eligible next=Eligible",
                "c3 peer=Eligible previous=HELD_CHECK"), seen);
        assertEquals(List.of(EQUALS_COLLECT, HASH_COLLECT, 0, PEER_HASH),
                List.of(childCalls.equals, childCalls.hashCode, peerCalls.equals, peerCalls.hashCode));
    }

    /** A cascade long enough that the log outgrows a short scan before its last checks. */
    @Test
    void checksOfALongCommittingCascadeFindTheMovedSiblingsHeld() {
        Fixture fixture = new Fixture(LONG, CascadePolicy.FAIL_FAST, -1);
        assertInstanceOf(Outcome.Applied.class, fixture.run());
        assertEquals(expectedLines(LONG, -1), seen);
        assertEquals(List.of(EQUALS_LONG_COMMIT, HASH_LONG_COMMIT, 0, LONG),
                List.of(childCalls.equals, childCalls.hashCode, peerCalls.equals, peerCalls.hashCode));
    }

    /** The rolled-back element is past the point where the log outgrew a short scan. */
    @Test
    void aSiblingThatALongCollectCascadeRolledBackIsNoLongerHeld() {
        Fixture fixture = new Fixture(LONG, CascadePolicy.COLLECT, LONG_THROWING);
        assertThrows(CallFailedException.class, fixture::run);
        assertEquals(expectedLines(LONG, LONG_THROWING), seen);
        assertEquals(List.of(EQUALS_LONG_COLLECT, HASH_LONG_COLLECT, 0, LONG),
                List.of(childCalls.equals, childCalls.hashCode, peerCalls.equals, peerCalls.hashCode));
    }

    /**
     * The line of each of {@code size} children: the peer is eligible, the next child too, the previous one held,
     * except the child after {@code rolledBack}, whose previous sibling was rolled back and is eligible again.
     */
    private static List<String> expectedLines(int size, int rolledBack) {
        List<String> lines = new ArrayList<>();
        for (int at = 0; at < size; at++) {
            StringBuilder line = new StringBuilder("c" + at + " peer=Eligible");
            if (at > 0) {
                line.append(" previous=").append(at - 1 == rolledBack ? "Eligible" : "HELD_CHECK");
            }
            if (at < size - 1) {
                line.append(" next=Eligible");
            }
            lines.add(line.toString());
        }
        return lines;
    }

    /** Children, a peer, and a parent whose COMPLETE cascades GO to the children; one child may throw. */
    private final class Fixture {
        private final Parent parent = new Parent();
        private final PeerNode peer = new PeerNode(100, peerCalls);
        private final StateMachine<Parent, ParentStatus, ParentStep> parents;

        Fixture(int size, CascadePolicy policy, int throwing) {
            for (int value = 0; value < size; value++) {
                parent.children.add(new Child(value, childCalls));
            }
            AtomicReference<MachineRegistry> registry = new AtomicReference<>();
            GraphDefinition<Child, Status, Step> children = childGraph(registry, throwing);
            GraphDefinition<PeerNode, Status, Step> peers = StateGraph.define(PEERS).initial(Status.A)
                    .terminal(Status.B).statePolicy(Status.A, "a peer only goes")
                    .transition(Step.GO, Status.A, Status.B).endTransition().build();
            GraphDefinition<Parent, ParentStatus, ParentStep> parentGraph = StateGraph.define(PARENTS)
                    .initial(ParentStatus.OPEN).terminal(ParentStatus.DONE)
                    .statePolicy(ParentStatus.OPEN, "a parent only completes")
                    .transition(ParentStep.COMPLETE, ParentStatus.OPEN, ParentStatus.DONE)
                    .action("cascades", (TransitionContext<Parent, ParentStatus, ParentStep> ctx) -> {
                        ctx.cascadeAll(CHILDREN, List.copyOf(ctx.entity().children.stream()
                                .map((Node node) -> (Child) node).toList()), Step.GO, policy);
                        return ActionOutcome.ok();
                    })
                    .endTransition().build();
            registry.set(MachineRegistry.builder()
                    .add(StateMachines.from(children).state((Child c) -> c.status, (Child c, Status s) -> c.status = s)
                            .identity((Child c) -> c.id))
                    .add(StateMachines.from(peers)
                            .state((PeerNode p) -> p.status, (PeerNode p, Status s) -> p.status = s)
                            .identity((PeerNode p) -> p.id))
                    .add(StateMachines.from(parentGraph)
                            .state((Parent p) -> p.status, (Parent p, ParentStatus s) -> p.status = s)
                            .identity((Parent p) -> "parent"))
                    .build());
            parents = registry.get().of(PARENTS);
        }

        /** The child graph, whose GO guard runs {@link #checks} once per step and whose action may throw. */
        private GraphDefinition<Child, Status, Step> childGraph(AtomicReference<MachineRegistry> registry,
                                                                int throwing) {
            return StateGraph.define(CHILDREN).initial(Status.A)
                    .terminal(Status.B).statePolicy(Status.A, "a child only goes")
                    .transition(Step.GO, Status.A, Status.B)
                    .guard("checks", (GuardContext<Child, Status, Step> ctx) -> {
                        if (!checking) {
                            checking = true;
                            try {
                                seen.add(checks(registry.get(), ctx.entity()));
                            } finally {
                                checking = false;
                            }
                        }
                        return GuardResult.pass();
                    })
                    .action("maybeThrows", (TransitionContext<Child, Status, Step> ctx) -> {
                        if (ctx.entity().id.value == throwing) {
                            throw new IllegalStateException("child " + throwing + " fails");
                        }
                        return ActionOutcome.ok();
                    })
                    .endTransition().build();
        }

        Outcome<Parent, ParentStatus, ParentStep> run() {
            return parents.execute(parent, ParentStep.COMPLETE);
        }

        /** What the checks of the peer, of the previous child and of the next child answer from {@code child}'s guard. */
        private String checks(MachineRegistry registry, Child child) {
            int at = child.id.value;
            StringBuilder line = new StringBuilder("c" + at);
            line.append(" peer=").append(answer(registry.of(PEERS), peer));
            if (at > 0) {
                line.append(" previous=").append(answer(registry.of(CHILDREN), (Child) parent.children.get(at - 1)));
            }
            if (at < parent.children.size() - 1) {
                line.append(" next=").append(answer(registry.of(CHILDREN), (Child) parent.children.get(at + 1)));
            }
            return line.toString();
        }
    }

    /** The simple answer of a check of {@code entity}: the eligibility's kind, or the refusal that stopped it. */
    private static <E> String answer(StateMachine<E, Status, Step> machine, E entity) {
        try {
            Eligibility<E, Status, Step> eligibility = machine.eligibility(entity, Step.GO);
            return eligibility.getClass().getSimpleName();
        } catch (ReentrantTransitionException refused) {
            return refused.getMessage().contains("of an entity that an enclosing call or check holds")
                    ? "HELD_CHECK" : refused.getMessage();
        }
    }
}
