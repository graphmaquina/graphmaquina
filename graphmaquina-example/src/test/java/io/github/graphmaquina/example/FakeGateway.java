package io.github.graphmaquina.example;

import io.github.graphmaquina.example.order.Charge;
import io.github.graphmaquina.example.order.Order;
import io.github.graphmaquina.example.order.PaymentGateway;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Queue of scheduled responses; without any scheduled, it accepts. Completes from the common pool, like an HTTP client. */
final class FakeGateway implements PaymentGateway {

    private final Deque<Supplier<CompletionStage<Charge>>> responses = new ArrayDeque<>();

    FakeGateway accept() {
        responses.addLast(() -> CompletableFuture.supplyAsync(Charge::accepted));
        return this;
    }

    FakeGateway decline(String code) {
        responses.addLast(() -> CompletableFuture.supplyAsync(() -> Charge.declined(code)));
        return this;
    }

    FakeGateway fail(Throwable error) {
        responses.addLast(() -> CompletableFuture.failedFuture(error));
        return this;
    }

    @Override
    public CompletionStage<Charge> chargeAsync(Order order) {
        Supplier<CompletionStage<Charge>> next = responses.pollFirst();
        if (next == null) {
            return CompletableFuture.supplyAsync(Charge::accepted);
        }
        return next.get();
    }
}
