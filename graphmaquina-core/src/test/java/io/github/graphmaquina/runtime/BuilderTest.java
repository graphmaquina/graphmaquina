package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static io.github.graphmaquina.fixtures.Keys.ORDERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.UnknownPeerMachineException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.Order;
import io.github.graphmaquina.fixtures.OrderGraphs;
import io.github.graphmaquina.fixtures.OrderStatus;
import io.github.graphmaquina.fixtures.OrderTransition;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * The builder rules of {@link StateMachines} and {@link MachineRegistry}: mandatory state accessors and asyncTimeout,
 * single use, identity and conflicting machines within a registry, and key lookup.
 */
class BuilderTest {

    /** Another catalogue over the same entity and states: the registry must not let two engines write it. */
    enum RivalTransition { GO }

    private static GraphDefinition<Doc, DocStatus, DocTransition> docs() {
        return StateGraph.define(DOCS)
                .initial(DocStatus.DRAFT).terminal(DocStatus.ARCHIVED)
                .statePolicy(DocStatus.DRAFT, "x").statePolicy(DocStatus.REVIEW, "x")
                .statePolicy(DocStatus.PUBLISHED, "x")
                .transition(DocTransition.SUBMIT, DocStatus.DRAFT, DocStatus.REVIEW).endTransition()
                .transition(DocTransition.PUBLISH, DocStatus.REVIEW, DocStatus.PUBLISHED).endTransition()
                .transition(DocTransition.ARCHIVE, DocStatus.PUBLISHED, DocStatus.ARCHIVED).endTransition()
                .build();
    }

    private static GraphDefinition<Order, OrderStatus, OrderTransition> orders() {
        return OrderGraphs.orderWithoutWatch(
                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                        CompletableFuture.completedFuture(ActionOutcome.ok()),
                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> ActionOutcome.ok(),
                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> ActionOutcome.ok(),
                (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass(),
                (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass());
    }

    @Test
    void stateAccessorsAreMandatory() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> StateMachines.from(docs()).build());
        assertTrue(error.getMessage().contains("state(getter, setter)"));
    }

    @Test
    void asyncTimeoutIsMandatoryWhenTheGraphIsAsync() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> StateMachines.from(orders()).state(Order::status, Order::setStatus).build());
        assertTrue(error.getMessage().contains("asyncTimeout"));
        assertNotNull(StateMachines.from(orders()).state(Order::status, Order::setStatus)
                .asyncTimeout(Duration.ofSeconds(1)).build());
        assertThrows(IllegalArgumentException.class, () -> StateMachines.from(orders()).asyncTimeout(Duration.ZERO));
    }

    @Test
    void builderIsSingleUse() {
        StateMachines.Builder<Doc, DocStatus, DocTransition> builder =
                StateMachines.from(docs()).state(Doc::status, Doc::setStatus);
        builder.build();
        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void registryRequiresIdentityAndRejectsDuplicates() {
        IllegalStateException noIdentity = assertThrows(IllegalStateException.class, () -> MachineRegistry.builder()
                .add(StateMachines.from(docs()).state(Doc::status, Doc::setStatus))
                .build());
        assertTrue(noIdentity.getMessage().contains("identity"));
        assertThrows(IllegalStateException.class, () -> MachineRegistry.builder()
                .add(StateMachines.from(docs()).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .add(StateMachines.from(docs()).state(Doc::status, Doc::setStatus).identity(Doc::id)));
        assertThrows(IllegalArgumentException.class, () -> MachineRegistry.builder().maxCascadeDepth(0));
    }

    /** Two catalogues over the same entity and state enum would race for the same setter. */
    @Test
    void registryRejectsTwoCataloguesOverTheSameState() {
        MachineKey<Doc, DocStatus, RivalTransition> rival =
                MachineKey.of(Doc.class, DocStatus.class, RivalTransition.class);
        GraphDefinition<Doc, DocStatus, RivalTransition> other = StateGraph.define(rival)
                .initial(DocStatus.DRAFT).terminal(DocStatus.ARCHIVED)
                .statePolicy(DocStatus.DRAFT, "x").statePolicy(DocStatus.REVIEW, "x")
                .statePolicy(DocStatus.PUBLISHED, "x")
                .transition(RivalTransition.GO, DocStatus.DRAFT, DocStatus.REVIEW).endTransition()
                .transition(RivalTransition.GO, DocStatus.REVIEW, DocStatus.PUBLISHED).endTransition()
                .transition(RivalTransition.GO, DocStatus.PUBLISHED, DocStatus.ARCHIVED).endTransition()
                .build();
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> MachineRegistry.builder()
                .add(StateMachines.from(docs()).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .add(StateMachines.from(other).state(Doc::status, Doc::setStatus).identity(Doc::id)));
        assertTrue(error.getMessage().contains("different transition catalogue"));
    }

    @Test
    void registryResolvesKeysAndRejectsUnknownOnes() {
        MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(docs()).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .build();
        assertEquals(DOCS, registry.of(DOCS).key());
        assertEquals(Set.of(DOCS), registry.keys());
        UnknownPeerMachineException error = assertThrows(UnknownPeerMachineException.class, () -> registry.of(ORDERS));
        assertEquals(ORDERS, error.requested());
        assertEquals(Set.of(DOCS), error.registered());
        registry.close();
    }
}
