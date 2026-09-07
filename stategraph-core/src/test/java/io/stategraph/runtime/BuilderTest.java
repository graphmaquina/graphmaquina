package io.stategraph.runtime;

import static io.stategraph.fixtures.Keys.DOCS;
import static io.stategraph.fixtures.Keys.ORDERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.stategraph.api.ActionOutcome;
import io.stategraph.api.GraphDefinition;
import io.stategraph.api.GuardContext;
import io.stategraph.api.GuardResult;
import io.stategraph.api.TransitionContext;
import io.stategraph.dsl.StateGraph;
import io.stategraph.error.UnknownPeerMachineException;
import io.stategraph.fixtures.Doc;
import io.stategraph.fixtures.DocStatus;
import io.stategraph.fixtures.Order;
import io.stategraph.fixtures.OrderGraphs;
import io.stategraph.fixtures.OrderStatus;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class BuilderTest {

    private static GraphDefinition<Doc, DocStatus> docs() {
        return StateGraph.define(DOCS)
                .initial(DocStatus.DRAFT).terminal(DocStatus.ARCHIVED)
                .from(DocStatus.DRAFT).to(DocStatus.REVIEW).move().stay().forbidOthers("x")
                .from(DocStatus.REVIEW).to(DocStatus.PUBLISHED).move().stay().forbidOthers("x")
                .from(DocStatus.PUBLISHED).to(DocStatus.ARCHIVED).move().stay().forbidOthers("x")
                .from(DocStatus.ARCHIVED).stay().forbidOthers("x")
                .build();
    }

    private static GraphDefinition<Order, OrderStatus> orders() {
        return OrderGraphs.order(
                (TransitionContext<Order, OrderStatus> ctx) -> CompletableFuture.completedFuture(ActionOutcome.ok()),
                (TransitionContext<Order, OrderStatus> ctx) -> ActionOutcome.ok(),
                (TransitionContext<Order, OrderStatus> ctx) -> ActionOutcome.ok(),
                (GuardContext<Order, OrderStatus> ctx) -> GuardResult.pass(),
                (GuardContext<Order, OrderStatus> ctx) -> GuardResult.pass());
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
        StateMachines.Builder<Doc, DocStatus> builder = StateMachines.from(docs()).state(Doc::status, Doc::setStatus);
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
