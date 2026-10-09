package io.github.graphmaquina.dsl;

import static io.github.graphmaquina.fixtures.Keys.ITEMS;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.StatePolicy;
import io.github.graphmaquina.api.SubmachineSpec;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.api.WatchCondition;
import io.github.graphmaquina.api.WatchSpec;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.error.InvalidGraphException.Problem;
import io.github.graphmaquina.fixtures.Item;
import io.github.graphmaquina.fixtures.ItemStatus;
import io.github.graphmaquina.fixtures.ItemTransition;
import io.github.graphmaquina.fixtures.Order;
import io.github.graphmaquina.fixtures.OrderStatus;
import io.github.graphmaquina.fixtures.OrderTransition;
import io.github.graphmaquina.graph.DirectedGraph;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * What {@code submachine(...)} declares: the binding the built definition holds for a state, the same-entity
 * overload, one child bound in two states and in two parents, the refusals of a spent builder, and what
 * {@code build()} reports for a binding, SG801 to SG805, with the machine keys as names.
 */
class SubmachineDslTest {

    /** The child entity of these tests; no lookup ever runs here, so it holds nothing. */
    static final class Payment { }

    /** States of the payment machine: three terminal outcomes of one authorization. */
    enum PaymentStatus { AUTHORIZING, CAPTURED, DECLINED, VOIDED }

    /** Identifiers of the payment machine. */
    enum PaymentTransition { CAPTURE, DECLINE, VOID }

    /** States of a check that governs another state of the order itself. */
    enum CheckStatus { CHECKING, CLEARED, FLAGGED }

    /** Identifiers of the check. */
    enum CheckTransition { CLEAR, FLAG }

    /** A failure kind for the recovery rule that claims an exit. */
    record Declined() implements FailureKind { }

    static final MachineKey<Payment, PaymentStatus, PaymentTransition> PAYMENTS =
            MachineKey.of(Payment.class, PaymentStatus.class, PaymentTransition.class);

    static final MachineKey<Order, CheckStatus, CheckTransition> CHECKS =
            MachineKey.of(Order.class, CheckStatus.class, CheckTransition.class);

    private static final Function<Order, Optional<Payment>> PAYMENT_OF = (Order order) -> Optional.empty();

    private static final Function<Payment, Optional<Order>> ORDER_OF = (Payment payment) -> Optional.empty();

    /** AUTHORIZING ends in CAPTURED, DECLINED or VOIDED, each terminal. */
    static GraphBuilder<Payment, PaymentStatus, PaymentTransition> paymentsHead() {
        return StateGraph.define(PAYMENTS)
                .initial(PaymentStatus.AUTHORIZING)
                .terminal(PaymentStatus.CAPTURED).terminal(PaymentStatus.DECLINED).terminal(PaymentStatus.VOIDED)
                .statePolicy(PaymentStatus.AUTHORIZING, "an authorization is captured, declined or voided")
                .transition(PaymentTransition.CAPTURE, PaymentStatus.AUTHORIZING, PaymentStatus.CAPTURED)
                    .endTransition()
                .transition(PaymentTransition.DECLINE, PaymentStatus.AUTHORIZING, PaymentStatus.DECLINED)
                    .endTransition()
                .transition(PaymentTransition.VOID, PaymentStatus.AUTHORIZING, PaymentStatus.VOIDED).endTransition();
    }

    static GraphDefinition<Payment, PaymentStatus, PaymentTransition> payments() {
        return paymentsHead().build();
    }

    static GraphDefinition<Order, CheckStatus, CheckTransition> checks() {
        return StateGraph.define(CHECKS)
                .initial(CheckStatus.CHECKING).terminal(CheckStatus.CLEARED).terminal(CheckStatus.FLAGGED)
                .statePolicy(CheckStatus.CHECKING, "a check clears or flags the order")
                .transition(CheckTransition.CLEAR, CheckStatus.CHECKING, CheckStatus.CLEARED).endTransition()
                .transition(CheckTransition.FLAG, CheckStatus.CHECKING, CheckStatus.FLAGGED).endTransition()
                .build();
    }

    /**
     * The order graph before any sub-machine: NEW leaves by CHARGE, DECLINE or CANCEL, PAID ships, and
     * PAYMENT_DECLINED leaves by RETRY or CANCEL.
     */
    static GraphBuilder<Order, OrderStatus, OrderTransition> orders() {
        return StateGraph.define(ORDERS)
                .initial(NEW).terminal(SHIPPED).terminal(CANCELLED).allowsCycle(PAYMENT_DECLINED)
                .statePolicy(NEW, "an order is charged, declined or cancelled")
                .statePolicy(PAID, "a paid order ships")
                .statePolicy(PAYMENT_DECLINED, "a declined order is retried or cancelled")
                .transition(CHARGE, NEW, PAID).endTransition()
                .transition(DECLINE, NEW, PAYMENT_DECLINED).endTransition()
                .transition(CANCEL, NEW, CANCELLED).endTransition()
                .transition(SHIP, PAID, SHIPPED).endTransition()
                .transition(RETRY, PAYMENT_DECLINED, NEW).endTransition()
                .transition(CANCEL, PAYMENT_DECLINED, CANCELLED).endTransition();
    }

    /** Runs the binding's parentOf on {@code child}, cast to the entity of the binding's own child machine. */
    private static <E, S extends Enum<S>, T extends Enum<T>, P> Optional<E> parentOf(
            SubmachineSpec<E, S, T, P, ?, ?> spec, Object child) {
        return spec.parentOf().apply(spec.childKey().entity().cast(child));
    }

    private static List<Problem> problems(GraphBuilder<?, ?, ?> builder) {
        return assertThrows(InvalidGraphException.class, builder::build).problems();
    }

    @Test
    void theDefinitionHoldsTheBindingOfItsStateWithItsExitsInDeclarationOrder() {
        GraphDefinition<Payment, PaymentStatus, PaymentTransition> payments = payments();
        GraphDefinition<Order, OrderStatus, OrderTransition> graph = orders()
                .submachine(NEW, payments, PAYMENT_OF, ORDER_OF)
                    .exit(PaymentStatus.VOIDED, CANCEL)
                    .exit(PaymentStatus.CAPTURED, CHARGE)
                    .exit(PaymentStatus.DECLINED, DECLINE)
                    .endSubmachine()
                .build();
        SubmachineSpec<Order, OrderStatus, OrderTransition, ?, ?, ?> spec = graph.submachine(NEW).orElseThrow();
        assertEquals(NEW, spec.state());
        assertSame(payments, spec.child());
        assertEquals(PAYMENTS, spec.childKey());
        assertSame(PAYMENT_OF, spec.childOf());
        assertSame(ORDER_OF, spec.parentOf());
        assertEquals(List.of(PaymentStatus.VOIDED, PaymentStatus.CAPTURED, PaymentStatus.DECLINED),
                List.copyOf(spec.exits().keySet()));
        assertEquals(List.of(CANCEL, CHARGE, DECLINE), List.copyOf(spec.exits().values()));
        for (OrderStatus other : List.of(PAID, PAYMENT_DECLINED, SHIPPED, CANCELLED)) {
            assertEquals(Optional.empty(), graph.submachine(other), other.name());
        }
        assertEquals(Optional.empty(), payments.submachine(PaymentStatus.AUTHORIZING));
        assertEquals(Optional.empty(), orders().build().submachine(NEW));
    }

    @Test
    void theDefinitionRefusesANullState() {
        GraphDefinition<Order, OrderStatus, OrderTransition> graph = orders().build();
        NullPointerException error = assertThrows(NullPointerException.class, () -> graph.submachine(null));
        assertTrue(error.getMessage().startsWith("state"), error.getMessage());
    }

    /** The overload types the child as the parent's entity, and both lookups return the entity they are given. */
    @Test
    void theSameEntityOverloadBindsAChildOfTheParentsEntityThroughTheIdentity() {
        GraphBuilder<Order, OrderStatus, OrderTransition> builder = orders();
        SubmachineBuilder<Order, OrderStatus, OrderTransition, Order, CheckStatus, CheckTransition> stage =
                builder.submachine(NEW, checks());
        stage.exit(CheckStatus.CLEARED, CHARGE).exit(CheckStatus.FLAGGED, CANCEL);
        SubmachineSpec<Order, OrderStatus, OrderTransition, ?, ?, ?> spec = builder.build().submachine(NEW)
                .orElseThrow();
        Order order = new Order("1", NEW);
        assertEquals(CHECKS, spec.childKey());
        assertEquals(Optional.of(order), spec.childOf().apply(order));
        assertSame(order, spec.childOf().apply(order).orElseThrow());
        assertEquals(Optional.of(order), parentOf(spec, order));
        assertSame(order, parentOf(spec, order).orElseThrow());
        assertEquals(Map.of(CheckStatus.CLEARED, CHARGE, CheckStatus.FLAGGED, CANCEL), spec.exits());
    }

    @Test
    void oneChildDefinitionIsBoundInTwoStatesOfAParentAndInTwoParents() {
        GraphDefinition<Payment, PaymentStatus, PaymentTransition> payments = payments();
        GraphDefinition<Order, OrderStatus, OrderTransition> orders = orders()
                .submachine(NEW, payments, PAYMENT_OF, ORDER_OF)
                    .exit(PaymentStatus.CAPTURED, CHARGE).exit(PaymentStatus.DECLINED, DECLINE)
                    .exit(PaymentStatus.VOIDED, CANCEL)
                    .endSubmachine()
                .submachine(PAYMENT_DECLINED, payments, PAYMENT_OF, ORDER_OF)
                    .exit(PaymentStatus.CAPTURED, RETRY).exit(PaymentStatus.DECLINED, CANCEL)
                    .exit(PaymentStatus.VOIDED, CANCEL)
                    .endSubmachine()
                .build();
        GraphDefinition<Item, ItemStatus, ItemTransition> items = StateGraph.define(ITEMS)
                .initial(ItemStatus.PENDING).terminal(ItemStatus.PACKED).terminal(ItemStatus.CANCELLED)
                .statePolicy(ItemStatus.PENDING, "an item is paid before it is packed")
                .statePolicy(ItemStatus.PACKING, "an item being packed is packed or cancelled")
                .transition(ItemTransition.START_PACKING, ItemStatus.PENDING, ItemStatus.PACKING).endTransition()
                .transition(ItemTransition.CANCEL, ItemStatus.PENDING, ItemStatus.CANCELLED).endTransition()
                .transition(ItemTransition.PACK, ItemStatus.PACKING, ItemStatus.PACKED).endTransition()
                .transition(ItemTransition.CANCEL, ItemStatus.PACKING, ItemStatus.CANCELLED).endTransition()
                .submachine(ItemStatus.PENDING, payments, (Item item) -> Optional.empty(),
                        (Payment payment) -> Optional.empty())
                    .exit(PaymentStatus.CAPTURED, ItemTransition.START_PACKING)
                    .exit(PaymentStatus.DECLINED, ItemTransition.CANCEL)
                    .exit(PaymentStatus.VOIDED, ItemTransition.CANCEL)
                    .endSubmachine()
                .build();
        assertSame(payments, orders.submachine(NEW).orElseThrow().child());
        assertSame(payments, orders.submachine(PAYMENT_DECLINED).orElseThrow().child());
        assertSame(payments, items.submachine(ItemStatus.PENDING).orElseThrow().child());
        assertEquals(Map.of(PaymentStatus.CAPTURED, CHARGE, PaymentStatus.DECLINED, DECLINE,
                PaymentStatus.VOIDED, CANCEL), orders.submachine(NEW).orElseThrow().exits());
        assertEquals(Map.of(PaymentStatus.CAPTURED, RETRY, PaymentStatus.DECLINED, CANCEL,
                PaymentStatus.VOIDED, CANCEL), orders.submachine(PAYMENT_DECLINED).orElseThrow().exits());
        assertEquals(Map.of(PaymentStatus.CAPTURED, ItemTransition.START_PACKING,
                        PaymentStatus.DECLINED, ItemTransition.CANCEL, PaymentStatus.VOIDED, ItemTransition.CANCEL),
                items.submachine(ItemStatus.PENDING).orElseThrow().exits());
        assertEquals(Optional.empty(), payments.submachine(PaymentStatus.AUTHORIZING));
    }

    /** build() reads every binding once: after it, the graph and every sub-machine stage refuse any call. */
    @Test
    void theGraphAndTheSubmachineStageRefuseEveryCallAfterBuild() {
        GraphDefinition<Payment, PaymentStatus, PaymentTransition> payments = payments();
        GraphBuilder<Order, OrderStatus, OrderTransition> builder = orders();
        SubmachineBuilder<Order, OrderStatus, OrderTransition, Payment, PaymentStatus, PaymentTransition> stage =
                builder.submachine(NEW, payments, PAYMENT_OF, ORDER_OF)
                        .exit(PaymentStatus.CAPTURED, CHARGE).exit(PaymentStatus.DECLINED, DECLINE)
                        .exit(PaymentStatus.VOIDED, CANCEL);
        builder.build();
        List<Executable> onTheGraph = List.of(
                () -> builder.submachine(PAYMENT_DECLINED, payments, PAYMENT_OF, ORDER_OF),
                () -> builder.submachine(PAYMENT_DECLINED, checks()));
        for (Executable call : onTheGraph) {
            assertEquals("build() was already called on the graph Order/OrderStatus",
                    assertThrows(IllegalStateException.class, call).getMessage());
        }
        List<Executable> onTheStage = List.of(() -> stage.exit(PaymentStatus.CAPTURED, RETRY), stage::endSubmachine);
        for (Executable call : onTheStage) {
            assertEquals("build() was already called on the graph Order/OrderStatus, so the sub-machine of NEW can"
                    + " no longer change", assertThrows(IllegalStateException.class, call).getMessage());
        }
    }

    /** A build() that failed spends the builder too, and the stage it opened. */
    @Test
    void theSubmachineStageRefusesEveryCallOnceBuildHasFailed() {
        GraphBuilder<Order, OrderStatus, OrderTransition> builder = orders();
        SubmachineBuilder<Order, OrderStatus, OrderTransition, Payment, PaymentStatus, PaymentTransition> stage =
                builder.submachine(NEW, payments(), PAYMENT_OF, ORDER_OF).exit(PaymentStatus.CAPTURED, CHARGE);
        assertEquals(List.of("SG801", "SG801"), assertThrows(InvalidGraphException.class, builder::build).codes());
        IllegalStateException late = assertThrows(IllegalStateException.class,
                () -> stage.exit(PaymentStatus.DECLINED, DECLINE));
        assertEquals("build() was already called on the graph Order/OrderStatus, so the sub-machine of NEW can no"
                + " longer change", late.getMessage());
    }

    @Test
    void nullArgumentsAreRefusedWithTheirNames() {
        GraphDefinition<Payment, PaymentStatus, PaymentTransition> payments = payments();
        List<String> names = List.of("state", "child", "childOf", "parentOf", "state", "child", "terminal",
                "transition");
        List<Executable> calls = List.of(
                () -> orders().submachine(null, payments, PAYMENT_OF, ORDER_OF),
                () -> orders().submachine(NEW, (GraphDefinition<Payment, PaymentStatus, PaymentTransition>) null,
                        PAYMENT_OF, ORDER_OF),
                () -> orders().submachine(NEW, payments, null, ORDER_OF),
                () -> orders().submachine(NEW, payments, PAYMENT_OF, null),
                () -> orders().submachine(null, checks()),
                () -> orders().submachine(NEW, (GraphDefinition<Order, CheckStatus, CheckTransition>) null),
                () -> orders().submachine(NEW, payments, PAYMENT_OF, ORDER_OF).exit(null, CHARGE),
                () -> orders().submachine(NEW, payments, PAYMENT_OF, ORDER_OF).exit(PaymentStatus.CAPTURED, null));
        for (int i = 0; i < calls.size(); i++) {
            NullPointerException error = assertThrows(NullPointerException.class, calls.get(i));
            assertTrue(error.getMessage().startsWith(names.get(i)), error.getMessage());
        }
    }

    /** Only raw types get a constant of another enum this far: a bound state, a terminal and an identifier. */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void aConstantOfAnotherEnumIsRefusedAtOnce() {
        GraphBuilder raw = orders();
        IllegalArgumentException state = assertThrows(IllegalArgumentException.class,
                () -> raw.submachine(PaymentStatus.AUTHORIZING, payments(), PAYMENT_OF, ORDER_OF));
        assertEquals("state must belong to io.github.graphmaquina.fixtures.OrderStatus: AUTHORIZING",
                state.getMessage());
        SubmachineBuilder stage = orders().submachine(NEW, payments(), PAYMENT_OF, ORDER_OF);
        IllegalArgumentException terminal = assertThrows(IllegalArgumentException.class,
                () -> stage.exit(CheckStatus.CLEARED, CHARGE));
        assertEquals("state must belong to io.github.graphmaquina.dsl.SubmachineDslTest$PaymentStatus: CLEARED",
                terminal.getMessage());
        IllegalArgumentException transition = assertThrows(IllegalArgumentException.class,
                () -> stage.exit(PaymentStatus.CAPTURED, PaymentTransition.CAPTURE));
        assertEquals("transition must belong to io.github.graphmaquina.fixtures.OrderTransition: CAPTURE",
                transition.getMessage());
    }

    @Test
    void sg801ATerminalWithoutExactlyOneExitAndAnExitOnAStateThatIsNotTerminal() {
        assertEquals(List.of(
                        new Problem("SG801", "Order/OrderStatus.NEW: the terminal CAPTURED of Payment/PaymentStatus has"
                                + " more than one exit"),
                        new Problem("SG801", "Order/OrderStatus.NEW: the terminal VOIDED of Payment/PaymentStatus has"
                                + " no exit"),
                        new Problem("SG801", "Order/OrderStatus.NEW: the exit on AUTHORIZING names no terminal of"
                                + " Payment/PaymentStatus")),
                problems(orders().submachine(NEW, payments(), PAYMENT_OF, ORDER_OF)
                        .exit(PaymentStatus.CAPTURED, CHARGE).exit(PaymentStatus.AUTHORIZING, DECLINE)
                        .exit(PaymentStatus.CAPTURED, CANCEL).exit(PaymentStatus.DECLINED, DECLINE)
                        .endSubmachine()));
    }

    @Test
    void sg802AnExitWhoseIdentifierHasNoEdgeFromTheNode() {
        assertEquals(List.of(new Problem("SG802", "Order/OrderStatus.NEW: the exit on CAPTURED names SHIP, which has no"
                        + " edge from NEW")),
                problems(orders().submachine(NEW, payments(), PAYMENT_OF, ORDER_OF)
                        .exit(PaymentStatus.CAPTURED, SHIP).exit(PaymentStatus.DECLINED, DECLINE)
                        .exit(PaymentStatus.VOIDED, CANCEL)
                        .endSubmachine()));
    }

    @Test
    void sg803ATerminalNodeAndANodeWithTwoBindings() {
        assertEquals(List.of(new Problem("SG803", "Order/OrderStatus.NEW holds more than one sub-machine")),
                problems(orders()
                        .submachine(NEW, payments(), PAYMENT_OF, ORDER_OF)
                            .exit(PaymentStatus.CAPTURED, CHARGE).exit(PaymentStatus.DECLINED, DECLINE)
                            .exit(PaymentStatus.VOIDED, CANCEL)
                            .endSubmachine()
                        .submachine(NEW, checks())
                            .exit(CheckStatus.CLEARED, CHARGE).exit(CheckStatus.FLAGGED, CANCEL)
                            .endSubmachine()));
        assertEquals(List.of(
                        new Problem("SG802", "Order/OrderStatus.SHIPPED: the exit on CLEARED names SHIP, which has no"
                                + " edge from SHIPPED"),
                        new Problem("SG802", "Order/OrderStatus.SHIPPED: the exit on FLAGGED names CANCEL, which has no"
                                + " edge from SHIPPED"),
                        new Problem("SG803", "Order/OrderStatus.SHIPPED is terminal and cannot hold a sub-machine")),
                problems(orders().submachine(SHIPPED, checks())
                        .exit(CheckStatus.CLEARED, SHIP).exit(CheckStatus.FLAGGED, CANCEL)
                        .endSubmachine()));
    }

    @Test
    void sg804AnExitClaimedFromItsNodeByAnOnFailureARecoveryOrAWatchRule() {
        GraphBuilder<Order, OrderStatus, OrderTransition> builder = StateGraph.define(ORDERS)
                .initial(NEW).terminal(SHIPPED).terminal(CANCELLED).allowsCycle(PAYMENT_DECLINED)
                .statePolicy(NEW, "an order is charged, declined or cancelled")
                .statePolicy(PAID, "a paid order ships")
                .statePolicy(PAYMENT_DECLINED, "a declined order is retried or cancelled")
                .transition(CHARGE, NEW, PAID)
                    .action("charge", (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                            ActionOutcome.ok())
                    .onFailure(DECLINE).recover(Declined.class, CANCEL)
                    .endTransition()
                .transition(DECLINE, NEW, PAYMENT_DECLINED).endTransition()
                .transition(CANCEL, NEW, CANCELLED).endTransition()
                .transition(SHIP, PAID, SHIPPED).endTransition()
                .transition(RETRY, PAYMENT_DECLINED, NEW).endTransition()
                .transition(CANCEL, PAYMENT_DECLINED, CANCELLED).endTransition()
                .watch(NEW, ITEMS, Order::items, Item::order)
                    .when(WatchCondition.allIn(ItemStatus.CANCELLED)).execute(CANCEL)
                    .endWatch()
                .submachine(NEW, payments(), PAYMENT_OF, ORDER_OF)
                    .exit(PaymentStatus.CAPTURED, CHARGE).exit(PaymentStatus.DECLINED, DECLINE)
                    .exit(PaymentStatus.VOIDED, CANCEL)
                    .endSubmachine();
        assertEquals(List.of(
                        new Problem("SG804", "Order/OrderStatus.NEW: DECLINE is an exit of its sub-machine and cannot"
                                + " be the onFailure of CHARGE"),
                        new Problem("SG804", "Order/OrderStatus.NEW: CANCEL is an exit of its sub-machine and cannot be"
                                + " a recovery of CHARGE"),
                        new Problem("SG804", "Order/OrderStatus.NEW: CANCEL is an exit of its sub-machine and cannot be"
                                + " executed by a watch rule")),
                problems(builder));
    }

    /** A second definition under the parent's own key is the only way the DSL can make a machine contain itself. */
    @Test
    void sg805AChildThatIsTheParentsOwnMachine() {
        assertEquals(List.of(new Problem("SG805", "the sub-machines of Order/OrderStatus contain Order/OrderStatus"
                        + " again: Order/OrderStatus.NEW -> Order/OrderStatus")),
                problems(orders().submachine(NEW, orders().build())
                        .exit(SHIPPED, CHARGE).exit(CANCELLED, CANCEL)
                        .endSubmachine()));
    }

    /** A child that binds, in one of its own states, a definition under the parent's key. */
    @Test
    void sg805AParentReachedAgainThroughTheSubmachinesOfItsChild() {
        assertEquals(List.of(new Problem("SG805", "the sub-machines of Order/OrderStatus contain Order/OrderStatus"
                        + " again: Order/OrderStatus.NEW -> Payment/PaymentStatus.AUTHORIZING -> Order/OrderStatus")),
                problems(orders().submachine(NEW, paymentsBindingOrders(), PAYMENT_OF, ORDER_OF)
                        .exit(PaymentStatus.CAPTURED, CHARGE).exit(PaymentStatus.DECLINED, DECLINE)
                        .exit(PaymentStatus.VOIDED, CANCEL)
                        .endSubmachine()));
    }

    /** Distinct definitions of one child key are checked along each binding's own path. */
    @Test
    void sg805ChecksDistinctDefinitionsOfTheSameChildKey() {
        assertEquals(List.of(new Problem("SG805", "the sub-machines of Order/OrderStatus contain Order/OrderStatus"
                        + " again: Order/OrderStatus.PAYMENT_DECLINED -> Payment/PaymentStatus.AUTHORIZING"
                        + " -> Order/OrderStatus")),
                problems(orders()
                        .submachine(NEW, payments(), PAYMENT_OF, ORDER_OF)
                            .exit(PaymentStatus.CAPTURED, CHARGE).exit(PaymentStatus.DECLINED, DECLINE)
                            .exit(PaymentStatus.VOIDED, CANCEL)
                            .endSubmachine()
                        .submachine(PAYMENT_DECLINED, paymentsBindingOrders(), PAYMENT_OF, ORDER_OF)
                            .exit(PaymentStatus.CAPTURED, RETRY).exit(PaymentStatus.DECLINED, CANCEL)
                            .exit(PaymentStatus.VOIDED, CANCEL)
                            .endSubmachine()));
    }

    /** NEW leads back in two steps and PAYMENT_DECLINED in one: the shorter path is the one reported. */
    @Test
    void sg805ReportsOneShortestPath() {
        assertEquals(List.of(new Problem("SG805", "the sub-machines of Order/OrderStatus contain Order/OrderStatus"
                        + " again: Order/OrderStatus.PAYMENT_DECLINED -> Order/OrderStatus")),
                problems(orders()
                        .submachine(NEW, paymentsBindingOrders(), PAYMENT_OF, ORDER_OF)
                            .exit(PaymentStatus.CAPTURED, CHARGE).exit(PaymentStatus.DECLINED, DECLINE)
                            .exit(PaymentStatus.VOIDED, CANCEL)
                            .endSubmachine()
                        .submachine(PAYMENT_DECLINED, orders().build())
                            .exit(SHIPPED, RETRY).exit(CANCELLED, CANCEL)
                            .endSubmachine()));
    }

    /**
     * The walk never expands a key twice on one path, so it ends on a definition that hands out a new instance of
     * itself in each of its bindings.
     */
    @Test
    void theWalkEndsOnAHandWrittenDefinitionThatBindsItselfAgainAndAgain() {
        GraphDefinition<Order, OrderStatus, OrderTransition> graph = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> orders().submachine(NEW, new SelfBinding(), PAYMENT_OF, ORDER_OF)
                        .exit(PaymentStatus.CAPTURED, CHARGE).exit(PaymentStatus.DECLINED, DECLINE)
                        .exit(PaymentStatus.VOIDED, CANCEL)
                        .endSubmachine()
                        .build());
        assertEquals(PAYMENTS, graph.submachine(NEW).orElseThrow().childKey());
    }

    /** A chain of sub-machines that never comes back to the parent's own key is no cycle. */
    @Test
    void aChainThatNeverReachesTheParentsKeyIsValid() {
        GraphDefinition<Payment, PaymentStatus, PaymentTransition> nested = paymentsBindingOrders();
        GraphDefinition<Order, CheckStatus, CheckTransition> checks = StateGraph.define(CHECKS)
                .initial(CheckStatus.CHECKING).terminal(CheckStatus.CLEARED).terminal(CheckStatus.FLAGGED)
                .statePolicy(CheckStatus.CHECKING, "a check clears or flags the order")
                .transition(CheckTransition.CLEAR, CheckStatus.CHECKING, CheckStatus.CLEARED).endTransition()
                .transition(CheckTransition.FLAG, CheckStatus.CHECKING, CheckStatus.FLAGGED).endTransition()
                .submachine(CheckStatus.CHECKING, nested, PAYMENT_OF, ORDER_OF)
                    .exit(PaymentStatus.CAPTURED, CheckTransition.CLEAR)
                    .exit(PaymentStatus.DECLINED, CheckTransition.FLAG)
                    .exit(PaymentStatus.VOIDED, CheckTransition.FLAG)
                    .endSubmachine()
                .build();
        assertSame(nested, checks.submachine(CheckStatus.CHECKING).orElseThrow().child());
    }

    /** A payments graph whose AUTHORIZING binds an order graph: CAPTURE when it ships, DECLINE when it is cancelled. */
    private static GraphDefinition<Payment, PaymentStatus, PaymentTransition> paymentsBindingOrders() {
        return paymentsHead()
                .submachine(PaymentStatus.AUTHORIZING, orders().build(), (Payment payment) -> Optional.empty(),
                        (Order order) -> Optional.empty())
                    .exit(SHIPPED, PaymentTransition.CAPTURE).exit(CANCELLED, PaymentTransition.DECLINE)
                    .endSubmachine()
                .build();
    }

    /**
     * The payments graph, except that AUTHORIZING binds a new instance of this definition, without end: only a hand
     * can write such a definition.
     */
    private static final class SelfBinding implements GraphDefinition<Payment, PaymentStatus, PaymentTransition> {

        private final GraphDefinition<Payment, PaymentStatus, PaymentTransition> delegate = payments();

        @Override
        public MachineKey<Payment, PaymentStatus, PaymentTransition> key() {
            return delegate.key();
        }

        @Override
        public PaymentStatus initial() {
            return delegate.initial();
        }

        @Override
        public Set<PaymentStatus> terminals() {
            return delegate.terminals();
        }

        @Override
        public DirectedGraph<PaymentStatus, PaymentTransition> graph() {
            return delegate.graph();
        }

        @Override
        public List<TransitionSpec<Payment, PaymentStatus, PaymentTransition>> transitions() {
            return delegate.transitions();
        }

        @Override
        public List<TransitionSpec<Payment, PaymentStatus, PaymentTransition>> outgoingFrom(PaymentStatus state) {
            return delegate.outgoingFrom(state);
        }

        @Override
        public Optional<TransitionSpec<Payment, PaymentStatus, PaymentTransition>> findTransition(
                PaymentStatus from, PaymentTransition transition) {
            return delegate.findTransition(from, transition);
        }

        @Override
        public StatePolicy<PaymentStatus, PaymentTransition> policy(PaymentStatus state) {
            return delegate.policy(state);
        }

        @Override
        public Optional<WatchSpec<Payment, PaymentStatus, PaymentTransition, ?, ?, ?>> watch(PaymentStatus state) {
            return delegate.watch(state);
        }

        @Override
        public Optional<SubmachineSpec<Payment, PaymentStatus, PaymentTransition, ?, ?, ?>> submachine(
                PaymentStatus state) {
            if (state != PaymentStatus.AUTHORIZING) {
                return Optional.empty();
            }
            return Optional.of(new SubmachineSpec<>(state, new SelfBinding(), (Payment payment) -> Optional.empty(),
                    (Payment payment) -> Optional.empty(), Map.of(PaymentStatus.CAPTURED, PaymentTransition.CAPTURE)));
        }

        @Override
        public boolean hasAsync() {
            return delegate.hasAsync();
        }
    }
}
