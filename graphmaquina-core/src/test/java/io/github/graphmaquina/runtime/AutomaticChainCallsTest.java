package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.AutomaticStep;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What an advance, a call whose commit starts an automatic chain, a cascade whose children each start one, and a plain
 * call in a registry with automatic edges answer, and how often each runs the equals and hashCode of the identities
 * involved: the chains read the commit and the post-commit scope, which compare identities as the commit log does.
 */
class AutomaticChainCallsTest {

    /** States of the entity: A advances to B on its own; B goes on to C on its own in the chained machine. */
    enum Status { A, B, C }

    /** Identifiers of the entity's machine. */
    enum Step { GO, AUTO }

    /** States of the parent. */
    enum ParentStatus { OPEN, DONE }

    /** The one identifier of the parent. */
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

    /** An entity with a counted identity. */
    static final class Item {
        final CountedId id;
        Status status = Status.A;

        Item(int value, Calls calls) {
            id = new CountedId(value, calls);
        }
    }

    /** A parent of items, which cascades GO to them. */
    static final class Parent {
        final List<Item> items = new ArrayList<>();
        ParentStatus status = ParentStatus.OPEN;
    }

    static final MachineKey<Item, Status, Step> ITEMS = MachineKey.of(Item.class, Status.class, Step.class);
    static final MachineKey<Parent, ParentStatus, ParentStep> PARENTS =
            MachineKey.of(Parent.class, ParentStatus.class, ParentStep.class);

    private final Calls calls = new Calls();

    @Test
    void anAdvanceTakesTheAutomaticEdge() {
        Item item = new Item(1, calls);
        try (MachineRegistry registry = registry(advancing())) {
            Outcome<Item, Status, Step> outcome = registry.of(ITEMS).advance(item);
            assertInstanceOf(Outcome.Applied.class, outcome);
            assertEquals(Status.B, item.status);
            assertEquals(List.of(), outcome.automaticSteps());
        }
        assertEquals(List.of(0, 2), List.of(calls.equals, calls.hashCode));
    }

    @Test
    void aCommitStartsTheChainOfItsEntity() {
        Item item = new Item(1, calls);
        try (MachineRegistry registry = registry(chained())) {
            Outcome<Item, Status, Step> outcome = registry.of(ITEMS).execute(item, Step.GO);
            assertEquals(Status.C, item.status);
            AutomaticStep.Done step = assertInstanceOf(AutomaticStep.Done.class, outcome.automaticSteps().get(0));
            assertInstanceOf(Outcome.Applied.class, step.outcome());
            assertEquals(1, outcome.automaticSteps().size());
        }
        assertEquals(List.of(0, 4), List.of(calls.equals, calls.hashCode));
    }

    @Test
    void eachItemThatACascadeMovedStartsItsChain() {
        Parent parent = new Parent();
        for (int value = 0; value < 3; value++) {
            parent.items.add(new Item(value, calls));
        }
        try (MachineRegistry registry = registry(chained())) {
            Outcome<Parent, ParentStatus, ParentStep> outcome = registry.of(PARENTS).execute(parent,
                    ParentStep.COMPLETE);
            assertEquals(3, outcome.automaticSteps().size());
            for (AutomaticStep step : outcome.automaticSteps()) {
                assertInstanceOf(Outcome.Applied.class, assertInstanceOf(AutomaticStep.Done.class, step).outcome());
            }
        }
        for (Item item : parent.items) {
            assertEquals(Status.C, item.status);
        }
        assertEquals(List.of(3, 28), List.of(calls.equals, calls.hashCode));
    }

    @Test
    void aPlainCallOfAMachineWithoutAutomaticEdgesStartsNoChain() {
        Parent parent = new Parent();
        try (MachineRegistry registry = registry(chained())) {
            Outcome<Parent, ParentStatus, ParentStep> outcome = registry.of(PARENTS).execute(parent,
                    ParentStep.COMPLETE);
            assertInstanceOf(Outcome.Applied.class, outcome);
            assertEquals(List.of(), outcome.automaticSteps());
        }
        assertEquals(ParentStatus.DONE, parent.status);
    }

    /** The items' graph in which A advances to B on its own, and B goes on to C only when asked. */
    private static GraphDefinition<Item, Status, Step> advancing() {
        return StateGraph.define(ITEMS).initial(Status.A).terminal(Status.C)
                .statePolicy(Status.A, "a advances").statePolicy(Status.B, "b goes on")
                .transition(Step.AUTO, Status.A, Status.B).automatic().endTransition()
                .transition(Step.GO, Status.B, Status.C).endTransition().build();
    }

    /** The items' graph in which A goes to B when asked, and B goes on to C on its own. */
    private static GraphDefinition<Item, Status, Step> chained() {
        return StateGraph.define(ITEMS).initial(Status.A).terminal(Status.C)
                .statePolicy(Status.A, "a goes").statePolicy(Status.B, "b advances")
                .transition(Step.GO, Status.A, Status.B).endTransition()
                .transition(Step.AUTO, Status.B, Status.C).automatic().endTransition().build();
    }

    /** A registry of the items' machine of {@code items}, and of the parent, whose COMPLETE cascades GO to them. */
    private static MachineRegistry registry(GraphDefinition<Item, Status, Step> items) {
        GraphDefinition<Parent, ParentStatus, ParentStep> parents = StateGraph.define(PARENTS)
                .initial(ParentStatus.OPEN).terminal(ParentStatus.DONE)
                .statePolicy(ParentStatus.OPEN, "a parent completes")
                .transition(ParentStep.COMPLETE, ParentStatus.OPEN, ParentStatus.DONE)
                .action("cascades", (TransitionContext<Parent, ParentStatus, ParentStep> ctx) -> {
                    ctx.cascadeAll(ITEMS, ctx.entity().items, Step.GO, CascadePolicy.FAIL_FAST);
                    return ActionOutcome.ok();
                })
                .endTransition().build();
        return MachineRegistry.builder()
                .add(StateMachines.from(items).state((Item i) -> i.status, (Item i, Status s) -> i.status = s)
                        .identity((Item i) -> i.id))
                .add(StateMachines.from(parents)
                        .state((Parent p) -> p.status, (Parent p, ParentStatus s) -> p.status = s)
                        .identity((Parent p) -> "parent"))
                .build();
    }
}
