package io.github.graphmaquina.export;

import java.util.List;
import lombok.NonNull;

/**
 * One recovery rule of an edge seen only through names. A view keeps the rules of its edge in the order they
 * were declared, which is no priority: the runtime routes a returned failure by its kind and a thrown one by the
 * most specific declared type, whatever the order.
 *
 * @param kind binary name of the failure kind, as {@link Class#getName()} gives it
 * @param transition name of the recovery identifier, resolved from the edge's origin
 * @param failsOn binary names of the throwable types the rule admits, in declaration order; empty when only a
 *     returned failure of {@code kind} selects the rule
 */
public record RecoveryView(@NonNull String kind, @NonNull String transition, @NonNull List<String> failsOn) {

    /** Copies the throwable names so later changes to the source list cannot change this view. */
    public RecoveryView {
        failsOn = List.copyOf(failsOn);
    }
}
