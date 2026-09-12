package io.github.graphmaquina.test;

import io.github.graphmaquina.test.internal.Checks;
import java.util.Objects;

/**
 * Expectations every failure view shares, returning the concrete view so that a chain keeps the narrowed
 * type. They read {@link Throwable#getCause()} and nothing else: a message is engine wording, so no
 * expectation here parses one.
 *
 * @param <SELF> the concrete view returned by every expectation
 */
public abstract class BaseFailureAssert<SELF extends BaseFailureAssert<SELF>> {

    /** Prefix of every failure message raised by this view. */
    final String context;

    /** The throwable under test, null only for a view built straight from a null subject. */
    final Throwable actual;

    BaseFailureAssert(String context, Throwable actual) {
        this.context = context;
        this.actual = actual;
    }

    /** The concrete view, so that an inherited expectation returns the narrowed type. */
    protected abstract SELF self();

    /** Satisfied by any cause the type accepts, subclasses included; a failure without a cause never does. */
    public SELF hasCause(Class<? extends Throwable> expected) {
        Objects.requireNonNull(expected, "expected");
        Throwable failure = Checks.subject(context, "cause", expected, actual);
        Throwable cause = failure.getCause();
        if (!expected.isInstance(cause)) {
            Checks.fail(context, "cause", expected, cause);
        }
        return self();
    }

    /** Compares by reference, so a distinct throwable that compares equal does not satisfy it. */
    public SELF hasCauseSameAs(Throwable expected) {
        Objects.requireNonNull(expected, "expected");
        Throwable failure = Checks.subject(context, "cause", expected, actual);
        Checks.same(context, "cause", expected, failure.getCause());
        return self();
    }

    public SELF hasNoCause() {
        Throwable failure = Checks.subject(context, "cause", "no cause", actual);
        Throwable cause = failure.getCause();
        if (cause != null) {
            Checks.fail(context, "cause", "no cause", cause);
        }
        return self();
    }
}
