package io.github.graphmaquina.dsl;

import java.util.List;
import lombok.NonNull;

/**
 * One {@code automaticOrder} declaration of a state, by names. A state may carry several declarations in an outline,
 * which the validator reports, so each one is kept as it was written, repeats included.
 *
 * @param state the state whose automatic edges the declaration orders
 * @param transitions the identifiers it names, in the order it names them
 */
public record AutomaticOrderOutline(@NonNull String state, @NonNull List<String> transitions) {

    /** Copies {@code transitions}, keeping its order and its repeats. */
    public AutomaticOrderOutline {
        transitions = List.copyOf(transitions);
    }
}
