package io.github.graphmaquina.api;

import lombok.NonNull;

/**
 * Immutable metadata of the committed transition delivered to a post-commit hook.
 * {@code isTerminal} describes {@code to}, using the graph's terminal declarations.
 * In a cascade the entity may already hold a later state; {@code from} and {@code to} still describe
 * this hop. This context has no transaction operations and may be retained after the hook returns.
 *
 * @param <S> the machine's state type
 * @param <T> the machine's transition identifier type
 * @param from the state the hop left
 * @param transition the identifier of the committed transition
 * @param to the state the hop committed
 * @param kind whether the committed transition ran an action
 * @param isTerminal whether {@code to} is one of the graph's terminal states
 */
public record CommitContext<S extends Enum<S>, T extends Enum<T>>(@NonNull S from, @NonNull T transition, @NonNull S to,
                                                                   @NonNull TransitionKind kind, boolean isTerminal) {
}
