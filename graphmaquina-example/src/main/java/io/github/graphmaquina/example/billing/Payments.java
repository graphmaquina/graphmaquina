package io.github.graphmaquina.example.billing;

import static io.github.graphmaquina.example.billing.PaymentStatus.AUTHORIZING;
import static io.github.graphmaquina.example.billing.PaymentStatus.CAPTURED;
import static io.github.graphmaquina.example.billing.PaymentStatus.DECLINED;
import static io.github.graphmaquina.example.billing.PaymentStatus.VOIDED;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.Outcome;

/**
 * The payment machine, the reusable child: four states and three edges. It declares no parent and no peer; the
 * bookings and the subscriptions bind it as a sub-machine from their own side.
 */
@GenerateGraphApi
@PaymentsGraph.Definition(initial = AUTHORIZING, terminals = {CAPTURED, DECLINED, VOIDED},
        state = PaymentRefs.Status.class, id = PaymentRefs.Id.class)
@PaymentsGraph.StatePolicy(state = AUTHORIZING, otherwise = "an authorization is captured, declined or voided")
public interface Payments extends Machine<Payment, PaymentStatus, PaymentTransition> {

    @PaymentsGraph.Transition(id = PaymentTransition.CAPTURE, from = AUTHORIZING, to = CAPTURED)
    Outcome<Payment, PaymentStatus, PaymentTransition> capture(Payment payment);

    @PaymentsGraph.Check(id = PaymentTransition.CAPTURE)
    Eligibility<Payment, PaymentStatus, PaymentTransition> canCapture(Payment payment);

    @PaymentsGraph.Transition(id = PaymentTransition.DECLINE, from = AUTHORIZING, to = DECLINED)
    Outcome<Payment, PaymentStatus, PaymentTransition> decline(Payment payment);

    @PaymentsGraph.Transition(id = PaymentTransition.VOID, from = AUTHORIZING, to = VOIDED)
    Outcome<Payment, PaymentStatus, PaymentTransition> voidPayment(Payment payment);
}
