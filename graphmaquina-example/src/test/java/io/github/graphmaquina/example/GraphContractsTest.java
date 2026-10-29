package io.github.graphmaquina.example;

import io.github.graphmaquina.api.GraphDefinition;
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
import io.github.graphmaquina.test.GraphSnapshots;
import org.junit.jupiter.api.Test;

/** Declared metadata of the example machines, independent of graphical exporters. */
class GraphContractsTest {

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
    void orderContractMatchesSnapshot() {
        GraphSnapshots.assertMatches(orderGraph(), "contracts/order.graph");
    }

    @Test
    void itemContractMatchesSnapshot() {
        GraphSnapshots.assertMatches(itemGraph(), "contracts/item.graph");
    }

    @Test
    void accountContractMatchesSnapshot() {
        GraphSnapshots.assertMatches(accountGraph(), "contracts/account.graph");
    }
}
