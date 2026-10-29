package io.github.graphmaquina.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.error.InvalidGraphException.Problem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The sub-machine checks of {@link MachineOutlineValidator} on outlines written by hand, as both producers hand them
 * over: SG801 to SG804 with their exact messages and their order, and what the outline's constructor refuses.
 */
class SubmachineValidationTest {

    private static final String ORDERS = "Order/OrderStatus";
    private static final String PAYMENTS = "Payment/PaymentStatus";
    private static final List<String> STATES = List.of("NEW", "PAID", "PAYMENT_DECLINED", "SHIPPED", "CANCELLED");
    private static final List<String> CATALOG = List.of("CHARGE", "DECLINE", "RETRY", "SHIP", "CANCEL");
    private static final List<String> PAYMENT_TERMINALS = List.of("CAPTURED", "DECLINED", "VOIDED");

    private static TransitionOutline move(String from, String transition, String to) {
        return new TransitionOutline(from, transition, to, TransitionKind.MOVE, Optional.empty());
    }

    /** An action edge with an optional onFailure and any number of recovery identifiers. */
    private static TransitionOutline action(String from, String transition, String to, Optional<String> onFailure,
                                            String... recoveries) {
        return new TransitionOutline(from, transition, to, TransitionKind.ACTION, onFailure, List.of(recoveries),
                false, false, false, List.of());
    }

    /** NEW leaves by CHARGE, DECLINE or CANCEL; PAYMENT_DECLINED by RETRY or CANCEL; PAID ships. */
    private static List<TransitionOutline> edges() {
        return List.of(move("NEW", "CHARGE", "PAID"), move("NEW", "DECLINE", "PAYMENT_DECLINED"),
                move("NEW", "CANCEL", "CANCELLED"), move("PAID", "SHIP", "SHIPPED"),
                move("PAYMENT_DECLINED", "RETRY", "NEW"), move("PAYMENT_DECLINED", "CANCEL", "CANCELLED"));
    }

    private static List<PolicyOutline> policies() {
        List<PolicyOutline> policies = new ArrayList<>();
        for (String state : STATES) {
            policies.add(new PolicyOutline(state, state + " rejects the rest", Map.of()));
        }
        return policies;
    }

    private static ExitOutline exit(String terminal, String transition) {
        return new ExitOutline(terminal, transition);
    }

    private static SubmachineOutline payments(String state, ExitOutline... exits) {
        return new SubmachineOutline(state, PAYMENTS, PAYMENT_TERMINALS, List.of(exits));
    }

    private static MachineOutline outline(List<TransitionOutline> edges, List<WatchOutline> watches,
                                          SubmachineOutline... submachines) {
        return new MachineOutline(ORDERS, STATES, CATALOG, "NEW", Set.of("SHIPPED", "CANCELLED"),
                Set.of("PAYMENT_DECLINED"), edges, policies(), watches, List.of(submachines), List.of());
    }

    private static List<Problem> validate(SubmachineOutline... submachines) {
        return MachineOutlineValidator.validate(outline(edges(), List.of(), submachines));
    }

    @Test
    void aBindingWhoseTerminalsEachHaveOneExitWithAnEdgeFromTheNodeIsValid() {
        assertEquals(List.of(), validate(payments("NEW", exit("CAPTURED", "CHARGE"), exit("DECLINED", "DECLINE"),
                exit("VOIDED", "CANCEL"))));
    }

    /** Two terminals may select the same identifier, and one child may be bound in two states. */
    @Test
    void twoTerminalsMayShareAnIdentifierAndTwoStatesMayBindTheSameChild() {
        assertEquals(List.of(), validate(
                payments("NEW", exit("CAPTURED", "CHARGE"), exit("DECLINED", "CANCEL"), exit("VOIDED", "CANCEL")),
                payments("PAYMENT_DECLINED", exit("CAPTURED", "RETRY"), exit("DECLINED", "CANCEL"),
                        exit("VOIDED", "CANCEL"))));
    }

    /**
     * One binding with every SG801 case: a terminal with two exits and one with none, filed under the node alone in
     * the child's terminal order, then an exit on a state that is not terminal, filed under its identifier.
     */
    @Test
    void sg801ATerminalWithoutExactlyOneExitAndAnExitOnAStateThatIsNotTerminal() {
        assertEquals(List.of(
                        new Problem(DiagnosticCode.SG801.name(),
                                "Order/OrderStatus.NEW: the terminal CAPTURED of Payment/PaymentStatus has"
                                + " more than one exit"),
                        new Problem(DiagnosticCode.SG801.name(),
                                "Order/OrderStatus.NEW: the terminal VOIDED of Payment/PaymentStatus has"
                                + " no exit"),
                        new Problem(DiagnosticCode.SG801.name(),
                                "Order/OrderStatus.NEW: the exit on AUTHORIZING names no terminal of"
                                + " Payment/PaymentStatus")),
                validate(payments("NEW", exit("CAPTURED", "CHARGE"), exit("AUTHORIZING", "DECLINE"),
                        exit("CAPTURED", "CANCEL"), exit("DECLINED", "DECLINE"))));
    }

    /** Each exit is checked on its own, in declaration order within one identifier. */
    @Test
    void sg802AnExitWhoseIdentifierHasNoEdgeFromTheNode() {
        assertEquals(List.of(
                        new Problem(DiagnosticCode.SG802.name(),
                                "Order/OrderStatus.NEW: the exit on CAPTURED names RETRY, which has no"
                                + " edge from NEW"),
                        new Problem(DiagnosticCode.SG802.name(),
                                "Order/OrderStatus.NEW: the exit on DECLINED names SHIP, which has no edge"
                                + " from NEW"),
                        new Problem(DiagnosticCode.SG802.name(),
                                "Order/OrderStatus.NEW: the exit on VOIDED names SHIP, which has no edge"
                                + " from NEW")),
                validate(payments("NEW", exit("DECLINED", "SHIP"), exit("CAPTURED", "RETRY"),
                        exit("VOIDED", "SHIP"))));
    }

    /**
     * A state with two bindings is reported once. Every binding is checked all the same: the exits of a terminal node
     * name identifiers that no edge from it declares.
     */
    @Test
    void sg803ATerminalNodeAndANodeWithTwoBindings() {
        assertEquals(List.of(
                        new Problem(DiagnosticCode.SG802.name(),
                                "Order/OrderStatus.SHIPPED: the exit on CAPTURED names SHIP, which has no"
                                + " edge from SHIPPED"),
                        new Problem(DiagnosticCode.SG802.name(),
                                "Order/OrderStatus.SHIPPED: the exit on DECLINED names CANCEL, which has"
                                + " no edge from SHIPPED"),
                        new Problem(DiagnosticCode.SG802.name(),
                                "Order/OrderStatus.SHIPPED: the exit on VOIDED names CANCEL, which has no"
                                + " edge from SHIPPED"),
                        new Problem(DiagnosticCode.SG803.name(),
                                "Order/OrderStatus.NEW holds more than one sub-machine"),
                        new Problem(DiagnosticCode.SG803.name(),
                                "Order/OrderStatus.SHIPPED is terminal and cannot hold a sub-machine")),
                validate(payments("SHIPPED", exit("CAPTURED", "SHIP"), exit("DECLINED", "CANCEL"),
                                exit("VOIDED", "CANCEL")),
                        payments("NEW", exit("CAPTURED", "CHARGE"), exit("DECLINED", "DECLINE"),
                                exit("VOIDED", "CANCEL")),
                        new SubmachineOutline("NEW", "Order/CheckStatus", List.of("CLEARED", "FLAGGED"),
                                List.of(exit("CLEARED", "CHARGE"), exit("FLAGGED", "CANCEL")))));
    }

    /**
     * Every claim on an exit from its own node: an onFailure, a recovery rule, a watch rule. Within one identifier,
     * the edges come in outline order, each with its recovery rules before its onFailure, then the watch.
     */
    @Test
    void sg804AnExitClaimedFromItsNodeByAnOnFailureARecoveryOrAWatchRule() {
        List<TransitionOutline> edges = List.of(
                action("NEW", "CHARGE", "PAID", Optional.of("CANCEL"), "DECLINE", "DECLINE", "CANCEL"),
                move("NEW", "DECLINE", "PAYMENT_DECLINED"),
                action("NEW", "CANCEL", "CANCELLED", Optional.of("DECLINE")),
                move("PAID", "SHIP", "SHIPPED"), move("PAYMENT_DECLINED", "RETRY", "NEW"),
                move("PAYMENT_DECLINED", "CANCEL", "CANCELLED"));
        List<WatchOutline> watches = List.of(new WatchOutline("NEW", List.of("CANCEL", "CHARGE", "CANCEL")));
        MachineOutline outline = outline(edges, watches,
                payments("NEW", exit("CAPTURED", "CHARGE"), exit("DECLINED", "DECLINE"), exit("VOIDED", "CANCEL")));
        assertEquals(List.of(
                        new Problem(DiagnosticCode.SG804.name(),
                                "Order/OrderStatus.NEW: CHARGE is an exit of its sub-machine and cannot be"
                                + " executed by a watch rule"),
                        new Problem(DiagnosticCode.SG804.name(),
                                "Order/OrderStatus.NEW: DECLINE is an exit of its sub-machine and cannot"
                                + " be a recovery of CHARGE"),
                        new Problem(DiagnosticCode.SG804.name(),
                                "Order/OrderStatus.NEW: DECLINE is an exit of its sub-machine and cannot"
                                + " be the onFailure of CANCEL"),
                        new Problem(DiagnosticCode.SG804.name(),
                                "Order/OrderStatus.NEW: CANCEL is an exit of its sub-machine and cannot be"
                                + " a recovery of CHARGE"),
                        new Problem(DiagnosticCode.SG804.name(),
                                "Order/OrderStatus.NEW: CANCEL is an exit of its sub-machine and cannot be"
                                + " the onFailure of CHARGE"),
                        new Problem(DiagnosticCode.SG804.name(),
                                "Order/OrderStatus.NEW: CANCEL is an exit of its sub-machine and cannot be"
                                + " executed by a watch rule")),
                MachineOutlineValidator.validate(outline));
    }

    /** The same identifiers claimed from a state that holds no binding are no exit there. */
    @Test
    void anExitIsReservedFromItsOwnNodeOnly() {
        List<TransitionOutline> edges = List.of(move("NEW", "CHARGE", "PAID"),
                move("NEW", "DECLINE", "PAYMENT_DECLINED"), move("NEW", "CANCEL", "CANCELLED"),
                move("PAID", "SHIP", "SHIPPED"), action("PAYMENT_DECLINED", "RETRY", "NEW", Optional.of("CANCEL")),
                move("PAYMENT_DECLINED", "CANCEL", "CANCELLED"));
        List<WatchOutline> watches = List.of(new WatchOutline("PAYMENT_DECLINED", List.of("CANCEL")));
        assertEquals(List.of(), MachineOutlineValidator.validate(outline(edges, watches,
                payments("NEW", exit("CAPTURED", "CHARGE"), exit("DECLINED", "DECLINE"), exit("VOIDED", "CANCEL")))));
    }

    /** Problems of two bindings are sorted by the index of their node, whatever the order of the bindings. */
    @Test
    void theProblemsOfSeveralBindingsFollowTheOrderOfTheirNodes() {
        assertEquals(List.of(
                        new Problem(DiagnosticCode.SG801.name(),
                                "Order/OrderStatus.NEW: the terminal VOIDED of Payment/PaymentStatus has"
                                + " no exit"),
                        new Problem(DiagnosticCode.SG801.name(),
                                "Order/OrderStatus.PAYMENT_DECLINED: the terminal DECLINED of"
                                + " Payment/PaymentStatus has no exit")),
                validate(payments("PAYMENT_DECLINED", exit("CAPTURED", "RETRY"), exit("VOIDED", "CANCEL")),
                        payments("NEW", exit("CAPTURED", "CHARGE"), exit("DECLINED", "DECLINE"))));
    }

    /**
     * The bound state and each exit's identifier are names of this machine, and the constructor checks them. The
     * child's terminals and the states the exits name belong to the child, which only the validator compares.
     */
    @Test
    void theOutlineRefusesAnUnknownBoundStateOrExitIdentifierButNotTheChildsStates() {
        IllegalArgumentException state = assertThrows(IllegalArgumentException.class,
                () -> outline(edges(), List.of(), payments("PAYING", exit("CAPTURED", "CHARGE"))));
        assertEquals("Order/OrderStatus: submachine.state PAYING is not one of [NEW, PAID, PAYMENT_DECLINED, SHIPPED,"
                + " CANCELLED]", state.getMessage());
        IllegalArgumentException identifier = assertThrows(IllegalArgumentException.class,
                () -> outline(edges(), List.of(), payments("NEW", exit("CAPTURED", "REFUND"))));
        assertEquals("Order/OrderStatus: submachine.exit REFUND is not one of [CHARGE, DECLINE, RETRY, SHIP, CANCEL]",
                identifier.getMessage());
        MachineOutline foreign = outline(edges(), List.of(), new SubmachineOutline("NEW", PAYMENTS,
                List.of("NEW", "SETTLED"), List.of(exit("SETTLED", "CHARGE"), exit("NEW", "CANCEL"))));
        assertEquals(List.of(new SubmachineOutline("NEW", PAYMENTS, List.of("NEW", "SETTLED"),
                List.of(exit("SETTLED", "CHARGE"), exit("NEW", "CANCEL")))), foreign.submachines());
    }

    @Test
    void aSubmachineOutlineKeepsReadOnlyCopiesAndAnOutlineWithoutOneHoldsNone() {
        List<String> terminals = new ArrayList<>(PAYMENT_TERMINALS);
        List<ExitOutline> exits = new ArrayList<>(List.of(exit("CAPTURED", "CHARGE"), exit("CAPTURED", "CHARGE")));
        SubmachineOutline binding = new SubmachineOutline("NEW", PAYMENTS, terminals, exits);
        terminals.clear();
        exits.clear();
        assertEquals(PAYMENT_TERMINALS, binding.childTerminals());
        assertEquals(List.of(exit("CAPTURED", "CHARGE"), exit("CAPTURED", "CHARGE")), binding.exits());
        assertThrows(UnsupportedOperationException.class, () -> binding.exits().add(exit("VOIDED", "CANCEL")));
        MachineOutline plain = new MachineOutline(ORDERS, STATES, CATALOG, "NEW", Set.of("SHIPPED", "CANCELLED"),
                Set.of("PAYMENT_DECLINED"), edges(), policies(), List.of());
        assertEquals(List.of(), plain.submachines());
    }
}
