package pay;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.Outcome;
import static pay.PaymentStatus.*;
import static pay.PaymentTransition.*;

/** Payments. */
@GenerateGraphApi
@PaymentsGraph.Definition(initial = PENDING, terminals = {CAPTURED, DECLINED},
    state = PaymentRefs.Status.class, id = PaymentRefs.Id.class)
@PaymentsGraph.StatePolicy(state = PENDING, otherwise = "a payment is captured or declined")
public interface Payments extends Machine<Payment, PaymentStatus, PaymentTransition> {
    @PaymentsGraph.Transition(id = CAPTURE, from = PENDING, to = CAPTURED)
    Outcome<Payment, PaymentStatus, PaymentTransition> capture(Payment payment);
    @PaymentsGraph.Transition(id = DECLINE, from = PENDING, to = DECLINED)
    Outcome<Payment, PaymentStatus, PaymentTransition> decline(Payment payment);
}
