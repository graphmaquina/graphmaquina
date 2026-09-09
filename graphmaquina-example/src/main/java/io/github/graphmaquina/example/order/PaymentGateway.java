package io.github.graphmaquina.example.order;

import java.util.concurrent.CompletionStage;

/** A real gateway completes the stage from its own thread and fails with an IOException when the network goes down. */
public interface PaymentGateway {

    CompletionStage<Charge> chargeAsync(Order order);
}
