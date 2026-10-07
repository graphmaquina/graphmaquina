package io.github.graphmaquina.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import lombok.NonNull;

/**
 * The watch of a state: which machine it looks at, how to reach the peers and back, and which rules advance it.
 * {@code peersOf}, {@code ownerOf} and the rules' conditions run while the thread evaluates the watch, where execute
 * and reevaluate throw {@code ReentrantTransitionException}, as they do in a guard. An Exception that {@code peersOf}
 * or a condition throws ends the reevaluation in a {@code TransitionAbortedException} of phase RESOLVE that carries it,
 * once unwrapped from CompletionException and ExecutionException, which a watch reaction reports as
 * {@code Reaction.Aborted}. An Exception that {@code ownerOf} throws, while the commit that triggered the reaction
 * looks the owner up, is the cause of a {@code Reaction.Aborted} itself, a checked one wrapped in
 * {@code UndeclaredThrowableException}.
 */
public record WatchSpec<E, S extends Enum<S>, T extends Enum<T>, P, Q extends Enum<Q>, U extends Enum<U>>(
        @NonNull S state,
        @NonNull MachineKey<P, Q, U> peer,
        @NonNull Function<? super E, ? extends Collection<? extends P>> peersOf,
        @NonNull Function<? super P, Optional<E>> ownerOf,
        @NonNull List<WatchRule<E, S, T, P, Q, U>> rules) {

    public WatchSpec {
        rules = List.copyOf(rules);
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("the watch of " + state + " has no rules");
        }
    }
}
