package io.github.graphmaquina.dsl;

import static io.github.graphmaquina.fixtures.Keys.ORDERS;
import static io.github.graphmaquina.fixtures.OrderStatus.CANCELLED;
import static io.github.graphmaquina.fixtures.OrderStatus.NEW;
import static io.github.graphmaquina.fixtures.OrderStatus.PAID;
import static io.github.graphmaquina.fixtures.OrderStatus.PAYMENT_DECLINED;
import static io.github.graphmaquina.fixtures.OrderStatus.SHIPPED;
import static io.github.graphmaquina.fixtures.OrderTransition.CANCEL;
import static io.github.graphmaquina.fixtures.OrderTransition.CHARGE;
import static io.github.graphmaquina.fixtures.OrderTransition.DECLINE;
import static io.github.graphmaquina.fixtures.OrderTransition.RETRY;
import static io.github.graphmaquina.fixtures.OrderTransition.SHIP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.fixtures.Order;
import io.github.graphmaquina.fixtures.OrderStatus;
import io.github.graphmaquina.fixtures.OrderTransition;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SG807: no automatic edge leaves a state that holds a sub-machine, over the order machine whose NEW holds a check
 * of the order itself, with CHARGE and CANCEL as its exits.
 */
class AutomaticSubmachineValidationTest {

    /** The edge NEW takes by {@code transition} to {@code to}, automatic when it is the {@code automatic} one. */
    private static GraphBuilder<Order, OrderStatus, OrderTransition> fromNew(
            GraphBuilder<Order, OrderStatus, OrderTransition> graph, OrderTransition transition, OrderStatus to,
            OrderTransition automatic) {
        TransitionBuilder<Order, OrderStatus, OrderTransition> edge = graph.transition(transition, NEW, to);
        return (transition == automatic ? edge.automatic() : edge).endTransition();
    }

    /**
     * The order machine, with NEW bound to a check of the order whose exits are CHARGE and CANCEL, and the edge of
     * {@code automatic} leaving NEW marked as automatic.
     */
    private static GraphBuilder<Order, OrderStatus, OrderTransition> orders(OrderTransition automatic) {
        GraphBuilder<Order, OrderStatus, OrderTransition> graph = StateGraph.define(ORDERS)
                .initial(NEW).terminal(SHIPPED).terminal(CANCELLED).allowsCycle(PAYMENT_DECLINED)
                .statePolicy(NEW, "an order is charged, declined or cancelled")
                .statePolicy(PAID, "a paid order ships")
                .statePolicy(PAYMENT_DECLINED, "a declined order is retried or cancelled");
        graph = fromNew(fromNew(fromNew(graph, CHARGE, PAID, automatic), DECLINE, PAYMENT_DECLINED, automatic),
                CANCEL, CANCELLED, automatic);
        graph.transition(SHIP, PAID, SHIPPED).endTransition()
                .transition(RETRY, PAYMENT_DECLINED, NEW).endTransition()
                .transition(CANCEL, PAYMENT_DECLINED, CANCELLED).endTransition();
        graph.submachine(NEW, SubmachineDslTest.checks())
                .exit(SubmachineDslTest.CheckStatus.CLEARED, CHARGE)
                .exit(SubmachineDslTest.CheckStatus.FLAGGED, CANCEL)
                .endSubmachine();
        return graph;
    }

    private static List<InvalidGraphException.Problem> problems(
            GraphBuilder<Order, OrderStatus, OrderTransition> graph) {
        return assertThrows(InvalidGraphException.class, graph::build).problems();
    }

    @Test
    void sg807AutomaticExitOfTheBoundSubmachine() {
        assertEquals(List.of(new InvalidGraphException.Problem(DiagnosticCode.SG807.name(),
                "the automatic transition CHARGE leaves NEW, which holds a sub-machine")), problems(orders(CHARGE)));
    }

    @Test
    void sg807AutomaticNonExitFromABoundState() {
        assertEquals(List.of(new InvalidGraphException.Problem(DiagnosticCode.SG807.name(),
                "the automatic transition DECLINE leaves NEW, which holds a sub-machine")), problems(orders(DECLINE)));
    }

    @Test
    void anAutomaticEdgeFromAStateWithoutASubmachineIsFine() {
        GraphBuilder<Order, OrderStatus, OrderTransition> graph = orders(SHIP);
        assertEquals(List.of(), graph.build().automaticFrom(NEW));
    }
}
