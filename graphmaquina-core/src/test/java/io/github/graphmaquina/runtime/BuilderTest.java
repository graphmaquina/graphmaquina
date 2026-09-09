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
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.UnknownPeerMachineException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.Order;
import io.github.graphmaquina.fixtures.OrderGraphs;
import io.github.graphmaquina.fixtures.OrderStatus;
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
