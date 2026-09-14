package io.github.graphmaquina.test;

import io.github.graphmaquina.api.ActionRef;
import io.github.graphmaquina.api.EdgeKind;
import io.github.graphmaquina.api.EdgeSpec;
import io.github.graphmaquina.api.HookRef;
import io.github.graphmaquina.test.internal.Checks;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Chainable, typed assertion over one cell of a graph's matrix, obtained from
 * {@link GraphAssert#edge(Enum, Enum)}. Every expectation reads a declared field of the
 * {@link EdgeSpec}; none of them runs the cell's action, guards or hook, so a graph whose callbacks
 * would fail if invoked can still be inspected in full. Timeout, {@code failsOn} classes and the
 * after-commit hook are checked directly, independently of any graphical exporter.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 */
public final class EdgeAssert<E, S extends Enum<S>> {

    private final String context;
    private final EdgeSpec<E, S> actual;

    EdgeAssert(String context, EdgeSpec<E, S> actual) {
        this.context = context;
        this.actual = actual;
    }

    public EdgeAssert<E, S> isAction() {
        return hasKind(EdgeKind.ACTION);
    }

    public EdgeAssert<E, S> isMove() {
        return hasKind(EdgeKind.MOVE);
    }

    public EdgeAssert<E, S> isStay() {
        return hasKind(EdgeKind.STAY);
    }

    public EdgeAssert<E, S> isForbidden() {
        return hasKind(EdgeKind.FORBID);
    }

    /** The reason is empty for every kind but {@code FORBID}, which always carries a non-blank one. */
    public EdgeAssert<E, S> hasReason(String expected) {
        Objects.requireNonNull(expected, "expected");
        EdgeSpec<E, S> spec = Checks.subject(context, "reason", expected, actual);
        Checks.equal(context, "reason", expected, spec.reason());
        return this;
    }

    public EdgeAssert<E, S> hasActionNamed(String expected) {
        Objects.requireNonNull(expected, "expected");
        Optional<String> wanted = Optional.of(expected);
        EdgeSpec<E, S> spec = Checks.subject(context, "action", wanted, actual);
        Checks.equal(context, "action", wanted, spec.action().map(ActionRef::name));
        return this;
    }

    public EdgeAssert<E, S> hasNoAction() {
        Optional<String> wanted = Optional.empty();
        EdgeSpec<E, S> spec = Checks.subject(context, "action", wanted, actual);
        Checks.equal(context, "action", wanted, spec.action().map(ActionRef::name));
        return this;
    }

    /** Asks the action itself, never {@link EdgeSpec#isAsync()}, which is also true for a sync action with an async guard. */
    public EdgeAssert<E, S> hasAsyncAction() {
        EdgeSpec<E, S> spec = Checks.subject(context, "async action", Boolean.TRUE, actual);
        Checks.equal(context, "async action", Boolean.TRUE,
                spec.action().isPresent() && spec.action().get().isAsync());
        return this;
    }

    /** The mirror of {@link #hasAsyncAction()}: an action is present and it is synchronous. */
    public EdgeAssert<E, S> hasSyncAction() {
        EdgeSpec<E, S> spec = Checks.subject(context, "sync action", Boolean.TRUE, actual);
        Checks.equal(context, "sync action", Boolean.TRUE,
                spec.action().isPresent() && !spec.action().get().isAsync());
        return this;
    }

    /** Compares names and asynchrony in declaration order, keeping duplicates. */
    public EdgeAssert<E, S> hasGuardsExactly(GuardExpectation... expected) {
        List<GuardExpectation> wanted = List.of(expected);
        EdgeSpec<E, S> spec = Checks.subject(context, "guards", wanted, actual);
        Checks.equal(context, "guards", wanted, guardsOf(spec));
        return this;
    }

    public EdgeAssert<E, S> hasNoGuards() {
        List<GuardExpectation> wanted = List.of();
        EdgeSpec<E, S> spec = Checks.subject(context, "guards", wanted, actual);
        Checks.equal(context, "guards", wanted, guardsOf(spec));
        return this;
    }

    public EdgeAssert<E, S> hasFailureTarget(S expected) {
        Objects.requireNonNull(expected, "expected");
        Optional<S> wanted = Optional.of(expected);
        EdgeSpec<E, S> spec = Checks.subject(context, "failure target", wanted, actual);
        Checks.equal(context, "failure target", wanted, spec.onFailure());
        return this;
    }

    public EdgeAssert<E, S> hasNoFailureTarget() {
        Optional<S> wanted = Optional.empty();
        EdgeSpec<E, S> spec = Checks.subject(context, "failure target", wanted, actual);
        Checks.equal(context, "failure target", wanted, spec.onFailure());
        return this;
    }

    /** Compares the declared classes in order, without widening to subclasses. */
    @SafeVarargs
    public final EdgeAssert<E, S> failsOnExactly(Class<? extends Throwable>... expected) {
        List<Class<? extends Throwable>> declared = new ArrayList<>();
        for (Class<? extends Throwable> type : expected) {
            declared.add(type);
        }
        List<Class<? extends Throwable>> wanted = List.copyOf(declared);
        EdgeSpec<E, S> spec = Checks.subject(context, "failsOn", wanted, actual);
        Checks.equal(context, "failsOn", wanted, spec.failsOn());
        return this;
    }

    public EdgeAssert<E, S> hasTimeout(Duration expected) {
        Objects.requireNonNull(expected, "expected");
        Optional<Duration> wanted = Optional.of(expected);
        EdgeSpec<E, S> spec = Checks.subject(context, "timeout", wanted, actual);
        Checks.equal(context, "timeout", wanted, spec.timeout());
        return this;
    }

    public EdgeAssert<E, S> hasNoTimeout() {
        Optional<Duration> wanted = Optional.empty();
        EdgeSpec<E, S> spec = Checks.subject(context, "timeout", wanted, actual);
        Checks.equal(context, "timeout", wanted, spec.timeout());
        return this;
    }

    public EdgeAssert<E, S> hasHookNamed(String expected) {
        Objects.requireNonNull(expected, "expected");
        Optional<String> wanted = Optional.of(expected);
        EdgeSpec<E, S> spec = Checks.subject(context, "hook", wanted, actual);
        Checks.equal(context, "hook", wanted, spec.afterCommit().map(HookRef::name));
        return this;
    }

    public EdgeAssert<E, S> hasNoHook() {
        Optional<String> wanted = Optional.empty();
        EdgeSpec<E, S> spec = Checks.subject(context, "hook", wanted, actual);
        Checks.equal(context, "hook", wanted, spec.afterCommit().map(HookRef::name));
        return this;
    }

    private EdgeAssert<E, S> hasKind(EdgeKind expected) {
        EdgeSpec<E, S> spec = Checks.subject(context, "kind", expected, actual);
        Checks.equal(context, "kind", expected, spec.kind());
        return this;
    }

    private static <E, S extends Enum<S>> List<GuardExpectation> guardsOf(EdgeSpec<E, S> spec) {
        return spec.guards().stream()
                .map(guard -> new GuardExpectation(guard.name(), guard.isAsync()))
                .toList();
    }
}
