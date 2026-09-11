package io.github.graphmaquina.api;

import lombok.NonNull;

/**
 * Immutable metadata of the committed cell delivered to a post-commit hook.
 * {@code isTerminal} describes {@code to}, using the graph's terminal declarations at build time.
 * In a cascade the entity may already hold a later state; {@code from} and {@code to} still describe
 * this hop. This context has no transaction operations and may be retained after the hook returns.
 *
 * @param <S> the machine's state type
 * @param from the state the hop left
 * @param to the state the hop committed
 * @param kind the cell's kind, always {@code MOVE} or {@code ACTION} because only those cells declare a hook
 * @param isTerminal whether {@code to} is one of the graph's terminal states
 */
public record CommitContext<S extends Enum<S>>(@NonNull S from, @NonNull S to,
                                                @NonNull EdgeKind kind, boolean isTerminal) {
}
