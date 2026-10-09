package io.github.graphmaquina.dsl;

import lombok.NonNull;

/**
 * One exit of a {@link SubmachineOutline}, by names.
 *
 * @param terminal the state of the child the exit is declared on
 * @param transition the identifier of the parent it maps to
 */
public record ExitOutline(@NonNull String terminal, @NonNull String transition) { }
