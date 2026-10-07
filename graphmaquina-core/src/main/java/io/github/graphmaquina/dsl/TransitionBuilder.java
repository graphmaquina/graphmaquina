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
     * The identifier of the edge that recovers from a domain failure no recovery rule takes: the action returns a
     * failure whose failure kind no {@code recover} of this edge declares, or a failure without a kind, or throws a
     * throwable {@code failsOn} lists. A returned failure keeps its failure kind in the outcome. It is resolved from
     * this edge's origin, and the recovery edge runs without its guards and without a failure route of its own.
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

    /**
     * A recovery rule that lists no throwable type: the same as {@code recover(kind, transition, List.of())}, so only
     * a failure the action returns with {@code kind} selects it.
     */
    default TransitionBuilder<E, S, T> recover(Class<? extends FailureKind> kind, T transition) {
        return recover(kind, transition, List.of());
    }

    /**
     * A recovery rule that lists one throwable type: the same as
     * {@code recover(kind, transition, List.of(failsOn))}. A null {@code failsOn} throws {@link NullPointerException}
     * at the call.
     */
    default TransitionBuilder<E, S, T> recover(Class<? extends FailureKind> kind, T transition,
                                               Class<? extends Throwable> failsOn) {
        return recover(kind, transition, List.of(Objects.requireNonNull(failsOn, "failsOn")));
    }

    /**
     * Declares one recovery rule; chainable, one rule per call. A failure the action returns with {@code kind}, as
     * {@code ActionOutcome.failed(kind, reason)} returns it, takes the edge {@code transition} instead of
     * {@code onFailure}, and the outcome reports {@code kind} as its failure kind. Kinds are compared by identity,
     * never through a supertype, and the order of the rules plays no part. A returned failure of a kind no rule
     * declares takes {@code onFailure} and keeps its kind; without {@code onFailure}, the call throws
     * {@code UnhandledFailureException}. {@code transition} is resolved from this edge's origin, and the recovery edge
     * runs without its guards and without consulting its own rules or {@code onFailure}. Two rules may name the same
     * recovery, and a rule may name the one {@code onFailure} names.
     *
     * <p>The engine routes no thrown exception through a rule: an exception that only the types of a rule's
     * {@code failsOn} accept aborts the call, like any other exception that no type of this edge's own
     * {@code failsOn} accepts.
     *
     * <p>{@code failsOn} is copied when the rule is declared. A null argument or element, an identifier of another
     * catalogue and a closed stage are refused at once, as the rest of this stage refuses them. These refusals throw
     * {@link NullPointerException}, {@link IllegalArgumentException} and {@link IllegalStateException}, respectively.
     * A stage is closed after its edge is closed or the graph is built. {@code build()}
     * reports the rest: SG301 when {@code transition} is this edge's own key or no edge with that identifier leaves
     * its origin, SG302 for a type {@code failsOn} may not list, SG303 for a class that is not a failure kind, SG304
     * for a kind two rules declare or a throwable type declared twice within a rule, by two rules, or by a rule and
     * {@code failsOn}, and SG507 for a rule on an edge without an action.
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
