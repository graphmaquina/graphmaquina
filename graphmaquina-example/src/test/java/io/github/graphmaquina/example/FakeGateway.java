package io.github.graphmaquina.example;

import io.github.graphmaquina.example.order.Charge;
import io.github.graphmaquina.example.order.Order;
import io.github.graphmaquina.example.order.PaymentGateway;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Queue of scheduled responses; without any scheduled, it accepts. Accepted and declined charges complete from the
 * common pool, like an HTTP client; a scheduled failure is a stage that has already failed. It keeps the orders it was
 * asked to charge, in the order it was asked.
 */
final class FakeGateway implements PaymentGateway {

    final List<Order> charged = new ArrayList<>();

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
        charged.add(order);
        Supplier<CompletionStage<Charge>> next = responses.pollFirst();
        if (next == null) {
            return CompletableFuture.supplyAsync(Charge::accepted);
        }
        return next.get();
    }
}
