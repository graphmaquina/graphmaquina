package io.github.graphmaquina.api;

import lombok.NonNull;

/**
 * Immutable metadata of the committed cell delivered to a post-commit hook.
 * {@code isTerminal} describes {@code to}, using the graph's terminal declarations at build time.
 * In a cascade the entity may already hold a later state; {@code from} and {@code to} still describe
 * this hop. This context has no transaction operations and may be retained after the hook returns.
 */
public record CommitContext<S extends Enum<S>>(@NonNull S from, @NonNull S to,
                                                @NonNull EdgeKind kind, boolean isTerminal) {
}
