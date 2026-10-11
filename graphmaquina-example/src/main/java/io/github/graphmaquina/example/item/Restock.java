package io.github.graphmaquina.example.item;

import io.github.graphmaquina.example.order.Order;
import lombok.NonNull;

/**
 * The restock of a cancelled item: the batch it goes back to stock in and the order it leaves. The item's
 * {@code restock} action leaves it in the call's flow, and the item's {@code applyRestock} hook applies it.
 */
public record Restock(@NonNull String batch, @NonNull Order order) { }
