package pay;

import static pay.PaymentStatus.CAPTURED;
import static pay.PaymentStatus.DECLINED;
import static pay.PaymentStatus.PENDING;
import static pay.PaymentTransition.CAPTURE;
import static pay.PaymentTransition.DECLINE;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.Outcome;

/**
 * The payment machine the producer publishes: a payment is captured or declined. It knows no parent, so any consumer
 * can bind it as the sub-machine of one of its own states.
 */
@GenerateGraphApi
@PaymentsGraph.Definition(
    initial = PENDING,
    terminals = {CAPTURED, DECLINED},
    state = PaymentRefs.Status.class,
    id = PaymentRefs.Id.class)
@PaymentsGraph.StatePolicy(state = PENDING, otherwise = "a payment is captured or declined")
public interface Payments extends Machine<Payment, PaymentStatus, PaymentTransition> {

    @PaymentsGraph.Transition(id = CAPTURE, from = PENDING, to = CAPTURED)
    Outcome<Payment, PaymentStatus, PaymentTransition> capture(Payment payment);

    @PaymentsGraph.Transition(id = DECLINE, from = PENDING, to = DECLINED)
    Outcome<Payment, PaymentStatus, PaymentTransition> decline(Payment payment);
}
