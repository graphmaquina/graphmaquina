package io.github.graphmaquina.example.billing;

import static io.github.graphmaquina.test.GraphAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.runtime.StateMachines;
import io.github.graphmaquina.test.StateChange;
import io.github.graphmaquina.test.StateSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A sub-machine over the parent's own entity, declared with the DSL: a stay keeps its own status and its payment
 * status in two fields, and a graph over the payment states governs the second field. The two-argument
 * {@code submachine(state, child)} binds it with no lookup, since the child is the parent itself.
 */
class SameEntitySubmachineTest {

    /** States of a stay. */
    enum StayStatus { REQUESTED, PAYING, CONFIRMED, CANCELLED }

    /** Identifiers of a stay's transitions. {@code CONFIRM} and {@code CANCEL} are the exits of {@code PAYING}. */
    enum StayTransition { BOOK, CONFIRM, CANCEL }

    /** A stay, which is its own payment. */
    static final class Stay {
        final String id;
        StayStatus status = StayStatus.REQUESTED;
        PaymentStatus paymentStatus = PaymentStatus.AUTHORIZING;

        Stay(String id) {
            this.id = id;
        }
    }

    static final MachineKey<Stay, StayStatus, StayTransition> STAYS =
            MachineKey.of(Stay.class, StayStatus.class, StayTransition.class);

    static final MachineKey<Stay, PaymentStatus, PaymentTransition> STAY_PAYMENTS =
            MachineKey.of(Stay.class, PaymentStatus.class, PaymentTransition.class);

    /** Built once: the payment machine must be built from the very definition the stay binds. */
    static final GraphDefinition<Stay, PaymentStatus, PaymentTransition> PAYMENT_GRAPH = StateGraph.define(STAY_PAYMENTS)
            .initial(PaymentStatus.AUTHORIZING)
            .terminal(PaymentStatus.CAPTURED)
            .terminal(PaymentStatus.DECLINED)
            .terminal(PaymentStatus.VOIDED)
            .statePolicy(PaymentStatus.AUTHORIZING, "an authorization is captured, declined or voided")
            .transition(PaymentTransition.CAPTURE, PaymentStatus.AUTHORIZING, PaymentStatus.CAPTURED).endTransition()
            .transition(PaymentTransition.DECLINE, PaymentStatus.AUTHORIZING, PaymentStatus.DECLINED).endTransition()
            .transition(PaymentTransition.VOID, PaymentStatus.AUTHORIZING, PaymentStatus.VOIDED).endTransition()
            .build();

    static final GraphDefinition<Stay, StayStatus, StayTransition> STAY_GRAPH = StateGraph.define(STAYS)
            .initial(StayStatus.REQUESTED)
            .terminal(StayStatus.CONFIRMED)
            .terminal(StayStatus.CANCELLED)
            .statePolicy(StayStatus.REQUESTED, "a stay is booked first")
            .statePolicy(StayStatus.PAYING, "the payment decides")
            .transition(StayTransition.BOOK, StayStatus.REQUESTED, StayStatus.PAYING).endTransition()
            .transition(StayTransition.CONFIRM, StayStatus.PAYING, StayStatus.CONFIRMED).endTransition()
            .transition(StayTransition.CANCEL, StayStatus.PAYING, StayStatus.CANCELLED).endTransition()
            .submachine(StayStatus.PAYING, PAYMENT_GRAPH)
            .exit(PaymentStatus.CAPTURED, StayTransition.CONFIRM)
            .exit(PaymentStatus.DECLINED, StayTransition.CANCEL)
            .exit(PaymentStatus.VOIDED, StayTransition.CANCEL)
            .endSubmachine()
            .build();

    @Test
    void aStayPaysThroughASecondStateOfItsOwn() {
        Stay stay = new Stay("s1");
        try (MachineRegistry registry = registry()) {
            assertThat(registry.of(STAYS).execute(stay, StayTransition.BOOK)).isApplied().movedTo(StayStatus.PAYING)
                    .starts().forChild(STAY_PAYMENTS, stay).single()
                    .startedAt(PaymentStatus.AUTHORIZING)
                    .byParent(STAYS, stay, StayStatus.PAYING);
            StateSnapshot before = StateSnapshot.builder()
                    .track(STAYS, stay, (Stay value) -> value.status)
                    .track(STAY_PAYMENTS, stay, (Stay value) -> value.paymentStatus)
                    .capture();

            assertThat(registry.of(STAY_PAYMENTS).execute(stay, PaymentTransition.CAPTURE)).isApplied()
                    .movedTo(PaymentStatus.CAPTURED)
                    .records().forEntity(STAYS, stay).single()
                    .hasTransition(StayTransition.CONFIRM).to(StayStatus.CONFIRMED).hasDepth(1);
            assertEquals(List.of(
                            new StateChange(STAYS, stay, StayStatus.PAYING, StayStatus.CONFIRMED),
                            new StateChange(STAY_PAYMENTS, stay, PaymentStatus.AUTHORIZING, PaymentStatus.CAPTURED)),
                    before.changes());
        }
    }

    private static MachineRegistry registry() {
        return MachineRegistry.builder()
                .add(StateMachines.from(STAY_GRAPH)
                        .state((Stay stay) -> stay.status, (Stay stay, StayStatus status) -> stay.status = status)
                        .identity((Stay stay) -> stay.id))
                .add(StateMachines.from(PAYMENT_GRAPH)
                        .state((Stay stay) -> stay.paymentStatus,
                                (Stay stay, PaymentStatus status) -> stay.paymentStatus = status)
                        .identity((Stay stay) -> stay.id))
                .build();
    }
}
