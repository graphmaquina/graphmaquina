package io.github.graphmaquina.test;

import io.github.graphmaquina.test.internal.Checks;
import io.github.graphmaquina.test.internal.InputTypes;
import io.github.graphmaquina.test.probe.ActionCall;
import io.github.graphmaquina.test.probe.GuardCall;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Chainable expectations over the operation input recorded by a synchronous or asynchronous probe. */
public final class CallInputAssert {
    private final String context;
    private final Optional<?> input;

    private CallInputAssert(String context, Optional<?> input) {
        this.context = context;
        this.input = input;
    }

    public static CallInputAssert assertThat(GuardCall<?, ?> actual) {
        return new CallInputAssert("guard call", actual == null ? null : actual.input());
    }

    public static CallInputAssert assertThat(ActionCall<?, ?> actual) {
        return new CallInputAssert("action call", actual == null ? null : actual.input());
    }

    /** Expects no argument (including a null argument) to have reached this callback. */
    public CallInputAssert hasNoInput() {
        Optional<?> recorded = Checks.subject(context, "input", "empty", input);
        if (recorded.isPresent()) {
            Checks.fail(context, "input", "empty", recorded.get());
        }
        return this;
    }

    /** Expects a present input equal to {@code expected}, using {@link Objects#equals}. */
    public CallInputAssert hasInput(Object expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "input", expected, Checks.subject(context, "input", expected, input).orElse(null));
        return this;
    }

    /** Expects the exact argument instance, even when another object would compare equal. */
    public CallInputAssert hasInputSameAs(Object expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.same(context, "input identity", expected,
                Checks.subject(context, "input identity", expected, input).orElse(null));
        return this;
    }

    /**
     * Expects a present input assignable to {@code type}, then runs the typed assertions. Missing or
     * incompatible input raises {@link GraphAssertionError}; failures from {@code assertions} propagate
     * unchanged. Null arguments and primitive type tokens are programming errors, even on a null subject.
     */
    public <I> CallInputAssert inputSatisfies(Class<I> type, Consumer<? super I> assertions) {
        InputTypes.referenceType(type);
        Objects.requireNonNull(assertions, "assertions");
        Object value = Checks.subject(context, "input type", type, input).orElse(null);
        if (!type.isInstance(value)) {
            Checks.fail(context, "input type", type, value == null ? null : value.getClass());
        }
        assertions.accept(type.cast(value));
        return this;
    }
}
