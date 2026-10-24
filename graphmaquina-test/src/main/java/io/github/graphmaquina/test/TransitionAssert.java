package io.github.graphmaquina.test;

import io.github.graphmaquina.api.ActionRef;
import io.github.graphmaquina.api.HookRef;
import io.github.graphmaquina.api.RecoveryRule;
import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.test.internal.Checks;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Chainable, typed assertion over one declared edge of a graph, obtained from
 * {@link GraphAssert#transition(Enum, Enum)}. Every expectation reads a declared field of the
 * {@link TransitionSpec}; none of them runs the edge's action, guards or hook, so a graph whose callbacks
 * would fail if invoked can still be inspected in full, its recovery rules included. Timeout, the classes of
 * the edge's own {@code failsOn}, the recovery rules and the after-commit hook are checked directly,
 * independently of any graphical exporter.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 * @param <T> transition identifier enum of the machine
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class TransitionAssert<E, S extends Enum<S>, T extends Enum<T>> {

    private final String context;
    private final TransitionSpec<E, S, T> actual;

    /** The destination this identifier reaches from this origin; the only reading of a target there is. */
    public TransitionAssert<E, S, T> hasTarget(@NonNull S expected) {
        TransitionSpec<E, S, T> spec = Checks.subject(context, "target", expected, actual);
        Checks.equal(context, "target", expected, spec.to());
        return this;
    }

    public TransitionAssert<E, S, T> isAction() {
        return hasKind(TransitionKind.ACTION);
    }

    public TransitionAssert<E, S, T> isMove() {
        return hasKind(TransitionKind.MOVE);
    }

    public TransitionAssert<E, S, T> hasActionNamed(@NonNull String expected) {
        Optional<String> wanted = Optional.of(expected);
        TransitionSpec<E, S, T> spec = Checks.subject(context, "action", wanted, actual);
        Checks.equal(context, "action", wanted, spec.action().map(ActionRef::name));
        return this;
    }

    public TransitionAssert<E, S, T> hasNoAction() {
        Optional<String> wanted = Optional.empty();
        TransitionSpec<E, S, T> spec = Checks.subject(context, "action", wanted, actual);
        Checks.equal(context, "action", wanted, spec.action().map(ActionRef::name));
        return this;
    }

    /**
     * Asks the action itself, never {@link TransitionSpec#isAsync()}, which is also true for a synchronous
     * action under an asynchronous guard.
     */
    public TransitionAssert<E, S, T> hasAsyncAction() {
        TransitionSpec<E, S, T> spec = Checks.subject(context, "async action", Boolean.TRUE, actual);
        Checks.equal(context, "async action", Boolean.TRUE,
                spec.action().isPresent() && spec.action().get().isAsync());
        return this;
    }

    /** The mirror of {@link #hasAsyncAction()}: an action is present and it is synchronous. */
    public TransitionAssert<E, S, T> hasSyncAction() {
        TransitionSpec<E, S, T> spec = Checks.subject(context, "sync action", Boolean.TRUE, actual);
        Checks.equal(context, "sync action", Boolean.TRUE,
                spec.action().isPresent() && !spec.action().get().isAsync());
        return this;
    }

    /** Compares names and asynchrony in declaration order, keeping duplicates. */
    public TransitionAssert<E, S, T> hasGuardsExactly(GuardExpectation... expected) {
        List<GuardExpectation> wanted = List.of(expected);
        TransitionSpec<E, S, T> spec = Checks.subject(context, "guards", wanted, actual);
        Checks.equal(context, "guards", wanted, guardsOf(spec));
        return this;
    }

    public TransitionAssert<E, S, T> hasNoGuards() {
        List<GuardExpectation> wanted = List.of();
        TransitionSpec<E, S, T> spec = Checks.subject(context, "guards", wanted, actual);
        Checks.equal(context, "guards", wanted, guardsOf(spec));
        return this;
    }

    /**
     * The identifier of the fallback recovery edge, {@code onFailure}, which is resolved from this edge's own origin.
     * The recovery edges that the edge's recovery rules name are not compared here, but by
     * {@link #recoversExactly(List)}.
     */
    public TransitionAssert<E, S, T> hasFailureTransition(@NonNull T expected) {
        Optional<T> wanted = Optional.of(expected);
        TransitionSpec<E, S, T> spec = Checks.subject(context, "failure transition", wanted, actual);
        Checks.equal(context, "failure transition", wanted, spec.onFailure());
        return this;
    }

    public TransitionAssert<E, S, T> hasNoFailureTransition() {
        Optional<T> wanted = Optional.empty();
        TransitionSpec<E, S, T> spec = Checks.subject(context, "failure transition", wanted, actual);
        Checks.equal(context, "failure transition", wanted, spec.onFailure());
        return this;
    }

    /**
     * Compares the classes the edge's own {@code failsOn} declares, in order and without widening to subclasses. The
     * classes a recovery rule lists are not among them: {@link #recoversExactly(List)} compares them.
     */
    @SafeVarargs
    public final TransitionAssert<E, S, T> failsOnExactly(Class<? extends Throwable>... expected) {
        List<Class<? extends Throwable>> declared = new ArrayList<>();
        for (Class<? extends Throwable> type : expected) {
            declared.add(type);
        }
        List<Class<? extends Throwable>> wanted = List.copyOf(declared);
        TransitionSpec<E, S, T> spec = Checks.subject(context, "failsOn", wanted, actual);
        Checks.equal(context, "failsOn", wanted, spec.failsOn());
        return this;
    }

    /**
     * Compares the edge's recovery rules with {@code expected}, exactly and in declaration order: each rule's
     * failure kind, recovery identifier and {@code failsOn} classes, all compared by identity and the classes in
     * their own order. The engine does not select by that order, but the expectation keeps it, as the declaration
     * does. An edge without rules matches only an empty list.
     *
     * @param expected the rules the edge must declare, in declaration order
     * @return this view
     */
    public TransitionAssert<E, S, T> recoversExactly(@NonNull List<RecoveryRule<T>> expected) {
        List<RecoveryRule<T>> wanted = List.copyOf(expected);
        TransitionSpec<E, S, T> spec = Checks.subject(context, "recovery rules", wanted, actual);
        Checks.equal(context, "recovery rules", wanted, spec.recoveryRules());
        return this;
    }

    public TransitionAssert<E, S, T> hasTimeout(@NonNull Duration expected) {
        Optional<Duration> wanted = Optional.of(expected);
        TransitionSpec<E, S, T> spec = Checks.subject(context, "timeout", wanted, actual);
        Checks.equal(context, "timeout", wanted, spec.timeout());
        return this;
    }

    public TransitionAssert<E, S, T> hasNoTimeout() {
        Optional<Duration> wanted = Optional.empty();
        TransitionSpec<E, S, T> spec = Checks.subject(context, "timeout", wanted, actual);
        Checks.equal(context, "timeout", wanted, spec.timeout());
        return this;
    }

    public TransitionAssert<E, S, T> hasHookNamed(@NonNull String expected) {
        Optional<String> wanted = Optional.of(expected);
        TransitionSpec<E, S, T> spec = Checks.subject(context, "hook", wanted, actual);
        Checks.equal(context, "hook", wanted, spec.afterCommit().map(HookRef::name));
        return this;
    }

    public TransitionAssert<E, S, T> hasNoHook() {
        Optional<String> wanted = Optional.empty();
        TransitionSpec<E, S, T> spec = Checks.subject(context, "hook", wanted, actual);
        Checks.equal(context, "hook", wanted, spec.afterCommit().map(HookRef::name));
        return this;
    }

    private TransitionAssert<E, S, T> hasKind(TransitionKind expected) {
        TransitionSpec<E, S, T> spec = Checks.subject(context, "kind", expected, actual);
        Checks.equal(context, "kind", expected, spec.kind());
        return this;
    }

    private static <E, S extends Enum<S>, T extends Enum<T>> List<GuardExpectation> guardsOf(
            TransitionSpec<E, S, T> spec) {
        return spec.guards().stream()
                .map(guard -> new GuardExpectation(guard.name(), guard.isAsync()))
                .toList();
    }
}
