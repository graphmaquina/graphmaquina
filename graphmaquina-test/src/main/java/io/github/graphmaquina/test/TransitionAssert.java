package io.github.graphmaquina.test;

import io.github.graphmaquina.api.ActionRef;
import io.github.graphmaquina.api.HookRef;
import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.test.internal.Checks;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Chainable, typed assertion over one declared edge of a graph, obtained from
 * {@link GraphAssert#transition(Enum, Enum)}. Every expectation reads a declared field of the
 * {@link TransitionSpec}; none of them runs the edge's action, guards or hook, so a graph whose callbacks
 * would fail if invoked can still be inspected in full. Timeout, {@code failsOn} classes and the
 * after-commit hook are checked directly, independently of any graphical exporter.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 * @param <T> transition identifier enum of the machine
 */
public final class TransitionAssert<E, S extends Enum<S>, T extends Enum<T>> {

    private final String context;
    private final TransitionSpec<E, S, T> actual;

    TransitionAssert(String context, TransitionSpec<E, S, T> actual) {
        this.context = context;
        this.actual = actual;
    }

    /** The destination this identifier reaches from this origin; the only reading of a target there is. */
    public TransitionAssert<E, S, T> hasTarget(S expected) {
        Objects.requireNonNull(expected, "expected");
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

    public TransitionAssert<E, S, T> hasActionNamed(String expected) {
        Objects.requireNonNull(expected, "expected");
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

    /** The identifier of the recovery edge, which is resolved from this edge's own origin. */
    public TransitionAssert<E, S, T> hasFailureTransition(T expected) {
        Objects.requireNonNull(expected, "expected");
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

    /** Compares the declared classes in order, without widening to subclasses. */
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

    public TransitionAssert<E, S, T> hasTimeout(Duration expected) {
        Objects.requireNonNull(expected, "expected");
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

    public TransitionAssert<E, S, T> hasHookNamed(String expected) {
        Objects.requireNonNull(expected, "expected");
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
