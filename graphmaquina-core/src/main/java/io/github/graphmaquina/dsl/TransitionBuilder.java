package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.AsyncTransitionAction;
import io.github.graphmaquina.api.AsyncTransitionGuard;
import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionGuard;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Stage of one edge. Every combination of actions, guards, {@code onFailure}, {@code failsOn}, {@code recover} and
 * {@code timeout} is accepted here and validated when the edge is closed: an edge with no action is a plain move,
 * {@code onFailure}, {@code failsOn} and {@code recover} require an action, and {@code timeout} an asynchronous one.
 * An edge runs at most one action and declares at most one {@code onFailure} and one {@code timeout}: a second one is
 * reported as SG507 rather than swapped in. It declares any number of recovery rules, one per {@code recover} call.
 * Once the edge is closed, or the graph built, the stage refuses every call with {@link IllegalStateException}.
 */
public interface TransitionBuilder<E, S extends Enum<S>, T extends Enum<T>> {

    TransitionBuilder<E, S, T> action(TransitionAction<E, S, T> action);

    TransitionBuilder<E, S, T> action(String name, TransitionAction<E, S, T> action);

    TransitionBuilder<E, S, T> asyncAction(AsyncTransitionAction<E, S, T> action);

    TransitionBuilder<E, S, T> asyncAction(String name, AsyncTransitionAction<E, S, T> action);

    TransitionBuilder<E, S, T> guard(TransitionGuard<E, S, T> guard);

    TransitionBuilder<E, S, T> guard(String name, TransitionGuard<E, S, T> guard);

    TransitionBuilder<E, S, T> asyncGuard(AsyncTransitionGuard<E, S, T> guard);

    TransitionBuilder<E, S, T> asyncGuard(String name, AsyncTransitionGuard<E, S, T> guard);

    /**
     * The identifier of the edge that recovers from a domain failure: the action returns a failure, or throws a
     * throwable {@code failsOn} lists. It is resolved from this edge's origin, and the recovery edge runs without its
     * guards and without a failure route of its own.
     */
    TransitionBuilder<E, S, T> onFailure(T transition);

    /**
     * One per call, chainable. SG302 accepts only throwable types foreign to the library. It refuses {@code Throwable},
     * {@code Exception} and {@code RuntimeException} as too broad, an {@code Error} or a
     * {@code CancellationException} as never a domain failure, and {@code CompletionException} or
     * {@code ExecutionException} themselves because they are unwrapped before matching: declare the type of their
     * cause instead. A type that a {@code recover} rule of this edge declares too is SG304, while the same type
     * listed here more than once is accepted.
     */
    TransitionBuilder<E, S, T> failsOn(Class<? extends Throwable> type);

    /** A recovery rule that declares no throwable type: the same as {@code recover(kind, transition, List.of())}. */
    default TransitionBuilder<E, S, T> recover(Class<? extends FailureKind> kind, T transition) {
        return recover(kind, transition, List.of());
    }

    /**
     * A recovery rule that declares one throwable type: the same as
     * {@code recover(kind, transition, List.of(failsOn))}. A null {@code failsOn} throws {@link NullPointerException}
     * at the call.
     */
    default TransitionBuilder<E, S, T> recover(Class<? extends FailureKind> kind, T transition,
                                               Class<? extends Throwable> failsOn) {
        return recover(kind, transition, List.of(Objects.requireNonNull(failsOn, "failsOn")));
    }

    /**
     * Declares a recovery rule of this edge: the failure kind {@code kind}, the identifier {@code transition} of its
     * recovery edge, and the throwable types {@code failsOn} declared with it, none or several. Each call declares one
     * rule, and an edge declares any number of them; two rules may name the same identifier. The list is copied when
     * the rule is declared, in its order.
     *
     * <p>A null argument or a null element throws {@link NullPointerException}, an identifier of another catalogue
     * {@link IllegalArgumentException}, and a call once the edge is closed or the graph built
     * {@link IllegalStateException}, all at the call. The rest is reported when the graph is built, at this edge:
     * <ul>
     *   <li>SG301: {@code transition} names this edge itself, or no edge with that identifier leaves this edge's
     *   origin.</li>
     *   <li>SG302: a throwable type that {@code failsOn(Class)} refuses as well.</li>
     *   <li>SG303: {@code kind} is not a failure kind, which is a named final class or record that implements
     *   {@link FailureKind} and is not an interface, an enum, a local or anonymous class, or a {@code Throwable}.</li>
     *   <li>SG304: two rules declare the same kind, or one throwable type is declared twice in a rule, in two rules,
     *   or in a rule and in {@code failsOn(Class)}.</li>
     *   <li>SG507: the edge runs no action.</li>
     * </ul>
     */
    TransitionBuilder<E, S, T> recover(Class<? extends FailureKind> kind, T transition,
                                       List<Class<? extends Throwable>> failsOn);

    TransitionBuilder<E, S, T> timeout(Duration timeout);

    /**
     * Post-commit hook of this edge. At most one per edge: a second one, through either method, throws
     * {@link IllegalStateException} at the call.
     */
    TransitionBuilder<E, S, T> afterCommit(String name, Consumer<? super E> hook);

    /**
     * Post-commit hook with the committed edge's metadata and graph-derived terminality. Counts as the edge's single
     * hook.
     */
    TransitionBuilder<E, S, T> afterCommitWith(String name, BiConsumer<? super E, ? super CommitContext<S, T>> hook);

    /** Closes the edge and returns to the graph. */
    GraphBuilder<E, S, T> endTransition();
}
