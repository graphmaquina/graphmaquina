package io.github.graphmaquina.example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.example.billing.BillingLookups;
import io.github.graphmaquina.example.billing.Booking;
import io.github.graphmaquina.example.billing.BookingActions;
import io.github.graphmaquina.example.billing.BookingStatus;
import io.github.graphmaquina.example.billing.BookingTransition;
import io.github.graphmaquina.example.billing.BookingsRuntime;
import io.github.graphmaquina.example.billing.PaymentsRuntime;
import io.github.graphmaquina.example.billing.RoomDesk;
import io.github.graphmaquina.export.GraphView;
import io.github.graphmaquina.export.GraphvizExporter;
import io.github.graphmaquina.example.item.Item;
import io.github.graphmaquina.example.item.ItemActions;
import io.github.graphmaquina.example.item.ItemStatus;
import io.github.graphmaquina.example.item.ItemTransition;
import io.github.graphmaquina.example.item.ItemsRuntime;
import io.github.graphmaquina.example.onboarding.Account;
import io.github.graphmaquina.example.onboarding.AccountActions;
import io.github.graphmaquina.example.onboarding.AccountStatus;
import io.github.graphmaquina.example.onboarding.AccountTransition;
import io.github.graphmaquina.example.onboarding.AccountsRuntime;
import io.github.graphmaquina.example.order.Order;
import io.github.graphmaquina.example.order.OrderActions;
import io.github.graphmaquina.example.order.OrderStatus;
import io.github.graphmaquina.example.order.OrderTransition;
import io.github.graphmaquina.example.order.OrdersRuntime;
import io.github.graphmaquina.test.GraphGoldens;
import org.junit.jupiter.api.Test;

/**
 * Visual DOT output only; GraphContractsTest and OrderFlowTest verify the machines' contracts and behavior. The
 * definition each companion's {@code structure()} builds, with no collaborator, draws the same goldens.
 */
class DiagramsTest {

    private static GraphDefinition<Order, OrderStatus, OrderTransition> orderGraph() {
        return OrdersRuntime.graph(new OrderActions(new FakeGateway(), new FakeCarrier()));
    }

    private static GraphDefinition<Item, ItemStatus, ItemTransition> itemGraph() {
        return ItemsRuntime.graph(new ItemActions());
    }

    /** The onboarding's graph, with a verifier that verifies every address: a diagram never runs it. */
    private static GraphDefinition<Account, AccountStatus, AccountTransition> accountGraph() {
        return AccountsRuntime.graph(new AccountActions((Account account) -> true));
    }

    @Test
    void orderDiagramMatchesGolden() {
        GraphGoldens.assertMatches(orderGraph(), "golden/order.dot");
    }

    @Test
    void itemDiagramMatchesGolden() {
        GraphGoldens.assertMatches(itemGraph(), "golden/item.dot");
    }

    @Test
    void accountDiagramMatchesGolden() {
        GraphGoldens.assertMatches(accountGraph(), "golden/account.dot");
    }

    @Test
    void orderStructureMatchesGolden() {
        GraphGoldens.assertMatches(OrdersRuntime.structure(), "golden/order.dot");
    }

    @Test
    void itemStructureMatchesGolden() {
        GraphGoldens.assertMatches(ItemsRuntime.structure(), "golden/item.dot");
    }

    @Test
    void accountStructureMatchesGolden() {
        GraphGoldens.assertMatches(AccountsRuntime.structure(), "golden/account.dot");
    }

    /**
     * The booking binds the payment machine twice: its structure reads that child from the payments' own, and draws as
     * the graph bound to a desk a diagram never asks.
     */
    @Test
    void bookingStructureDrawsAsTheBoundGraph() {
        RoomDesk unasked = (TransitionContext<Booking, BookingStatus, BookingTransition> context) -> {
            throw new AssertionError("a diagram asks no desk");
        };
        GraphDefinition<Booking, BookingStatus, BookingTransition> bound = BookingsRuntime.graph(
                PaymentsRuntime.graph(), new BookingActions(unasked), new BillingLookups());
        assertEquals(GraphvizExporter.render(GraphView.of(bound)),
                GraphvizExporter.render(GraphView.of(BookingsRuntime.structure())));
    }

}
