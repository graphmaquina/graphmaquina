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
 * An edge runs at most one action and declares at most one {@code onFailure} (through {@code onFailure} or
 * {@code onFailureAndContinue}), one {@code timeout} and one {@code automatic} mark: a second one is reported as
 * SG507 rather than swapped in. It declares any number of recovery rules, one per {@code recover} or
 * {@code recoverAndContinue} call.
 * Its guards run in declaration order, each under a name of its own, which a rejection reports: the name given or,
 * without one, the simple name of the guard's class. A guard declared twice, and a second guard of one name, are
 * reported as SG105.
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
     * throwable whose most specific declared type is one of {@code failsOn}. A returned failure keeps its failure kind
     * in the outcome; a throwable routed here reports none. It is resolved from this edge's origin, and the recovery
     * edge runs without its guards and without a failure route of its own.
     */
    TransitionBuilder<E, S, T> onFailure(T transition);

    /**
     * The same fallback recovery edge as {@link #onFailure(Enum)}, declared as a continuing route: the edge records
     * {@code onFailureContinues}. It counts as this edge's one {@code onFailure}, so declaring it together with
     * {@code onFailure} is SG507.
     */
    TransitionBuilder<E, S, T> onFailureAndContinue(T transition);

    /**
     * Marks this edge as automatic. At most one mark per edge: a second one is SG507. The mark is recorded on the
     * edge's {@code TransitionSpec} and listed by {@code GraphDefinition.automaticFrom(state)}.
     */
    TransitionBuilder<E, S, T> automatic();

    /**
     * One per call, chainable: a throwable type whose instances take {@code onFailure}, without a failure kind, unless
     * a more specific type that a {@code recover} rule of this edge declares accepts them too. The action's throwable
     * is unwrapped from CompletionException and ExecutionException first, and the most specific declared type wins,
     * in a rule or here, whatever the order of the declarations; when it is one of these types and the edge has no
     * {@code onFailure}, the call throws {@code UnhandledFailureException}. An InterruptedException that the action
     * throws on the calling thread interrupts that thread again, so the recovery it selects runs on an interrupted
     * thread: the engine's wait for a recovery action's stage that is still pending aborts the call as interrupted at
     * once. An Error, a CancellationException and a StateMachineException never match. A declared type also accepts the
     * JDK exceptions the engine throws from the action's context, such as a NullPointerException for a null argument,
     * an IllegalArgumentException for an identifier of another catalogue, a ClassCastException from
     * {@code input(Class)} or a WrongThreadException off the action's thread: they are matched like the action's own. A
     * type listed here twice is harmless; one that a rule also declares is SG304. SG302 accepts only throwable types
     * foreign to the library. It refuses {@code Throwable}, {@code Exception} and {@code RuntimeException} as too
     * broad, an {@code Error} or a {@code CancellationException} as never a domain failure, and
     * {@code CompletionException} or {@code ExecutionException} themselves because they are unwrapped before matching:
     * declare the type of their cause instead.
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
     * never through a supertype. A returned failure of a kind no rule declares takes {@code onFailure} and keeps its
     * kind; without {@code onFailure}, the call throws {@code UnhandledFailureException}.
     *
     * <p>A throwable the action throws, or completes its stage with, once unwrapped from CompletionException and
     * ExecutionException, takes, unless the action runs as a recovery action, the route of the most specific type that
     * accepts it among every type this edge declares, in its rules and in its own {@code failsOn}: a type of this
     * rule's {@code failsOn} routes it to {@code transition} with {@code kind}, and a type of the edge's own
     * {@code failsOn} to {@code onFailure} without a kind. An InterruptedException that the action throws on the
     * calling thread interrupts that thread again, so the recovery it selects runs on an interrupted thread: the
     * engine's wait for a recovery action's stage that is still pending aborts the call as interrupted at once. For the
     * types the DSL admits, the order of the declarations never matters. A throwable no declared type accepts aborts
     * the call, a CancellationException and a StateMachineException that is not a failure of this call included, which
     * never match. The two cases that do not abort are an Error thrown as itself, which propagates, and a failure of
     * the call itself, for which the engine throws it as itself or, when the transaction is poisoned, the failure that
     * poisoned the transaction. An Error that completes the stage or arrives wrapped aborts the call. Once a failed
     * cascade poisoned the transaction, for an accepted throwable of an action that does not run as a recovery action,
     * the engine throws the failure that poisoned the transaction. A declared type also accepts the JDK exceptions the
     * engine throws from the action's context, such as a NullPointerException for a null argument, an
     * IllegalArgumentException for an identifier of another catalogue, a ClassCastException from {@code input(Class)}
     * or a WrongThreadException off the action's thread: they are matched like the action's own. For a throwable a
     * declared type accepts, from an action that does not run as a recovery action, the engine checks the poison before
     * it reads the throwable's message, for synchronous throws and exceptional completions of completed or pending
     * stages alike; the JDK may already have read that message, for example when {@code supplyAsync} wrapped the
     * throwable in a CompletionException. A throwable from the action when it runs as a recovery action is never
     * routed, even one a declared type accepts: an Error thrown as itself propagates; the engine throws a failure of
     * this call as itself or, when the transaction is poisoned, the failure that poisoned the transaction; and on any
     * other throwable the engine aborts the call with it as cause, holding as suppressed the throwable that selected
     * the failure route, when a throwable selected it and is not the same one.
     * {@link io.github.graphmaquina.error.TransitionAbortedException} says what aborting the call means inside a
     * cascade.
     *
     * <p>{@code transition} is resolved from this edge's origin, and the recovery edge runs without its guards and
     * without consulting its own rules or {@code onFailure}. Two rules may name the same recovery, and a rule may name
     * the one {@code onFailure} names: the outcome's failure kind then tells them apart.
     *
     * <p>{@code failsOn} is copied when the rule is declared. A null argument or element, an identifier of another
     * catalogue and a closed stage are refused at once, as the rest of this stage refuses them. {@code build()}
     * reports the rest: SG301 when {@code transition} is this edge's own key or no edge with that identifier leaves
     * its origin, SG302 for a type {@code failsOn} may not list, SG303 for a class that is not a failure kind, SG304
     * for a kind two rules declare or a throwable type declared twice within a rule, by two rules, or by a rule and
     * {@code failsOn}, and SG507 for a rule on an edge without an action.
     */
    TransitionBuilder<E, S, T> recover(Class<? extends FailureKind> kind, T transition,
                                       List<Class<? extends Throwable>> failsOn);

    /**
     * A continuing recovery rule that lists no throwable type: the same as
     * {@code recoverAndContinue(kind, transition, List.of())}.
     */
    default TransitionBuilder<E, S, T> recoverAndContinue(Class<? extends FailureKind> kind, T transition) {
        return recoverAndContinue(kind, transition, List.of());
    }

    /**
     * A continuing recovery rule that lists one throwable type: the same as
     * {@code recoverAndContinue(kind, transition, List.of(failsOn))}. A null {@code failsOn} throws
     * {@link NullPointerException} at the call.
     */
    default TransitionBuilder<E, S, T> recoverAndContinue(Class<? extends FailureKind> kind, T transition,
                                                          Class<? extends Throwable> failsOn) {
        return recoverAndContinue(kind, transition, List.of(Objects.requireNonNull(failsOn, "failsOn")));
    }

    /**
     * Declares one recovery rule exactly as {@link #recover(Class, Enum, List)} does, and records the rule as a
     * continuing one: its {@code RecoveryRule#continueChain()} is true. Every check and every diagnostic of
     * {@code recover} applies to it, and the two kinds of rule share the same failure kinds and throwable types.
     */
    TransitionBuilder<E, S, T> recoverAndContinue(Class<? extends FailureKind> kind, T transition,
                                                  List<Class<? extends Throwable>> failsOn);

    TransitionBuilder<E, S, T> timeout(Duration timeout);

    /**
     * Post-commit hook of this edge. At most one per edge: a second one, through either method, throws
     * {@link IllegalStateException} at the call.
     */
    TransitionBuilder<E, S, T> afterCommit(String name, Consumer<? super E> hook);

    /**
     * Post-commit hook with the committed edge's metadata, graph-derived terminality and the call's flow, which
     * {@link CommitContext} describes. Counts as the edge's single hook.
     */
    TransitionBuilder<E, S, T> afterCommitWith(String name, BiConsumer<? super E, ? super CommitContext<S, T>> hook);

    /** Closes the edge and returns to the graph. */
    GraphBuilder<E, S, T> endTransition();
}
